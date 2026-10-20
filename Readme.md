# VTunnel SSH

Fork de [mwiede/jsch](https://github.com/mwiede/jsch) para desenvolver uma variante voltada ao tunelamento VPN do VTunnel.

## Estado atual

- Base: tag `jsch-2.28.7`, commit `27e9dfea5a50a0c4f810a2e7c83e32a1906c4e9a`, mesma versao usada atualmente pelo app.
- A primeira limpeza remove os canais SFTP, shell/exec, subsystem, PTY, X11 e encaminhamento de agente. Canais `direct-tcpip`, `forwarded-tcpip` e `direct-streamlocal@openssh.com` permanecem disponiveis. Tipos removidos falham com `JSchException`.
- Foram removidos conectores desktop (Pageant/Windows/Unix SSH agent), GSSAPI/Kerberos e dependencias nativas JNA/junixsocket. Adaptadores Log4j/SLF4J ficam apenas na suite de testes; a interface `Logger` usada pelo Android permanece.
- Senha, chave publica, keyboard-interactive, verificacao de host keys, certificados, proxies, compressao e algoritmos criptograficos foram preservados. A janela de 4 MiB configurada pelo app permanece inalterada.
- Primeira release: [`2.28.7-vpn.1`](https://github.com/TelksBr/vtunnel-ssh/releases/tag/2.28.7-vpn.1). O JAR de build local caiu de 609.520 para 509.527 bytes (16,4% menor).
- Esta variante nao e substituta da API JSch completa: aplicativos que usam recursos removidos precisam continuar na biblioteca upstream.
- Release atual: [`2.28.7-vpn.2`](https://github.com/TelksBr/vtunnel-ssh/releases/tag/2.28.7-vpn.2), usada pela dependencia Gradle do VTunnel.
- A biblioteca notifica o produtor depois de consumir bytes no pipe do canal, eliminando a espera pelo polling de aproximadamente 1 s quando o pipe estava cheio. O adaptador do app ja contorna esse caso; a correcao agora existe tambem na biblioteca, sem depender dele.
- O envio usa imediatamente o credito remoto parcial, sem a espera de polling de 100 ms, e o cancelamento das esperas de credito/rekey preserva a interrupcao. Essas correcoes nao representam ganho geral comprovado de jitter ou vazao.
- Historico, creditos e licencas upstream foram preservados. Consulte [a documentacao original](README.upstream.md).

## Escopo de desenvolvimento

Priorizar canais `direct-tcpip`, encaminhamento SOCKS e o caminho de dados SSH utilizado pela VPN Android.

Primeira melhoria por TDD implementada nesta branch: despertar do produtor no pipe de recepcao apos leitura simples ou em bloco. Os testes usam o pipe cru do canal, incluindo o fluxo estendido, sem adaptador Android. Cobrem rajadas, consumidor lento, wrap, integridade, EOF com dados pendentes, byte zero, skip, interrupcao e fechamento. Crescimento e limite do buffer, credito de janela e algoritmos criptograficos foram preservados.

As proximas investigacoes seguem as medicoes:

1. Bloqueio entre canais na sessao compartilhada e relacao entre consumo, buffers e credito de janela SSH.
2. Custo de leituras/escritas, flush, copias, crescimento/reducao de buffers e alocacoes sob rajadas, apos a correcao do envio com credito remoto parcial em `Session.write`.
3. CPU e memoria do transporte e dos algoritmos criptograficos, com renegociacao e canais concorrentes.
4. Vazao, RTT sob carga e variacao de RTT em perfis moveis com atraso variavel, perda e banda assimetrica; verificar ganhos e regressoes tambem sem limitacao artificial.

Cada mudanca funcional deve comecar por um teste que falha na base e passar por comparacoes repetidas contra a mesma base, com os mesmos perfis e sementes. Simulacao nao substitui validacao no Android real.

O [laboratorio de rede da biblioteca](tools/network/README.md) permite comparar JARs no Android, sem o adaptador do app, preservando a janela de 4 MiB. Ele registra amostras brutas, timeouts, upload confirmado no servidor e CPU do processo. Nao presume melhoria global de jitter a partir da correcao de um bloqueio especifico.

## Build e validacao

### Integracao Android via JitPack

Adicione o repositorio no `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

E a dependencia no modulo Android:

```kotlin
implementation("com.github.TelksBr:vtunnel-ssh:2.28.7-vpn.2")
```

O artefato e compilado a partir da tag de release. Consulte tambem as notas e os arquivos da [release no GitHub](https://github.com/TelksBr/vtunnel-ssh/releases/tag/2.28.7-vpn.2).

O projeto preserva o Maven Wrapper e o build upstream. O `pom.xml` desta base exige uma toolchain JDK 25 ou superior. Com ela instalada:

```powershell
.\mvnw.cmd -B verify
```

Os testes de integracao ficam desativados por padrao no upstream. Para executa-los, consulte os requisitos e a matriz em `.github/workflows/maven.yml` e use `-DskipITs=false` no ambiente apropriado.

No Windows, selecione explicitamente o JDK 25 via `JAVA_HOME` se o Java padrao ainda for 11. A limpeza foi verificada com `clean verify`: 492 testes unitarios passaram; os 4 testes de tunelamento local tambem passaram. O alvo base do bytecode permanece Java 8; as variantes multi-release de criptografia mantidas seguem o upstream.

Nesta branch, a correcao do pipe foi verificada com `clean verify` (498 testes), mais os 4 testes SSH de loopback. Os testes do laboratorio de rede devem rodar tambem no Linux/WSL, onde podem verificar o bloqueio entre o escritor dos contadores compartilhados e o coletor. O coletor rejeita contadores regressivos e preserva as amostras brutas.

A rodada de credito parcial/cancelamento acrescentou quatro testes (502 unitarios aprovados).
`Session.write` envia credito parcial sem aguardar o polling de 100 ms e preserva a interrupcao
ao cancelar esperas de credito/rekey do caminho de dados. Os quatro testes de loopback tambem
passaram com `--small-window` (janela remota de 32 KiB e consumo irregular). Veja
`tools/network/README.md` para reproduzir o probe isolado e a comparacao Android. O ganho
da espera interna nao implica reducao geral de jitter ou aumento geral de vazao.

A matriz Docker de algoritmos/autenticacao foi migrada de SFTP/SCP para eco TCP atraves de `direct-tcpip`, com verificacao de tamanho e SHA-256 dos mesmos dados. Ela exige Docker e BusyBox `nc` com suporte a `-e` dentro das imagens de teste.

Para uma validacao real local sem Docker, instale a dependencia de teste com `python -m pip install -r tools/requirements-test.txt` e inicie `python tools/loopback_ssh.py --ready-file local-validation/loopback.json --stop-file local-validation/stop-loopback`. O fixture escuta somente em 127.0.0.1, usa host key em memoria e senha temporaria aleatoria.

Em outro terminal, carregue `port` e `password` do arquivo gerado nas variaveis de ambiente `VTUNNEL_SSH_TEST_PORT` e `VTUNNEL_SSH_TEST_PASSWORD` e execute `mvnw.cmd -B -DskipITs=false '-Dit.test=TunnelLoopbackIT' failsafe:integration-test failsafe:verify`. Ao terminar, crie o arquivo `local-validation/stop-loopback` para encerrar o servidor e apagar as credenciais temporarias. Para reiniciar, remova antes esse arquivo de parada.

Os testes locais cobrem senha, chave publica, integridade de dados, EOF, quatro canais simultaneos e recuperacao apos recusa de destino. Nao equivalem a medicao de desempenho nem a validacao Android.

## Organizacao e atualizacoes

- `origin`: https://github.com/TelksBr/vtunnel-ssh.git
- `upstream`: https://github.com/mwiede/jsch.git
- `codex/vpn-foundation`: base de desenvolvimento fixada em 2.28.7, acrescida desta documentacao.
- `master`: referencia herdada do fork; nao representa necessariamente a versao usada pelo app.

Antes de incorporar atualizacoes upstream, revisar o diff, correcoes de seguranca e compatibilidade Android, e repetir os testes de regressao. Manter commits de otimizacao separados de atualizacoes upstream.

Os pacotes Java `com.jcraft.jsch` foram mantidos para facilitar a futura integracao com o app; as coordenadas Maven agora identificam o fork. Os workflows herdados ainda precisam de revisao antes de habilitar CI ou releases neste fork. A documentacao upstream arquivada descreve recursos da biblioteca original e nao o conjunto reduzido deste fork.
