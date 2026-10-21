"""Plot controlled completion outcomes, never treat timeout deadlines as completion latency."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def counts(path, label):
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    rows = [r for r in rows if r['label'] == label]
    return len(rows), sum(r['complete'] for r in rows), sum(r['timeout'] for r in rows), sum(
        not r['complete'] and not r['timeout'] for r in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('control_only_slow', type=Path)
    parser.add_argument('final_gcm_slow', type=Path)
    parser.add_argument('final_ctr_slow', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    panels = [
        ('Consumidor lento · AES-GCM', [
            ('vpn.3', counts(args.final_gcm_slow, 'before')),
            ('Só ajuste\nde crédito', counts(args.control_only_slow, 'after')),
            ('vpn.4\ncandidata', counts(args.final_gcm_slow, 'after'))]),
        ('Consumidor lento · AES-CTR', [
            ('vpn.3', counts(args.final_ctr_slow, 'before')),
            ('vpn.4\ncandidata', counts(args.final_ctr_slow, 'after'))]),
    ]
    fig, axes = plt.subplots(1, 2, figsize=(12, 5.2))
    for ax, (title, stages) in zip(axes, panels):
        bottom = [0] * len(stages)
        for field, color, label in [(1, '#8855ff', 'Concluiu com integridade'),
                                    (2, '#6b7280', 'Timeout'), (3, '#ef8a42', 'Erro / truncamento')]:
            values = [value[field] for _, value in stages]
            ax.bar(range(len(stages)), values, bottom=bottom, color=color, label=label, width=.58)
            for index, value in enumerate(values):
                if value:
                    ax.text(index, bottom[index] + value / 2, str(value), ha='center', va='center', color='white', weight='bold')
            bottom = [a + b for a, b in zip(bottom, values)]
        ax.set_xticks(range(len(stages)), [label for label, _ in stages])
        ax.set_title(title)
        ax.set_ylim(0, 3.5)
        ax.set_yticks(range(4))
        ax.set_ylabel('Execuções (3 por versão / estágio)')
        ax.spines[['top', 'right']].set_visible(False)
    fig.suptitle('VPN.4: eliminar o bloqueio e preservar todos os bytes', fontsize=16)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='lower center', bbox_to_anchor=(.5, .13), ncol=3, frameon=False)
    fig.text(.5, .025, 'Loopback SSH/JVM · 3 MiB download + 128 KiB upload · janela 4 MiB / pipe 1 MiB\n'
             'Pausa de 2 ms por leitura de 16 KiB · ordem AB/BA · não mede jitter, rede móvel ou Speedtest.',
             ha='center', fontsize=9)
    fig.tight_layout(rect=(0, .23, 1, .92))
    for extension in ['png', 'svg']:
        fig.savefig(args.output / ('duplex-window-outcomes.' + extension), dpi=160)


if __name__ == '__main__':
    main()
