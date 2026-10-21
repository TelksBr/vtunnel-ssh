"""Scientific plots for the queue lab; show missing/censored data explicitly."""
import argparse
import json
import statistics
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def first_receive_seconds(row):
    event = row["echo_remote_events"][0]
    remote = event["after"]
    if remote.get("received_sequence") != event["sequence"] and row["tail_observation"]:
        remote = row["tail_observation"]["remote"]
    if remote.get("received_sequence") != event["sequence"]:
        return None
    return (remote["received_ns"] - event["before"]["server_now_ns"]) / 1e9


def style(axis):
    axis.spines[["top", "right"]].set_visible(False)
    axis.grid(axis="y", alpha=.15)
    axis.set_axisbelow(True)


def save(fig, output):
    for extension in ["png", "svg"]:
        fig.savefig(output.with_suffix("." + extension), dpi=160)
    plt.close(fig)


def plot_queue(source, output):
    data = rows(source)
    keys = ["one_free", "four_free", "one_capped", "four_capped"]
    labels = ["1 livre", "4 livres", "1 a 1,5 Mb/s", "4 a 1,5 Mb/s"]
    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.8))
    for index, key in enumerate(keys):
        group = [row for row in data if row["label"] == key]
        delays = [first_receive_seconds(row) for row in group]
        delays = [value for value in delays if value is not None]
        rates = [row["remote_upload_mbps"] for row in group]
        if delays:
            axes[0].scatter([index]*len(delays), delays, color="#8b5cf6", zorder=3)
            axes[0].text(index, max(delays)+.7, f"{statistics.median(delays):.2f}s", ha="center", fontsize=9)
        else:
            axes[0].text(index, 1, "sem chegada observada", ha="center", rotation=90)
        median = statistics.median(rates)
        axes[1].bar(index, median, color="#8b5cf6", alpha=.8)
        axes[1].errorbar(index, median, yerr=[[median-min(rates)],[max(rates)-median]], color="#333333", capsize=4)
        axes[1].text(index, max(rates)+.07, f"{median:.2f}", ha="center", fontsize=9)
    axes[0].set_title("Chegada do primeiro eco ao destino")
    axes[0].set_ylabel("Tempo após marcador no servidor (s)")
    axes[0].set_ylim(0, 27)
    axes[1].set_title("Upload entregue: mediana e faixa por execução")
    axes[1].set_ylabel("Vazão confirmada no destino (Mb/s)")
    axes[1].set_ylim(0, 3.3)
    for axis in axes:
        axis.set_xticks(range(4), labels)
        axis.set_xlabel("Uploads concorrentes / carga agregada oferecida")
        style(axis)
    fig.suptitle("Fila sob saturação: carga limitada é um controle experimental", fontsize=13)
    fig.text(.5, .02, "21/10/2026 · Android 15 / SM-G781B · perfil móvel simulado · janela SSH 4 MiB · 3 execuções por caso\n"
             "Chegada medida no relógio do servidor; inclui retorno do marcador. Em 4 livres, observada após parar a carga (tail).\n"
             "Fonte: queue JSONL · ADB reverse / WSL netem / OpenSSH · sem TUN ou Speedtest.", ha="center", fontsize=8)
    fig.tight_layout(rect=[0,.15,1,.92])
    save(fig, output)


def plot_relay(source, output):
    data = rows(source)
    fig, axes = plt.subplots(1, 2, figsize=(10.5, 4.5))
    for axis, profile, title in zip(axes, ["mobile-jitter", "unshaped"], ["Perfil móvel", "Sem limitação artificial"]):
        medians = []
        for index, label in enumerate(["relay_default", "relay_32k"]):
            group = [row for row in data if row["profile"] == profile and row["label"] == label]
            values = [row["remote_upload_mbps"] for row in group]
            median = statistics.median(values)
            medians.append(median)
            axis.bar(index, median, color="#8b5cf6", alpha=.8)
            axis.errorbar(index, median, yerr=[[median-min(values)],[max(values)-median]], color="#333333", capsize=4)
            axis.annotate(f"{median:.2f} Mb/s\n{sum(row['timeouts'] for row in group)}/{len(group)} timeouts",
                          (index, max(values)), xytext=(0,5), textcoords="offset points", ha="center", fontsize=9)
        axis.set_ylim(0, max(row["remote_upload_mbps"] for row in data if row["profile"] == profile)*1.35)
        axis.set_title(f"{title}: {(medians[1]/medians[0]-1)*100:.1f}% na mediana")
        axis.set_xticks([0,1], ["Padrão do kernel", "Pedido de 32 KiB"])
        axis.set_xlabel("Buffer de envio do relay privado")
        axis.set_ylabel("Upload confirmado no destino (Mb/s)")
        style(axis)
    fig.suptitle("Experimento descartado: reduzir SO_SNDBUF do relay", fontsize=13)
    fig.text(.5,.02,"21/10/2026 · mesmo DEX / 4 uploads · 3 pares AB/BA por perfil · janela SSH 4 MiB preservada\n"
             "Fonte: relay JSONL · pedido 32 KiB; kernel reportou 64 KiB. Sem mudança de buffer no app.",ha="center",fontsize=8)
    fig.tight_layout(rect=[0,.12,1,.92])
    save(fig, output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("queue", type=Path)
    parser.add_argument("relay", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    args.output_directory.mkdir(parents=True, exist_ok=True)
    plot_queue(args.queue, args.output_directory / "queue-arrival")
    plot_relay(args.relay, args.output_directory / "relay-buffer")
