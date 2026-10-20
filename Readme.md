# VTunnel SSH

Fork de [mwiede/jsch](https://github.com/mwiede/jsch) para desenvolver uma variante voltada ao tunelamento VPN do VTunnel.

## Estado atual

- Base: tag `jsch-2.28.7`, commit `27e9dfea5a50a0c4f810a2e7c83e32a1906c4e9a`, mesma versao usada atualmente pelo app.
- A primeira limpeza remove os canais SFTP, shell/exec, subsystem, PTY, X11 e encaminhamento de agente. Canais `direct-tcpip`, `forwarded-tcpip` e `direct-streamlocal@openssh.com` permanecem disponiveis. Tipos removidos falham com `JSchException`.
- Foram removidos conectores desktop (Pageant/Windows/Unix SSH agent), GSSAPI/Kerberos e dependencias nativas JNA/junixsocket. Adaptadores Log4j/SLF4J ficam apenas na suite de testes; a interface `Logger` usada pelo Android permanece.
- Senha, chave publica, keyboard-interactive, verificacao de host keys, certificados, proxies, compressao e algoritmos criptograficos foram preservados. A janela e o caminho de dados do app nao foram alterados.
- Coordenadas locais: `com.github.TelksBr:vtunnel-ssh:2.28.7-vpn.1-SNAPSHOT`. Nenhum artefato foi publicado. O JAR de build local caiu de 609.520 para 509.527 bytes (16,4% menor).
- Esta variante nao e substituta da API JSch completa: aplicativos que usam recursos removidos precisam continuar na biblioteca upstream.
- O VTunnel ainda utiliza o artefato upstream; este fork nao esta integrado nem publicado como biblioteca propria.
- Historico, creditos e licencas upstream foram preservados. Consulte [a documentacao original](README.upstream.md).

## Escopo de desenvolvimento

Priorizar canais `direct-tcpip`, encaminhamento SOCKS e o caminho de dados SSH utilizado pela VPN Android.

Primeiras investigacoes, ainda nao implementadas neste fork:

1. Reproduzir por TDD a espera do produtor apos liberar espaco no pipe de recepcao; validar EOF, cancelamento, fechamento e integridade dos bytes.
2. Investigar bloqueio entre canais na sessao compartilhada e a relacao entre consumo, buffers e credito de janela SSH.
3. Comparar throughput, RTT sob carga e jitter em Wi-Fi e perfis moveis com latencia variavel, perda e banda assimetrica, mantendo a janela de 4 MiB configurada pelo app.
4. Medir CPU, alocacoes e memoria antes de remover recursos ou alterar buffers. Nao presumir ganho apenas por reduzir o tamanho da biblioteca.

Cada mudanca funcional deve comecar por um teste que falha na base e passar por comparacoes repetidas contra a mesma base, com os mesmos perfis e sementes. Simulacao nao substitui validacao no Android real.

## Build e validacao

O projeto preserva o Maven Wrapper e o build upstream. O `pom.xml` desta base exige uma toolchain JDK 25 ou superior. Com ela instalada:

```powershell
.\mvnw.cmd -B verify
```

Os testes de integracao ficam desativados por padrao no upstream. Para executa-los, consulte os requisitos e a matriz em `.github/workflows/maven.yml` e use `-DskipITs=false` no ambiente apropriado.

No Windows, selecione explicitamente o JDK 25 via `JAVA_HOME` se o Java padrao ainda for 11. A limpeza foi verificada com `clean verify`: 492 testes unitarios passaram; os 4 testes de tunelamento local tambem passaram. O alvo base do bytecode permanece Java 8; as variantes multi-release de criptografia mantidas seguem o upstream.

A matriz Docker de algoritmos/autenticacao foi migrada de SFTP/SCP para eco TCP atraves de `direct-tcpip`, com verificacao de tamanho e SHA-256 dos mesmos dados. Ela exige Docker e BusyBox `nc` com suporte a `-e` dentro das imagens de teste.

Para uma validacao real local sem Docker, instale a dependencia de teste com `python -m pip install -r tools/requirements-test.txt` e inicie `python tools/loopback_ssh.py --ready-file local-validation/loopback.json --stop-file local-validation/stop-loopback`. O fixture escuta somente em 127.0.0.1, usa host key em memoria e senha temporaria aleatoria.

Em outro terminal, carregue `port` e `password` do arquivo gerado nas variaveis de ambiente `VTUNNEL_SSH_TEST_PORT` e `VTUNNEL_SSH_TEST_PASSWORD` e execute `mvnw.cmd -B -DskipITs=false -Dit.test=TunnelLoopbackIT failsafe:integration-test failsafe:verify`. Ao terminar, crie o arquivo `local-validation/stop-loopback` para encerrar o servidor e apagar as credenciais temporarias. Para reiniciar, remova antes esse arquivo de parada.

Os testes locais cobrem senha, chave publica, integridade de dados, EOF, quatro canais simultaneos e recuperacao apos recusa de destino. Nao equivalem a medicao de desempenho nem a validacao Android.

## Organizacao e atualizacoes

- `origin`: https://github.com/TelksBr/vtunnel-ssh.git
- `upstream`: https://github.com/mwiede/jsch.git
- `codex/vpn-foundation`: base de desenvolvimento fixada em 2.28.7, acrescida desta documentacao.
- `master`: referencia herdada do fork; nao representa necessariamente a versao usada pelo app.

Antes de incorporar atualizacoes upstream, revisar o diff, correcoes de seguranca e compatibilidade Android, e repetir os testes de regressao. Manter commits de otimizacao separados de atualizacoes upstream.

Os pacotes Java `com.jcraft.jsch` foram mantidos para facilitar a futura integracao com o app; as coordenadas Maven agora identificam o fork. Os workflows herdados ainda precisam de revisao antes de habilitar CI ou releases neste fork. A documentacao upstream arquivada descreve recursos da biblioteca original e nao o conjunto reduzido deste fork.
