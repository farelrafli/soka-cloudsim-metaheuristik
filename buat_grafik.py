import argparse
import csv
import math
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

BASE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(BASE, "output", "hasil_metrik_perbandingan.csv")
OUT_DIR = os.path.join(BASE, "output", "grafik_baru")

WARNA_ALGO = {"ACO": "#1D3557", "GWO": "#E63946"}

# (kolom_csv, nama_pendek, judul_metrik, satuan, apakah_lebih_kecil_lebih_baik)
METRIK = [
    ("Makespan_Aktual",    "makespan",    "Makespan Aktual",       "detik",       True),
    ("Throughput",         "throughput",  "Throughput",            "task/detik",  False),
    ("Utilization_Persen", "utilization", "Resource Utilization",  "%",           False),
    ("Imbalance",          "imbalance",   "Degree of Imbalance",   "detik",       True),
    ("Total_Cost",         "cost",        "Total Cost",            "unit biaya",  True),
]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Membuat grafik perbandingan Populasi, Iterasi, dan Seed untuk semua metrik CloudSim"
    )
    parser.add_argument("--csv", default=CSV_PATH, help="Path file CSV hasil simulasi")
    parser.add_argument("--out", default=OUT_DIR, help="Folder output grafik")
    parser.add_argument(
        "--task",
        type=int,
        default=None,
        help="Filter N_Task tertentu (misal: 1000). Jika kosong, menghitung rata-rata lintas semua N_Task (100..1000).",
    )
    return parser.parse_args()


def load_data(path, filter_task=None):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            if not r or not r.get("Algoritma"):
                continue
            n_task = int(r["N_Task"])
            if filter_task is not None and n_task != filter_task:
                continue
            rows.append({
                "algo": r["Algoritma"].strip(),
                "seed": int(r["Seed"]),
                "pop": int(r["Population"]),
                "iter": int(r["Iterations"]),
                "task": n_task,
                "Makespan_Aktual": float(r["Makespan_Aktual"]),
                "Throughput": float(r["Throughput"]),
                "Utilization_Persen": float(r["Utilization_Persen"]),
                "Imbalance": float(r["Imbalance"]),
                "Total_Cost": float(r["Total_Cost"]),
            })
    return rows


def plot_heatmap_per_seed(seed, seed_rows, pops, iters, algos, task_label, out_folder):
    """
    Membuat Heatmap berdampingan (ACO vs GWO) untuk satu Seed:
    Sumbu Y = Population (5..20), Sumbu X = Iterations (20..100),
    dengan nilai angka eksak di dalam tiap sel.
    """
    for kolom, slug, judul, satuan, lower_better in METRIK:
        n_algos = len(algos)
        fig, axes = plt.subplots(1, n_algos, figsize=(6.2 * n_algos, 4.8), squeeze=False)

        # Hitung matriks nilai per algo terlebih dahulu agar skala warna seragam antar-algoritma
        matrices = {}
        all_vals = []
        for algo in algos:
            mat = np.full((len(pops), len(iters)), np.nan)
            for pi, p in enumerate(pops):
                for ii, it in enumerate(iters):
                    vals = [r[kolom] for r in seed_rows if r["algo"] == algo and r["pop"] == p and r["iter"] == it]
                    if vals:
                        mean_v = sum(vals) / len(vals)
                        mat[pi, ii] = mean_v
                        all_vals.append(mean_v)
            matrices[algo] = mat

        if not all_vals:
            plt.close(fig)
            continue

        vmin, vmax = min(all_vals), max(all_vals)
        cmap = "YlGnBu_r" if lower_better else "YlGnBu"

        for idx, algo in enumerate(algos):
            ax = axes[0, idx]
            mat = matrices[algo]
            im = ax.imshow(mat, cmap=cmap, aspect="auto", vmin=vmin, vmax=vmax)

            ax.set_xticks(range(len(iters)))
            ax.set_xticklabels(iters)
            ax.set_yticks(range(len(pops)))
            ax.set_yticklabels(pops)
            ax.set_xlabel("Iterations")
            if idx == 0:
                ax.set_ylabel("Population")
            ax.set_title(f"Algoritma {algo}", fontweight="bold", fontsize=11)

            # Tulis angka eksak di dalam setiap kotak heatmap
            mid = (vmin + vmax) / 2.0
            for pi in range(len(pops)):
                for ii in range(len(iters)):
                    val = mat[pi, ii]
                    if not np.isnan(val):
                        # Warna teks kontras mengikuti gelap/terangnya sel
                        is_dark = (val < mid) if lower_better else (val > mid)
                        txt_color = "white" if is_dark else "black"
                        fmt = f"{val:.2f}" if val >= 10 else f"{val:.4f}"
                        ax.text(ii, pi, fmt, ha="center", va="center", color=txt_color, fontsize=8.5, fontweight="bold")
                    else:
                        ax.text(ii, pi, "-", ha="center", va="center", color="gray", fontsize=8.5)

        cbar = fig.colorbar(im, ax=axes.ravel().tolist(), shrink=0.85, pad=0.03)
        cbar.set_label(f"{judul} ({satuan})")

        arah = "Lebih Kecil = Lebih Baik" if lower_better else "Lebih Besar = Lebih Baik"
        fig.suptitle(
            f"Heatmap {judul} — Seed {seed} ({task_label})\n[{arah}]",
            fontsize=12.5,
            fontweight="bold",
            y=1.02,
        )
        out_path = os.path.join(out_folder, f"1_heatmap_{slug}.png")
        plt.savefig(out_path, dpi=200, bbox_inches="tight")
        plt.close(fig)


def plot_kurva_pop_iter_per_seed(seed, seed_rows, pops, iters, algos, task_label, out_folder):
    """
    Membuat Grid Subplot berdasarkan Population (5, 10, 15, 20):
    Sumbu X = Iterations (20..100), Sumbu Y = Nilai Metrik, Garis = ACO vs GWO.
    """
    cols = min(2, len(pops))
    rows_grid = math.ceil(len(pops) / cols)

    for kolom, slug, judul, satuan, _ in METRIK:
        fig, axes = plt.subplots(rows_grid, cols, figsize=(6.2 * cols, 4.2 * rows_grid), squeeze=False, sharey=True)

        for idx, p in enumerate(pops):
            r_idx, c_idx = divmod(idx, cols)
            ax = axes[r_idx, c_idx]

            for algo in algos:
                x_vals, y_vals = [], []
                for it in iters:
                    vals = [r[kolom] for r in seed_rows if r["algo"] == algo and r["pop"] == p and r["iter"] == it]
                    if vals:
                        x_vals.append(it)
                        y_vals.append(sum(vals) / len(vals))
                if x_vals:
                    ax.plot(
                        x_vals,
                        y_vals,
                        marker="o",
                        linewidth=2.2,
                        label=algo,
                        color=WARNA_ALGO.get(algo),
                    )
                    # Anotasi titik awal dan akhir agar angka langsung terbaca
                    for xv, yv in zip(x_vals, y_vals):
                        lbl = f"{yv:.1f}" if yv >= 100 else f"{yv:.2f}"
                        ax.annotate(lbl, (xv, yv), textcoords="offset points", xytext=(0, 6), ha="center", fontsize=7.5)

            ax.set_title(f"Population = {p}", fontweight="bold", fontsize=10.5)
            ax.set_xlabel("Iterations")
            if c_idx == 0:
                ax.set_ylabel(f"{judul} ({satuan})")
            ax.set_xticks(iters)
            ax.grid(alpha=0.3, linestyle="--")
            ax.legend(title="Algoritma")

        # Sembunyikan subplot kosong jika jumlah populasi ganjil
        for idx in range(len(pops), rows_grid * cols):
            r_idx, c_idx = divmod(idx, cols)
            axes[r_idx, c_idx].axis("off")

        fig.suptitle(
            f"Pengaruh Iterations & Population terhadap {judul} — Seed {seed} ({task_label})",
            fontsize=12.5,
            fontweight="bold",
            y=1.01,
        )
        plt.tight_layout()
        out_path = os.path.join(out_folder, f"2_kurva_pop_iter_{slug}.png")
        plt.savefig(out_path, dpi=200, bbox_inches="tight")
        plt.close(fig)


def plot_tren_task_per_seed(seed, seed_rows, pops, iters, algos, out_folder):
    """
    Membuat perbandingan skala jumlah task (N_Task = 100..1000) berdampingan ACO vs GWO
    untuk kombinasi (Population, Iterations) yang tersedia pada Seed tersebut.
    """
    tasks = sorted({r["task"] for r in seed_rows})
    if len(tasks) <= 1:
        return

    combos = sorted({(r["pop"], r["iter"]) for r in seed_rows})
    cmap = plt.get_cmap("tab20")

    for kolom, slug, judul, satuan, _ in METRIK:
        n_algos = len(algos)
        fig, axes = plt.subplots(1, n_algos, figsize=(6.8 * n_algos, 5.0), squeeze=False, sharey=True)

        for a_idx, algo in enumerate(algos):
            ax = axes[0, a_idx]
            for c_idx, (p, it) in enumerate(combos):
                sub = [r for r in seed_rows if r["algo"] == algo and r["pop"] == p and r["iter"] == it]
                sub.sort(key=lambda x: x["task"])
                if sub:
                    ax.plot(
                        [x["task"] for x in sub],
                        [x[kolom] for x in sub],
                        marker="o",
                        markersize=4,
                        linewidth=1.6,
                        color=cmap(c_idx % 20),
                        label=f"Pop={p}, Iter={it}",
                    )
            ax.set_title(f"Algoritma {algo}", fontweight="bold", fontsize=11)
            ax.set_xlabel("Jumlah Task (N_Task)")
            if a_idx == 0:
                ax.set_ylabel(f"{judul} ({satuan})")
            ax.set_xticks(tasks)
            ax.tick_params(axis="x", rotation=30)
            ax.grid(alpha=0.3, linestyle="--")

        handles, labels = axes[0, 0].get_legend_handles_labels()
        if handles:
            fig.legend(handles, labels, title="Konfigurasi (Pop, Iter)", loc="center left", bbox_to_anchor=(1.0, 0.5), fontsize=8.5)

        fig.suptitle(
            f"Skalabilitas {judul} terhadap Jumlah Task — Seed {seed}",
            fontsize=12.5,
            fontweight="bold",
            y=1.02,
        )
        out_path = os.path.join(out_folder, f"3_tren_task_{slug}.png")
        plt.savefig(out_path, dpi=200, bbox_inches="tight")
        plt.close(fig)


def plot_ringkasan_lintas_seed(rows, pops, iters, algos, task_label, out_dir):
    """
    Membuat grafik agregasi rata-rata ± standar deviasi lintas semua Seed,
    serta menyimpan file CSV rekap statistik.
    """
    summary_dir = os.path.join(out_dir, "ringkasan_lintas_seed")
    os.makedirs(summary_dir, exist_ok=True)

    # Simpan CSV rekap Mean & StdDev per (Algoritma, Population, Iterations)
    csv_out = os.path.join(BASE, "output", "ringkasan_statistik_parameter.csv")
    with open(csv_out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        header = ["Algoritma", "Population", "Iterations", "NFE (Pop*Iter)", "Jumlah_Seed"]
        for _, slug, _, _, _ in METRIK:
            header.extend([f"{slug}_mean", f"{slug}_std"])
        writer.writerow(header)

        for algo in algos:
            for p in pops:
                for it in iters:
                    # Rata-ratakan per seed terlebih dahulu
                    by_seed = defaultdict(list)
                    for r in rows:
                        if r["algo"] == algo and r["pop"] == p and r["iter"] == it:
                            by_seed[r["seed"]].append(r)
                    if not by_seed:
                        continue
                    row_out = [algo, p, it, p * it, len(by_seed)]
                    for kolom, _, _, _, _ in METRIK:
                        seed_means = [sum(x[kolom] for x in items) / len(items) for items in by_seed.values()]
                        m = float(np.mean(seed_means))
                        s = float(np.std(seed_means, ddof=1)) if len(seed_means) > 1 else 0.0
                        row_out.extend([f"{m:.4f}", f"{s:.4f}"])
                    writer.writerow(row_out)

    # Buat grafik perbandingan Mean ± StdDev per Population terhadap Iterations
    cols = min(2, len(pops))
    rows_grid = math.ceil(len(pops) / cols)

    for kolom, slug, judul, satuan, _ in METRIK:
        fig, axes = plt.subplots(rows_grid, cols, figsize=(6.2 * cols, 4.2 * rows_grid), squeeze=False, sharey=True)
        for idx, p in enumerate(pops):
            r_idx, c_idx = divmod(idx, cols)
            ax = axes[r_idx, c_idx]

            for algo in algos:
                x_vals, y_means, y_stds = [], [], []
                for it in iters:
                    by_seed = defaultdict(list)
                    for r in rows:
                        if r["algo"] == algo and r["pop"] == p and r["iter"] == it:
                            by_seed[r["seed"]].append(r[kolom])
                    if by_seed:
                        seed_means = [sum(v) / len(v) for v in by_seed.values()]
                        x_vals.append(it)
                        y_means.append(float(np.mean(seed_means)))
                        y_stds.append(float(np.std(seed_means, ddof=1)) if len(seed_means) > 1 else 0.0)

                if x_vals:
                    warna = WARNA_ALGO.get(algo)
                    ax.errorbar(
                        x_vals,
                        y_means,
                        yerr=y_stds,
                        marker="o",
                        linewidth=2.2,
                        capsize=4,
                        label=f"{algo} (Mean ± SD)",
                        color=warna,
                    )

            ax.set_title(f"Population = {p}", fontweight="bold", fontsize=10.5)
            ax.set_xlabel("Iterations")
            if c_idx == 0:
                ax.set_ylabel(f"{judul} ({satuan})")
            ax.set_xticks(iters)
            ax.grid(alpha=0.3, linestyle="--")
            ax.legend(title="Algoritma")

        for idx in range(len(pops), rows_grid * cols):
            r_idx, c_idx = divmod(idx, cols)
            axes[r_idx, c_idx].axis("off")

        fig.suptitle(
            f"Stabilitas Lintas Seed (Mean ± StdDev) — {judul} ({task_label})",
            fontsize=12.5,
            fontweight="bold",
            y=1.01,
        )
        plt.tight_layout()
        out_path = os.path.join(summary_dir, f"ringkasan_seed_{slug}.png")
        plt.savefig(out_path, dpi=200, bbox_inches="tight")
        plt.close(fig)

    print(f"Tabel rekap statistik tersimpan di: {csv_out}")


def main():
    args = parse_args()
    if not os.path.exists(args.csv):
        raise SystemExit(f"CSV tidak ditemukan: {args.csv}")

    rows = load_data(args.csv, filter_task=args.task)
    if not rows:
        raise SystemExit("Data kosong atau tidak ada N_Task yang sesuai filter.")

    seeds = sorted({r["seed"] for r in rows})
    pops = sorted({r["pop"] for r in rows})
    iters = sorted({r["iter"] for r in rows})
    algos = sorted({r["algo"] for r in rows})
    task_label = f"N_Task = {args.task}" if args.task is not None else "Rata-rata N_Task 100..1000"

    os.makedirs(args.out, exist_ok=True)

    for seed in seeds:
        seed_rows = [r for r in rows if r["seed"] == seed]
        seed_folder = os.path.join(args.out, f"seed_{seed}")
        os.makedirs(seed_folder, exist_ok=True)

        plot_heatmap_per_seed(seed, seed_rows, pops, iters, algos, task_label, seed_folder)
        plot_kurva_pop_iter_per_seed(seed, seed_rows, pops, iters, algos, task_label, seed_folder)
        plot_tren_task_per_seed(seed, seed_rows, pops, iters, algos, seed_folder)
        print(f"Selesai membuat grafik analisis lengkap untuk Seed {seed} -> {seed_folder}")

    plot_ringkasan_lintas_seed(rows, pops, iters, algos, task_label, args.out)
    print(f"\nSemua grafik analisis berhasil disimpan di: {args.out}")


if __name__ == "__main__":
    main()