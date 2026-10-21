"""Standalone scientific figures: byte-offset diagnosis and scoped before/after measurements."""
import argparse
import json
import statistics
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def style(axis):
    axis.spines[["top","right"]].set_visible(False)
    axis.grid(axis="y",alpha=.15)
    axis.set_axisbelow(True)


def save(fig,path):
    for extension in ["png","svg"]:fig.savefig(path.with_suffix("."+extension),dpi=160)
    plt.close(fig)


def diagnosis(trace,linux,output):
    row=next(row for row in trace if row["label"]=="four_adb" and row["seed"]==1000)
    event=row["echo_remote_events"][0]
    marker=event["before"]["server_now_ns"]
    samples=[s for s in row["stream_samples"] if s["sequence"]==event["sequence"]]
    time=[(s["remote"]["server_now_ns"]-marker)/1e9 for s in samples]
    copied=[s["remote"]["relay_transport"]["received"]/1048576 for s in samples]
    acked=[s["remote"]["relay_transport"]["acked_lower"]/1048576 for s in samples]
    end=samples[0]["range"]["end"]/1048576
    fig,axes=plt.subplots(1,2,figsize=(12,4.8),gridspec_kw={"width_ratios":[1.5,1]})
    ax=axes[0]
    ax.plot(time,copied,marker="o",markersize=3,color="#8b5cf6",label="Prefixo copiado pelo relay")
    ax.plot(time,acked,color="#6b7280",label="Limite inferior do prefixo com ACK TCP")
    ax.axhline(end,color="#8b5cf6",linestyle="--",label="Fim do pacote de eco no fluxo cifrado")
    timeout=next(s for s in samples if s["kind"]=="timeout")
    when=(timeout["remote"]["server_now_ns"]-marker)/1e9
    ax.axvline(when,color="#333333",linestyle=":",label="Snapshot no timeout")
    ax.set_title("Fila observada antes da cópia pelo relay")
    ax.set_xlabel("Tempo após marcador WSL (s)")
    ax.set_ylabel("Posição no fluxo cliente → servidor (MiB)")
    ax.set_ylim(0,max(end,max(copied))*1.12)
    ax.legend(fontsize=7,loc="upper left")
    style(ax)
    ax=axes[1]
    for index,label in enumerate(["before","after"]):
        group=[r for r in linux if r["label"]==label]
        count=sum(r["timeouts"]>0 for r in group)
        ax.bar(index,count,color=["#6b7280","#8b5cf6"][index])
        ax.text(index,count+.12,f"{count}/{len(group)}",ha="center")
    ax.set_xticks([0,1],["Publicada vpn.2","Candidata"])
    ax.set_xlabel("Biblioteca (mesma JVM / TCP direto)")
    ax.set_ylabel("Execuções com timeout (contagem)")
    ax.set_title("Leitor desbloqueado: 3/3 → 0/3 timeouts")
    ax.set_ylim(0,3.8)
    style(ax)
    fig.suptitle("Diagnóstico do relay e correção do monitor de crédito são efeitos distintos",fontsize=13)
    fig.text(.5,.02,"21/10/2026 · esquerda: Android/ADB, seed 1000, linhas ligam snapshots; fila de recepção do socket não medida\n"
             "Direita: 3 pares AB/BA, JVM Linux, enlace direto com perfil móvel simulado (12/3 Mb/s) · 4 uploads + eco · janela 4 MiB\n"
             "Fontes: stream-network e send-linux-ab JSONL. ACK TCP não comprova encaminhamento pelo OpenSSH.",ha="center",fontsize=8)
    fig.tight_layout(rect=[0,.16,1,.92])
    save(fig,output/"diagnosis-and-timeouts")


def android(upload,download,output):
    fig,axes=plt.subplots(1,3,figsize=(13.5,4.8))
    panels=[(upload,"remote_upload_mbps","Upload por Wi-Fi","Vazão entregue (Mb/s)"),
            (upload,"rtt_p95_ms","Eco sob upload por Wi-Fi","p95 por execução (ms)"),
            (download,"mbps","Controle de download por ADB","Vazão recebida (Mb/s)")]
    for ax,(data,metric,title,units) in zip(axes,panels):
        maximum=0
        for index,label in enumerate(["before","after"]):
            values=[r[metric] for r in data if r["label"]==label]
            assert values and all(v is not None for v in values)
            median=statistics.median(values);maximum=max(maximum,max(values))
            ax.bar(index,median,color=["#6b7280","#8b5cf6"][index])
            ax.errorbar(index,median,yerr=[[median-min(values)],[max(values)-median]],color="#333333",capsize=4)
            ax.annotate(f"{median:.2f}",(index,max(values)),xytext=(0,6),textcoords="offset points",ha="center",fontsize=9)
        ax.set_ylim(0,maximum*1.25)
        ax.set_xticks([0,1],["vpn.2","Candidata"])
        ax.set_xlabel("Biblioteca")
        ax.set_ylabel(units)
        ax.set_title(title)
        style(ax)
    fig.suptitle("Celular real: medianas e faixas de três execuções por versão",fontsize=13)
    fig.text(.5,.02,"21/10/2026 · SM-G781B / Android 15 · sem limitação artificial · 4 canais + eco · janela 4 MiB · ordem AB/BA\n"
             "Upload usa Wi-Fi → ponte Windows → WSL; download mostrado usa ADB reverse → WSL. Não é Speedtest nem TUN.\n"
             "O download por Wi-Fi teve timeouts nas duas versões e permanece inconclusivo; dados preservados no relatório.",ha="center",fontsize=8)
    fig.tight_layout(rect=[0,.16,1,.92])
    save(fig,output/"android-validation")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ["trace","linux","upload","download","output"]:parser.add_argument(name,type=Path)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    diagnosis(rows(args.trace),rows(args.linux),args.output)
    android(rows(args.upload),rows(args.download),args.output)
