"""Plot bounded-consumer isolation and mobile-netem guardrails; never label them Speedtest jitter."""
import argparse
import json
from pathlib import Path
import statistics

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    android = rows(args.evidence / 'android/receive.jsonl')
    groups = [('JVM Linux CTR', rows(args.evidence / 'jvm-ctr/receive.jsonl')),
              ('JVM Linux GCM', rows(args.evidence / 'jvm-gcm/receive.jsonl')),
              ('Android CTR', [r for r in android if r['cipher'] == 'aes256-ctr']),
              ('Android GCM', [r for r in android if r['cipher'] == 'aes128-gcm@openssh.com'])]
    plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False})
    colors = {'before': '#81818f', 'after': '#7c3aed'}
    names = {'before': 'vpn.4', 'after': 'vpn.5 candidata'}
    fig, axes = plt.subplots(1, 2, figsize=(13, 5.6))
    summary = []
    maximum_latency = 0
    for index, (title, data) in enumerate(groups):
        for offset, label in [(-.18, 'before'), (.18, 'after')]:
            selected = [r for r in data if r['label'] == label]
            completed = [r for r in selected if r['complete'] and r['integrity_verified']]
            progress = statistics.median(r['echo_before_resume'] for r in selected)
            axes[0].bar(index + offset, progress, .34, color=colors[label],
                        label=names[label] if index == 0 else None)
            axes[0].annotate(f'{progress:g}', (index + offset, progress), xytext=(0, 4),
                             textcoords='offset points', ha='center', fontsize=9)
            values = [r['scheduled_p95_ms'] for r in completed]
            latency = statistics.median(values) if values else None
            if values:
                maximum_latency = max(maximum_latency, max(values))
                axes[1].bar(index + offset, latency, .34, color=colors[label])
                axes[1].errorbar(index + offset, latency,
                                 yerr=[[latency - min(values)], [max(values) - latency]],
                                 color='#363642', capsize=3, linewidth=1)
                axes[1].annotate(f'{latency:.1f}\n{len(completed)}/{len(selected)}',
                                 (index + offset, max(values)), xytext=(0, 5),
                                 textcoords='offset points', ha='center', fontsize=8)
            summary.append({'platform_cipher': title, 'label': label, 'phases': len(selected),
                            'complete': len(completed), 'echo_before_resume_median': progress,
                            'completed_scheduled_p95_median_ms': latency})
    axes[0].set(title='Mensagens entregues durante a pausa do consumidor',
                ylabel='Mensagens recebidas (mediana por fase)', ylim=(0, 92))
    axes[1].set(title='Latência desde o instante programado de envio',
                ylabel='Mediana dos p95 por fase completa (ms; escala log)', yscale='log')
    axes[1].set_ylim(top=maximum_latency * 5)
    for axis in axes:
        axis.set_xticks(range(len(groups)), [g[0] for g in groups])
        axis.set_xlabel('Plataforma e cifra SSH')
        axis.grid(axis='y', alpha=.2)
        axis.set_axisbelow(True)
    axes[0].legend(loc='upper left', fontsize=9, ncol=2)
    fig.suptitle('Recepção SSH com consumidor parado por 1.500 ms', fontsize=15)
    fig.text(.5, .018,
             'Fonte: OpenSSH local · 21/10/2026 · 80 ecos de 32 B a cada 20 ms + download 8 MiB + upload/eco 2 MiB.\n'
             'Faixas: mínimo/máximo por fase; frações: fases completas/total. JVM Linux direta; Android via ADB e ponte TCP própria.\n'
             'Ensaio controlado, sem TUN ou rádio móvel. A latência inclui espera para conseguir enviar a mensagem.',
             ha='center', fontsize=9)
    fig.tight_layout(rect=(0, .14, 1, .94))
    for extension in ['png', 'svg']:
        fig.savefig(args.output / ('receive-isolation.' + extension), dpi=160)
    plt.close(fig)
    network = rows(args.evidence / 'mobile-upload/library.jsonl') + rows(args.evidence / 'mobile-download/library.jsonl')
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.6))
    for index, direction in enumerate(['upload', 'download']):
        metric = 'remote_upload_mbps' if direction == 'upload' else 'mbps'
        selected = [r for r in network if r['direction'] == direction]
        for seed in sorted({r['seed'] for r in selected}):
            pair = {r['label']: r for r in selected if r['seed'] == seed}
            axes[index].plot([0, 1], [pair['before'][metric], pair['after'][metric]],
                             color='#aaaab5', marker='o', alpha=.65)
        medians = [statistics.median(r[metric] for r in selected if r['label'] == label)
                   for label in ['before', 'after']]
        axes[index].plot([0, 1], medians, color=colors['after'], marker='s', linewidth=2.3, label='Mediana')
        axes[index].set(title=direction.capitalize() + ' no netem móvel', ylabel='Vazão entregue (Mb/s)',
                        xlabel='Biblioteca SSH', xticks=[0, 1], xticklabels=['vpn.4', 'vpn.5 candidata'])
        axes[index].grid(axis='y', alpha=.2)
        axes[index].set_ylim(bottom=0)
        axes[index].legend(fontsize=8)
    fig.text(.5, .012, 'Fonte: netem/OpenSSH Linux · 21/10/2026 · 3 pares AB/BA por direção · 4 canais + eco.\n'
             '60 ± 25 ms por direção, perda 0,3%, 12/3 Mb/s; janela 4 MiB. Não mede TUN, rádio ou Speedtest.',
             ha='center', fontsize=9)
    fig.tight_layout(rect=(0, .12, 1, 1))
    for extension in ['png', 'svg']:
        fig.savefig(args.output / ('mobile-throughput.' + extension), dpi=160)
    (args.output / 'plot-summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
