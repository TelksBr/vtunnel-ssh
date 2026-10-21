"""Plot paired diagnostic cost; never label this instrumentation experiment a speed gain."""
import argparse
import json
import statistics
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def plot(source, output):
    rows = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.6), gridspec_kw={"width_ratios": [1.3, 1]})
    ax = axes[0]
    unshaped = [row for row in rows if row["profile"] == "unshaped"]
    for index, seed in enumerate(sorted({row["seed"] for row in unshaped})):
        pair = {row["label"]: row["remote_upload_mbps"] for row in unshaped if row["seed"] == seed}
        ax.plot([0, 1], [pair["vpn1"], pair["vpn2"]], marker=["o", "s", "^"][index % 3], linestyle=["-", "--", ":"][index % 3],
                color="#8b5cf6", alpha=.7,
                label=f"Par / seed {seed}")
    off = statistics.median(row["remote_upload_mbps"] for row in unshaped if row["label"] == "vpn1")
    on = statistics.median(row["remote_upload_mbps"] for row in unshaped if row["label"] == "vpn2")
    ax.set_xticks([0, 1], ["Desligadas", "Ligadas"])
    ax.set_xlabel("MÃ©tricas da sessÃ£o (mesmo DEX)")
    ax.set_ylabel("Upload confirmado no destino (Mb/s)")
    ax.set_title(f"Custo do diagnÃ³stico: {(on/off-1)*100:.1f}% na mediana")
    ax.set_ylim(0, max(off, on) * 1.15)
    ax.text(0, off+8, f"{off:.1f}", ha="center")
    ax.text(1, on+8, f"{on:.1f}", ha="center")
    ax.legend(fontsize=8)
    ax = axes[1]
    mobile = [row for row in rows if row["profile"] == "mobile-jitter"]
    counts = {stage: sum(event["timeout"] and event["stage"] == stage for row in mobile
                        for event in row["echo_events"])
              for stage in ["QUEUED", "WRITING", "FLUSHING", "READING"]}
    ax.barh(list(counts), list(counts.values()), color="#8b5cf6")
    ax.set_xlabel("Ecos que atingiram timeout (contagem)")
    ax.set_ylabel("Etapa observada antes do cancelamento")
    ax.set_title("Perfil mÃ³vel: 6/6 timeouts na leitura")
    ax.set_xlim(0, max(1, len(mobile)) + 1)
    ax.text(counts["READING"] + .1, 3, str(counts["READING"]), va="center")
    for ax in axes:
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y" if ax is axes[0] else "x", alpha=.15)
        ax.set_axisbelow(True)
    fig.suptitle("SSH no Android â€” diagnÃ³stico inicial, sem ganho de performance declarado", fontsize=13)
    fig.text(.5, .02, "21/10/2026 Â· SM-G781B / Android 15 Â· 3 pares AB/BA por perfil Â· 4 uploads + eco Â· janela 4 MiB\n"
             "Fonte: JSONL app_process / ADB / WSL netem. Rede mÃ³vel simulada; sem TUN ou Speedtest. Timeout nÃ£o Ã© RTT medido.",
             ha="center", fontsize=8)
    fig.tight_layout(rect=[0, .12, 1, .93])
    for extension in ["png", "svg"]:
        fig.savefig(output.with_suffix("." + extension), dpi=160)
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    plot(args.source, args.output)
