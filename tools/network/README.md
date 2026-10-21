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
javac --release 8 -cp local-validation/baseline-vpn1.jar -d local-validation/probe-classes tools/network/CipherStreamTrace.java tools/network/TunnelNetworkProbe.java
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

## Diagnostico opcional de contencao (vpn.3-SNAPSHOT)

`network-diag` ativa `Session.setFlowMetrics(new SessionFlowMetrics())` depois da
conexao e antes da abertura dos canais. `network` mantem os contadores desligados.
O probe usa reflexao somente para esta API, permitindo o mesmo bytecode na release
vpn.2 e na candidata. Pedir `network-diag` a uma versao sem a API falha explicitamente.

Cada eco salva `echo_events`: QUEUED, WRITING, FLUSHING, READING ou COMPLETE.
Duracoes de etapas ainda pendentes sao `null`. Em timeout, salva o estado antes de
cancelar o worker e os contadores cumulativos em `flow_at_timeout`; tambem salva
`flow_at_start` (apos aquecimento) e `flow_at_end`. Em timeout guarda ate 16 frames
do worker de eco, somente nomes de classes/metodos, sem argumentos ou payloads. Os snapshots sao nao bloqueantes e eventualmente consistentes:
nao sao um trace atomico, nem associam uma espera da sessao a um canal especifico.
Incluem abertura de canais/aquecimento, enquanto vazao e CPU usam o intervalo de medida.
WRITE_LOCK_WAIT mede espera pelo monitor de envio; ENCODE inclui MAC/criptografia;
TRANSPORT_WRITE inclui escrita/flush; CHANNEL_DELIVERY mede entrega ao consumidor;
CREDIT_WAIT e REKEY_WAIT contam intervalos de espera, inclusive repeticoes de polling.
RequestedDataBytes representa pedidos locais, nao entrega remota. Contadores usam
memoria fixa e nao guardam payloads, destinos ou credenciais. Operacoes pendentes
aparecem em InFlight, e nao no MaxNanos das operacoes ja encerradas.

Para medir overhead, use o mesmo DEX candidato nos dois lados e alterne somente:

```powershell
./tools/network/compare-android.ps1 -BaselineMode network -CandidateMode network-diag -Directions upload -Repetitions 3
```

Para comparar o caminho desligado com a release publicada, use os DEXs respectivos,
ambos em `network`. As etiquetas vpn1/vpn2 sao aliases dos lados do ensaio, nao tags
Git. Sempre registre os hashes, modos e revisoes de cada lado. Uma espera em
FLUSHING com escritores bloqueados indica onde investigar, mas por si so nao prova
starvation do monitor nem justifica paralelizar o cifrador SSH.

## Observacao no destino e controles de fila

[Resultados e limites da rodada de 21/10/2026](../../docs/performance/ssh-queue-analysis-2026-10-21.md).
O modo `queue` adiciona observacao do pedido completo no destino e limitador de
carga **agregado** apenas para testes. Sintaxe:

```text
queue LABEL PROFILE SEED DIRECTION SECONDS HOST SSH_PORT KEY CHANNELS UPLOAD_KBPS [RELAY_SNDBUF] OUTPUT
```

UPLOAD_KBPS=0 e RELAY_SNDBUF=0 deixam carga e kernel sem limite imposto pelo probe.
O eco usa frames de 32 bytes com sequencia nos primeiros 8 bytes. O journal salva
somente o ultimo recebimento/envio e suas contagens, sem payload. Consulta ECHO
retorna relogio monotonic_ns WSL; nao subtrair esse valor do relogio Android.
Depois de timeout, a carga e interrompida e a observacao pode continuar por 15 s.
Esse tail e excluido da medida de vazao/CPU e altera a carga apos o deadline.

Compile CipherStreamTrace.java, TunnelNetworkProbe.java e TunnelNetworkProbeTest.java, execute o harness
Java, empacote o probe e a mesma biblioteca de referencia em DEX. Prepare o lab,
as chaves e ADB reverse como acima; envie o DEX para `probe.zip` ou para os nomes
usados em cada script. As comparacoes sao sequenciais porque RESET reinicia o lab.

```powershell
./tools/network/queue-android.ps1
./tools/network/relay-buffer-android.ps1
python tools/network/queue_summary.py RAW.jsonl SUMMARY.json
python tools/network/plot_queue_analysis.py CHANNELS.jsonl RELAY.jsonl OUTPUT_DIRECTORY
```

No controle Wi-Fi, `wifi_bridge.py --bind HOST_LAN_IP --device PHONE_IP` cria duas
portas temporarias (20080/20084), aceitando apenas o IP do aparelho. Pare esse
processo ao terminar. O controle e escolhido por `-Dvtunnel.probe.controlPort=20084`
no app_process; o caminho ADB usa 19084. `route-android.ps1 -WifiHost HOST_LAN_IP`
alterna os dois caminhos; usa `route-probe.zip`. O caminho Wi-Fi ainda inclui
Windows/WSL e nao equivale a acesso direto a uma VPS publica.

O buffer de 32 KiB foi um experimento descartado: piorou a vazao movel e nao
resolveu os timeouts. Nao o adote como configuracao de producao. A janela local
continua em 4 MiB em todas as variantes. Nao publique uma release com alegacao de
ganho usando apenas o efeito de limitar a carga oferecida.

## Posicao do eco no fluxo e isolamento do envio

[Correcao, testes e resultados de 21/10/2026](../../docs/performance/ssh-send-isolation-2026-10-21.md).
O modo `queue` aceita `-Dvtunnel.probe.offsets=true` (padrao: false). O wrapper
`CipherStreamTrace` conta bytes de todo o fluxo cliente-servidor, inclusive a
identificacao e autenticacao SSH. Um marcador por thread delimita as escritas
do eco; nao salva conteudo. Falhas e escritas parciais mantem limites incertos,
sem tratar bytes aceitos localmente como bytes entregues ao servidor.
O harness `CipherStreamTraceTest.java` verifica limites, excecoes e snapshots
durante uma escrita bloqueada; compile-o junto de CipherStreamTrace.java e
execute `com.jcraft.jsch.CipherStreamTraceTest` no classpath resultante.

Com offsets ligados, RESET ativa o relay observado; a sintaxe do controle e
`RESET PROFILE SEED [RELAY_BUFFER [TRACK_STREAM]]`, com TRACK_STREAM 0/1.
Snapshots numericos passam por um socket UNIX privado, sem gravar por pacote.
`received` e o prefixo copiado para userspace pelo relay: a fila de recepcao
do socket do relay nao e medida. BEFORE_RELAY_COPY inclui essa fila e os
segmentos anteriores do caminho; nao localiza sozinho a espera no Android.
Os limites de bytes aceitos preservam uma escrita em andamento. OUTQ inclui
bytes nao enviados e nao confirmados; OUTQNSD somente os nao enviados.
As duas consultas do kernel sao sequenciais, com timestamps distintos.
ACK TCP nao comprova encaminhamento pelo OpenSSH nem leitura pelo destino.
Campos indisponiveis ficam null e geracoes distintas nao sao correlacionadas.

`stream-android.ps1` compara rotas e overhead dos offsets; os DEXs dessa rodada
usam a mesma candidata de diagnostico. `stream_classify.py RAW SUMMARY` resume
as classes conservadoras. O custo do observador deve ser medido separadamente
do ganho de uma correcao na biblioteca. O probe tambem registra, no timeout,
ate 24 frames de threads SSH, somente classes/metodos e estado.

`compare_linux_path.py` compara proxy TCP com acesso direto ao namespace na
mesma JVM; `compare_linux_library.py` compara duas bibliotecas pelo acesso
direto. Use Linux/JRE 21, o lab privado iniciado e caminhos Linux para JARs.
Consulte `--help`; ambos exigem pasta de saida nova e registram a ordem AB/BA.
`compare_linux_library.py --seed-start N` permite ampliar a coleta com novas
sementes, preservando as rodadas anteriores; o padrao continua 1100.
O destino direto e 10.205.33.2:22022 e o controle e 127.0.0.1:19084; nao passa
pelo relay TCP. O argumento `-Dvtunnel.probe.controlHost=127.0.0.1` seleciona
o controle local. Em PowerShell, coloque argumentos -D com pontos entre aspas
ao chamar Java diretamente; os scripts Python usam listas de argumentos.

Para comparar a candidata com vpn.2 no Android, prepare baseline.zip e
candidate.zip contendo o mesmo probe e respectivas bibliotecas, mais a chave
temporaria. `send-android.ps1 -AdbRoute -Direction download` usa ADB reverse;
`send-android.ps1 -WifiHost HOST_LAN_IP -Direction upload` usa a ponte Wi-Fi.
Nao rode as duas rotas simultaneamente. O script recebe DeviceDirectory,
OutputDirectory e SeedStart; cada par usa 8 s solicitados e 2 s de intervalo.
Resuma com queue_summary.py e preserve tambem as fases inconclusivas.

O fix separa o monitor de envio/lifecycle do monitor do credito remoto; a
janela continua 4 MiB e a criptografia continua serializada pela sessao.
Nao elimina as filas TCP nem garante RTT baixo quando o enlace esta saturado.

## Ciclo bidirecional de credito (desenvolvimento vpn.4)

`DuplexWindowProbe.java` e `compare_duplex_window.py` exercitam uma dependencia
controlada no servidor local: ele envia 3 MiB antes de consumir o upload de
128 KiB, cuja janela remota e de 32 KiB. Depois de enviar os 3 MiB, espera a
reposicao da janela de recepcao do cliente (4 MiB) antes de consumir o upload.
O cliente drena o download concorrentemente. O teste deve renovar a janela
mesmo com o escritor esperando credito remoto. O digest SHA-256 do upload,
todos os bytes do download e EOF sao verificados quando a fase termina.

O script inicia `tools/loopback_ssh.py --duplex-window` em 127.0.0.1, usa senha
aleatoria passada somente pelo ambiente e encerra a fixture ao terminar.
Exige Python/Paramiko e JDK 25 no PATH. Nao execute durante outro benchmark.

```powershell
python tools/network/compare_duplex_window.py --baseline local-validation/vtunnel-ssh-2.28.7-vpn.3.jar --candidate target/vtunnel-ssh-2.28.7-vpn.4-SNAPSHOT.jar --output local-validation/vpn4-duplex-ab
```

Sao tres pares em ordem AB/BA, deadline de 5 s para concluir o recebimento,
AES-256-CTR/HMAC-SHA2-512 e metricas de fluxo desligadas. O tempo medido inclui
a preparacao da espera de credito apos abrir o canal. Um timeout e um resultado
censurado, nao um tempo de conclusao. Nao calcule uma melhora percentual de
latencia a partir dele. Nao ha netem, TUN ou radio celular nesse ensaio.
O campo out_window_size do Paramiko serve exclusivamente como instrumento da
fixture; nao introduz dependencia Paramiko no app ou na biblioteca Java.

`--cipher aes128-gcm@openssh.com` exercita AEAD. `--metrics` ativa os contadores
nos dois lados e `--read-delay-ms 2` pausa a leitura a cada bloco de 16 KiB para
forcar contrapressao. Erros de transferencia ficam no JSONL/resumo e fazem o
script terminar com falha depois de preservar os resultados; nao sao descartados.
Os limites de conclusao, timeout e erro no grafico devem considerar todas as fases.

Para a integracao JUnit, inicie a fixture com `--duplex-window`, carregue port e
password nas variaveis de ambiente do teste e defina
`VTUNNEL_SSH_TEST_DUPLEX=true`. Execute `verify` com `-DskipITs=false` e
`'-Dit.test=TunnelLoopbackIT'`. Sem essa variavel, somente o caso novo e ignorado;
os cinco casos anteriores continuam disponiveis. O controle normal da fixture
continua restrito a echo.vtunnel.test; duplex.vtunnel.test so e aceito com a flag.
