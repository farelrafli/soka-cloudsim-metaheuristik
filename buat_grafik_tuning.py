"""
Membaca output/tuning_hasil_ACO.csv dan tuning_hasil_GWO.csv (dibuat oleh Tuning.java), membuat grafik perbandingan parameter,
dan memilih parameter terbaik untuk tiap algoritma.

Aturan pemilihan (tertulis eksplisit agar bisa dipertanggungjawabkan):
  1. Skor sebuah konfigurasi = rata-rata makespan ternormalisasi (makespan / lower bound) pada semua
     skenario (100..1000 task) dan semua seed. Makin kecil makin baik, 1.00 = sempurna.
  2. Konfigurasi terbaik = skor terkecil. Karena populasi dan iterasi lebih besar hampir selalu lebih baik
     tetapi lebih mahal, dipilih konfigurasi PALING MURAH (jumlah evaluasi paling sedikit) yang skornya
     masih dalam TOLERANSI dari skor terbaik.

Hanya butuh matplotlib:  python3 buat_grafik_tuning.py
"""
import csv
import os
import statistics
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

BASE = os.path.dirname(os.path.abspath(__file__))
CSV_PATHS = [os.path.join(BASE, "output", "tuning_hasil_%s.csv" % a) for a in ("ACO", "GWO")]
OUT_DIR = os.path.join(BASE, "output", "grafik_tuning")
TOLERANSI = 0.01  # 1% dari skor terbaik
WARNA = {"ACO": "#21295C", "GWO": "#E07A5F"}
ALGOS = ["ACO", "GWO"]


def main():
    for path in CSV_PATHS:
        if not os.path.exists(path):
            raise SystemExit("CSV tidak ditemukan: %s\nJalankan dulu Tuning.java" % path)
    os.makedirs(OUT_DIR, exist_ok=True)

    # data[(algo, pop, iter)][seed] -> daftar makespan ternormalisasi (satu per skenario)
    data = defaultdict(lambda: defaultdict(list))
    evals = {}
    for path in CSV_PATHS:
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                key = (r["Algoritma"], int(r["Populasi"]), int(r["Iterasi"]))
                data[key][int(r["Seed"])].append(float(r["Makespan_Norm"]))
                evals[key] = int(r["Evaluasi"])

    # skor per konfigurasi: rata-rata per seed (rata-rata antar skenario), lalu rata-rata dan simpangan baku antar seed
    summary = {}
    for key, per_seed in data.items():
        seed_means = [statistics.mean(v) for v in per_seed.values()]
        summary[key] = {
            "mean": statistics.mean(seed_means),
            "std": statistics.pstdev(seed_means),
            "evals": evals[key],
            "seed_means": seed_means,
        }

    # ---- pilih parameter terbaik
    chosen = {}
    for algo in ALGOS:
        keys = [k for k in summary if k[0] == algo]
        best_mean = min(summary[k]["mean"] for k in keys)
        ok = [k for k in keys if summary[k]["mean"] <= best_mean * (1 + TOLERANSI)]
        pick = min(ok, key=lambda k: (summary[k]["evals"], summary[k]["mean"]))
        absolute_best = min(keys, key=lambda k: summary[k]["mean"])
        chosen[algo] = (pick, absolute_best)

    # ---- tabel ringkasan
    with open(os.path.join(BASE, "output", "tuning_ringkasan.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["Algoritma", "Populasi", "Iterasi", "Evaluasi", "Skor_Rata2", "Skor_StdSeed", "Terpilih"])
        for key in sorted(summary, key=lambda k: (k[0], k[1], k[2])):
            s = summary[key]
            w.writerow([key[0], key[1], key[2], s["evals"], "%.4f" % s["mean"], "%.4f" % s["std"],
                        "ya" if key == chosen[key[0]][0] else ""])

    with open(os.path.join(BASE, "output", "parameter_terpilih.txt"), "w", encoding="utf-8") as f:
        for algo in ALGOS:
            pick, best = chosen[algo]
            f.write("%s: populasi=%d, iterasi=%d (evaluasi=%d, skor=%.4f +- %.4f antar seed)\n" % (
                algo, pick[1], pick[2], summary[pick]["evals"], summary[pick]["mean"], summary[pick]["std"]))
            f.write("   skor terbaik mutlak: populasi=%d, iterasi=%d (evaluasi=%d, skor=%.4f)\n" % (
                best[1], best[2], summary[best]["evals"], summary[best]["mean"]))
    print(open(os.path.join(BASE, "output", "parameter_terpilih.txt"), encoding="utf-8").read())

    def rentang(algo):
        return (sorted({k[1] for k in summary if k[0] == algo}),
                sorted({k[2] for k in summary if k[0] == algo}))

    # ---- grafik 1: skor vs populasi (satu garis per jumlah iterasi)
    fig, axes = plt.subplots(1, 2, figsize=(15, 5))
    for ax, algo in zip(axes, ALGOS):
        pops, iters = rentang(algo)
        for it in iters:
            ax.plot(pops, [summary[(algo, p, it)]["mean"] for p in pops], marker="o", label="%d iterasi" % it)
        pick = chosen[algo][0]
        ax.scatter([pick[1]], [summary[pick]["mean"]], s=220, facecolors="none", edgecolors="red", linewidths=2, label="terpilih")
        ax.set_title("%s: pengaruh ukuran populasi" % algo)
        ax.set_xlabel("Ukuran populasi (%s)" % ("semut" if algo == "ACO" else "serigala"))
        ax.set_ylabel("Makespan / lower bound (rata-rata, makin kecil makin baik)")
        ax.set_xticks(pops)
        ax.grid(alpha=0.3)
        ax.legend(fontsize=8, loc="upper left", bbox_to_anchor=(1.01, 1.0))
    plt.tight_layout()
    plt.savefig(os.path.join(OUT_DIR, "1_populasi.png"), dpi=200)
    plt.close()

    # ---- grafik 2: skor vs iterasi (satu garis per populasi)
    fig, axes = plt.subplots(1, 2, figsize=(15, 5))
    for ax, algo in zip(axes, ALGOS):
        pops, iters = rentang(algo)
        for p in pops:
            ax.plot(iters, [summary[(algo, p, it)]["mean"] for it in iters], marker="o", label="populasi %d" % p)
        pick = chosen[algo][0]
        ax.scatter([pick[2]], [summary[pick]["mean"]], s=220, facecolors="none", edgecolors="red", linewidths=2, label="terpilih")
        ax.set_title("%s: pengaruh jumlah iterasi" % algo)
        ax.set_xlabel("Jumlah iterasi")
        ax.set_ylabel("Makespan / lower bound (rata-rata)")
        ax.set_xticks(iters)
        ax.grid(alpha=0.3)
        ax.legend(fontsize=8, loc="upper left", bbox_to_anchor=(1.01, 1.0))
    plt.tight_layout()
    plt.savefig(os.path.join(OUT_DIR, "2_iterasi.png"), dpi=200)
    plt.close()

    # ---- grafik 3: skor vs biaya komputasi (jumlah evaluasi)
    plt.figure(figsize=(8, 5))
    for algo in ALGOS:
        ks = sorted([k for k in summary if k[0] == algo], key=lambda k: summary[k]["evals"])
        plt.scatter([summary[k]["evals"] for k in ks], [summary[k]["mean"] for k in ks], label=algo, color=WARNA[algo], alpha=0.75)
        pick = chosen[algo][0]
        plt.scatter([summary[pick]["evals"]], [summary[pick]["mean"]], s=260, facecolors="none", edgecolors=WARNA[algo], linewidths=2.5)
    plt.xscale("log")
    plt.title("Kualitas vs biaya komputasi (lingkaran = parameter terpilih)")
    plt.xlabel("Jumlah evaluasi jadwal (skala log)")
    plt.ylabel("Makespan / lower bound (rata-rata)")
    plt.grid(alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(OUT_DIR, "3_biaya_vs_kualitas.png"), dpi=200)
    plt.close()

    # ---- grafik 4: variasi antar seed pada parameter terpilih
    plt.figure(figsize=(7, 5))
    labels, series = [], []
    for algo in ALGOS:
        pick = chosen[algo][0]
        labels.append("%s\n(pop %d, iter %d)" % (algo, pick[1], pick[2]))
        series.append(summary[pick]["seed_means"])
    bp = plt.boxplot(series, patch_artist=True)
    plt.xticks(range(1, len(labels) + 1), labels)
    for patch, algo in zip(bp["boxes"], ALGOS):
        patch.set_facecolor(WARNA[algo])
        patch.set_alpha(0.6)
    plt.title("Variasi antar seed (10 seed) pada parameter terpilih")
    plt.ylabel("Makespan / lower bound (rata-rata antar skenario)")
    plt.grid(alpha=0.3, axis="y")
    plt.tight_layout()
    plt.savefig(os.path.join(OUT_DIR, "4_variasi_seed.png"), dpi=200)
    plt.close()
    print("Grafik tersimpan di", OUT_DIR)


if __name__ == "__main__":
    main()
