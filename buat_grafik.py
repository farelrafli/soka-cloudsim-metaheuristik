"""
Membaca output/hasil_metrik_perbandingan.csv (dibuat oleh Main.java) dan membuat 5 grafik PNG
di output/grafik/: satu per metrik, satu garis per algoritma (ACO, GWO), sumbu X = jumlah task.

Hanya butuh matplotlib (tanpa pandas):
    sudo apt install -y python3-matplotlib      # atau: pip install matplotlib
Jalankan dari folder project:
    python3 buat_grafik.py
"""
import csv
import os
from collections import OrderedDict

import matplotlib
matplotlib.use("Agg")  # tanpa jendela, aman di WSL
import matplotlib.pyplot as plt

BASE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(BASE, "output", "hasil_metrik_perbandingan.csv")
OUT_DIR = os.path.join(BASE, "output", "grafik")

WARNA = {"ACO": "#21295C", "GWO": "#E07A5F"}

# (kolom CSV, judul, label sumbu Y, nama file)
METRIK = [
    ("Makespan_Aktual",    "Makespan vs Jumlah Task",             "Makespan (detik)",                "1_makespan.png"),
    ("Throughput",         "Throughput vs Jumlah Task",           "Throughput (task/detik)",         "2_throughput.png"),
    ("Utilization_Persen", "Resource Utilization vs Jumlah Task", "Utilisasi (%)",                   "3_utilization.png"),
    ("Imbalance",          "Degree of Imbalance vs Jumlah Task",  "Stdev waktu selesai VM (detik)",  "4_imbalance.png"),
    ("Total_Cost",         "Total Cost vs Jumlah Task",           "Total Cost (unit biaya)",         "5_total_cost.png"),
]


def baca_csv(path):
    """Mengembalikan {algoritma: [baris, ...]} terurut menurut N_Task."""
    data = OrderedDict()
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            data.setdefault(row["Algoritma"], []).append(row)
    for rows in data.values():
        rows.sort(key=lambda r: int(r["N_Task"]))
    return data


def main():
    if not os.path.exists(CSV_PATH):
        raise SystemExit(f"CSV tidak ditemukan: {CSV_PATH}\nJalankan dulu: mvn -q compile exec:java")
    data = baca_csv(CSV_PATH)
    os.makedirs(OUT_DIR, exist_ok=True)

    for kolom, judul, label_y, filename in METRIK:
        plt.figure(figsize=(8, 5))
        for algo, rows in data.items():
            x = [int(r["N_Task"]) for r in rows]
            y = [float(r[kolom]) for r in rows]
            plt.plot(x, y, marker="o", linewidth=2, label=algo, color=WARNA.get(algo))
        plt.title(judul)
        plt.xlabel("Jumlah Task")
        plt.ylabel(label_y)
        plt.legend(title="Algoritma")
        plt.grid(alpha=0.3)
        plt.tight_layout()
        out_path = os.path.join(OUT_DIR, filename)
        plt.savefig(out_path, dpi=200)
        plt.close()
        print("Tersimpan:", out_path)


if __name__ == "__main__":
    main()
