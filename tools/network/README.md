# Laboratorio de transporte SSH para VPN

Compara bibliotecas no mesmo Android, com `direct-tcpip` cru: sem o adaptador de notificacao do app, SOCKS, TUN ou tun2socks. O laboratorio de rede foi trazido de `VTunnel/tools/ssh-jitter`; os nomes de namespace, interfaces e pasta temporaria sao exclusivos desta versao.

O caminho e Android -> ADB reverse -> relay TCP WSL -> veth/netem -> OpenSSH -> destinos locais de eco, download e upload. ADB/relay acrescentam buffers. Nao equivale a uma rede celular real nem a Speedtest.

## Perfis e medidas

- `unshaped`: sem qdiscs artificiais nas duas interfaces privadas.
- `mobile-jitter`: 60 +/- 25 ms de atraso por sentido, distribuicao normal, perda aleatoria de 0,3%, 12/3 Mbit/s (download/upload), fila de 128 skbs.
- `deep` e `shallow`: 20 ms por sentido, 8/4 Mbit/s e filas de 1000 ou 32 skbs.

Cada fase reinicia os endpoints e o sshd privados e descarta os dois qdiscs antes de reaplicar o perfil. A semente e 526 + repeticao; no sentido oposto usa-se semente + 1. As variantes alternam a ordem na segunda repeticao. Sementes iguais nao garantem perder os mesmos segmentos se o tempo dos fluxos mudar.

O probe usa AES-256-CTR, HMAC-SHA2-512, janela de **4 MiB por canal** e pipe com limite de **1 MiB**. Abre quatro canais de carga e um canal de eco na mesma sessao. O aquecimento e de 1,5 s sob carga ou 300 ms em repouso. Cada eco envia 32 bytes e exige resposta identica, com espera de 20 ms entre amostras.

Registra RTTs brutos, p50/p95, media da diferenca absoluta entre RTTs consecutivos, timeouts, CPU do processo Android e vazao. Upload relevante e `remote_upload_mbps`, confirmado pelo destino remoto durante o intervalo; `mbps` de upload conta apenas bytes aceitos localmente. A variacao de RTT tem definicao propria e nao deve ser chamada de jitter Ookla. Um timeout de 12 s nao e jitter zero. CPU exclui abertura de canais/aquecimento e deve ser avaliada junto da quantidade efetiva de dados, nunca isoladamente.

Os escritores do mmap e o coletor usam `flock` para impedir snapshots parciais. O probe salva `upload_before`/`upload_after` e falha se o contador diminuir; o resumo tambem rejeita esse caso. A coleta original sem bloqueio produziu uma amostra negativa no upload: essas series exploratorias foram descartadas e nao sustentam conclusoes de desempenho. Rode `python3 -m unittest discover -s tools/network -p 'test_*.py'` no Linux para incluir o teste concorrente dos contadores.

O modo `wakeup` isola o pipe: enche 1 MiB, verifica que o produtor esta esperando, consome 32 KiB e mede ate a escrita pendente terminar. Sao 12 repeticoes, sem rede. Esse teste nao mede RTT/jitter.

A correcao usa uma unica notificacao em `read()`: a leitura em bloco do [Android API 25](https://android.googlesource.com/platform/libcore/+/android-7.1.0_r1/ojluni/src/main/java/java/io/PipedInputStream.java) e do JDK testado delega a primeira leitura a esse metodo. O monitor da leitura em bloco continua retido ate terminar a copia. O teste de despertar em bloco deve permanecer na matriz Android para detectar qualquer mudanca dessa delegacao.

Na validacao isolada de 2026-10-20 no SM-G781B/Android 15, a release `vpn.1` teve mediana de 999,79 ms e maximo de 1000,55 ms; a candidata com notificacao unica teve mediana de 0,96 ms e maximo de 1,88 ms (12 repeticoes cada). Esses numeros medem exclusivamente a liberacao do produtor apos consumir dados do pipe cheio. Evidencia local ignorada: `local-validation/android-wakeup-single.jsonl`.

## Preparacao (PowerShell, JDK 25 e Android SDK)

Guarde o JAR da release de referencia em `local-validation/baseline-vpn1.jar`. Compile a candidata com `mvnw.cmd -B clean verify`. Os dois JARs devem vir de revisoes conhecidas; guarde seus SHA-256 junto dos resultados.

```powershell
New-Item -ItemType Directory -Force local-validation/probe-classes | Out-Null
javac --release 8 -cp local-validation/baseline-vpn1.jar -d local-validation/probe-classes tools/network/TunnelNetworkProbe.java
jar --create --file local-validation/network-probe.jar -C local-validation/probe-classes .
```

Com `d8.bat` do SDK no PATH e `android.jar` correspondente em `$androidPlatformJar`:

```powershell
d8.bat --min-api 25 --lib $androidPlatformJar --output local-validation/baseline-dex.zip local-validation/baseline-vpn1.jar local-validation/network-probe.jar
d8.bat --min-api 25 --lib $androidPlatformJar --output local-validation/candidate-dex.zip target/vtunnel-ssh-2.28.7-vpn.2-SNAPSHOT.jar local-validation/network-probe.jar
```

O WSL precisa de Python 3, iproute2 com netem/seed e OpenSSH. `up` cria somente `vtsshbench`, `vtssh-host/vtssh-peer` e `/tmp/vtunnel-ssh-network`. Nao use junto de outra execucao deste mesmo laboratorio. As chaves geradas sao temporarias e limitadas ao sshd privado.

Na raiz do clone, substitua o caminho `/mnt/c/...` pelo seu caminho WSL:

```powershell
python -m unittest discover -s tools/network -p test_lab.py
wsl -u root -e python3 /mnt/c/Users/Telks/Desktop/vtunnel-ssh/tools/network/lab.py up
wsl -u root -e python3 /mnt/c/Users/Telks/Desktop/vtunnel-ssh/tools/network/lab.py start-control
wsl -u root -e cp /tmp/vtunnel-ssh-network/client_key /mnt/c/Users/Telks/Desktop/vtunnel-ssh/local-validation/network-client-key
adb reverse --list
```

As portas 19080 e 19084 precisam estar livres de outros testes. Em um unico aparelho autorizado:

```powershell
adb shell mkdir -p /data/local/tmp/vtunnel-lib-vpn2
adb push local-validation/baseline-dex.zip /data/local/tmp/vtunnel-lib-vpn2/baseline.zip
adb push local-validation/candidate-dex.zip /data/local/tmp/vtunnel-lib-vpn2/candidate.zip
adb push local-validation/network-client-key /data/local/tmp/vtunnel-lib-vpn2/client_key
adb shell chmod 600 /data/local/tmp/vtunnel-lib-vpn2/client_key
adb reverse tcp:19080 tcp:19080
adb reverse tcp:19084 tcp:19084
```

## Comparacao

### Credito parcial de envio

`WindowCreditProbe.java` executa o caminho real de fragmentacao de `Session.write`,
substituindo somente a criptografia/socket por um destino em memoria. Cada execucao
descarta seis aquecimentos e coleta 30 casos de credito parcial e 30 de credito total.
Os dados aleatorios sao conferidos byte a byte e o credito deve terminar em zero.
Compile-o no mesmo pacote e inclua seu JAR nos DEX A/B, como `TunnelNetworkProbe`.
Execute no Android com:

```powershell
adb shell 'CLASSPATH=/data/local/tmp/vtunnel-credit/baseline.zip app_process /system/bin com.jcraft.jsch.WindowCreditProbe before /data/local/tmp/vtunnel-credit/credit.jsonl'
adb shell 'CLASSPATH=/data/local/tmp/vtunnel-credit/candidate.zip app_process /system/bin com.jcraft.jsch.WindowCreditProbe after /data/local/tmp/vtunnel-credit/credit.jsonl'
```

Repita na ordem inversa. Esta medida isola a espera de credito remoto: nao mede RTT,
jitter nem velocidade da VPN. A referencia desta rodada ja inclui a notificacao de
leitura do pipe; a diferenca A/B e a correcao do credito parcial/cancelamento em Session.
`tools/loopback_ssh.py --small-window` permite validar os pacotes criptografados com
janela remota de 32 KiB e consumo em blocos irregulares de 4093 bytes.

```powershell
adb shell 'CLASSPATH=/data/local/tmp/vtunnel-lib-vpn2/baseline.zip app_process /system/bin com.jcraft.jsch.TunnelNetworkProbe wakeup vpn1 /data/local/tmp/vtunnel-lib-vpn2/wakeup.jsonl'
adb shell 'CLASSPATH=/data/local/tmp/vtunnel-lib-vpn2/candidate.zip app_process /system/bin com.jcraft.jsch.TunnelNetworkProbe wakeup vpn2 /data/local/tmp/vtunnel-lib-vpn2/wakeup.jsonl'
adb pull /data/local/tmp/vtunnel-lib-vpn2/wakeup.jsonl local-validation/android-wakeup.jsonl
./tools/network/compare-android.ps1 -Repetitions 3 -Seconds 6
python tools/network/summarize.py local-validation/network/network-RUN.jsonl local-validation/network/summary.json
```

O script salva JSONL bruto e progresso em `local-validation/network`. Nao misture o smoke test com a serie A/B. Resuma as medianas e faixas **por execucao**, sem juntar RTTs de repeticoes distintas em uma unica distribuicao. Inclua timeouts, erros, quantidade de amostras e limitacoes; tres repeticoes nao demonstram ausencia de regressao em todas as redes/aparelhos.

Para investigar um sinal de regressao, repita somente a fase relevante com mais pares, intervalos e tempo de medida. Por exemplo:

```powershell
./tools/network/compare-android.ps1 -Profiles unshaped -Directions upload -Repetitions 6 -Seconds 12 -SeedStart 600 -CooldownSeconds 3
```

## Encerramento

Depois de copiar os resultados, remova somente os redirecionamentos e a pasta criados acima:

```powershell
adb reverse --remove tcp:19080
adb reverse --remove tcp:19084
adb shell rm -rf /data/local/tmp/vtunnel-lib-vpn2
wsl -u root -e python3 /mnt/c/Users/Telks/Desktop/vtunnel-ssh/tools/network/lab.py down
Remove-Item -LiteralPath local-validation/network-client-key
```

`down` encerra apenas os processos do namespace privado, remove suas interfaces e chaves. Nenhuma credencial ou APK do app e necessario para esse ensaio.
