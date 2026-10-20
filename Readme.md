# VTunnel SSH

Fork de [mwiede/jsch](https://github.com/mwiede/jsch) para desenvolver uma variante voltada ao tunelamento VPN do VTunnel.

## Estado inicial

- Base: tag `jsch-2.28.7`, commit `27e9dfea5a50a0c4f810a2e7c83e32a1906c4e9a`, mesma versao usada atualmente pelo app.
- Esta preparacao adiciona somente documentacao. Codigo Java, dependencias, API e comportamento permanecem iguais aos da tag de origem.
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

Nesta preparacao nao foi executado build: o Java padrao encontrado no ambiente e o JDK 11. Nenhum binario deste fork foi gerado ou publicado.

## Organizacao e atualizacoes

- `origin`: https://github.com/TelksBr/vtunnel-ssh.git
- `upstream`: https://github.com/mwiede/jsch.git
- `codex/vpn-foundation`: base de desenvolvimento fixada em 2.28.7, acrescida desta documentacao.
- `master`: referencia herdada do fork; nao representa necessariamente a versao usada pelo app.

Antes de incorporar atualizacoes upstream, revisar o diff, correcoes de seguranca e compatibilidade Android, e repetir os testes de regressao. Manter commits de otimizacao separados de atualizacoes upstream.

As coordenadas Maven e os pacotes Java ainda sao os originais. Definir coordenadas e versionamento proprios antes de qualquer publicacao; nao publicar usando a identidade Maven do upstream. Os workflows herdados tambem precisam de revisao antes de habilitar CI ou releases neste fork.
