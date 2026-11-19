## Algoritma Ant Colony Optimization (ACO)

ACO (`runAco`) digunakan untuk mencari pembagian (*assignment*) setiap task ke salah satu VM dengan tujuan meminimalkan **makespan**, yaitu waktu penyelesaian task paling akhir.

### 1 Representasi solusi

- Setiap semut membangun satu solusi berupa array `assignment`.
- Indeks array menunjukkan task, sedangkan nilainya menunjukkan indeks VM yang dipilih.
- Contoh: `assignment[0] = 2` berarti task pertama dijadwalkan ke VM indeks 2.
- Matriks `pheromone[task][vm]` menyimpan tingkat feromon untuk pasangan task dan VM. Pada awal algoritma, seluruh nilainya diinisialisasi dengan `1`.

### 2 Cara semut memilih VM

Task diproses sesuai urutan dataset. Untuk setiap task, algoritma menghitung perkiraan waktu selesai jika task ditempatkan pada masing-masing VM:

\[
completion = finishTime + \frac{taskLength}{vmMips}
\]

`finishTime` adalah waktu selesai terkecil dari core VM yang sedang dipilih. VM dengan perkiraan waktu selesai lebih kecil dan feromon lebih tinggi akan memperoleh bobot pemilihan lebih besar.

Kode menggunakan:

\[
weight_{task,vm} =
pheromone_{task,vm}
\times
\left(\frac{1}{10^{-9}+completion}\right)^2
\]

Dengan demikian, parameter yang digunakan adalah **alpha = 1** untuk pengaruh feromon dan **beta = 2** untuk pengaruh informasi heuristik (perkiraan waktu selesai). Pemilihan VM dilakukan secara probabilistik menggunakan metode *roulette wheel*, bukan selalu memilih bobot terbesar. Hal ini memberi kesempatan bagi alternatif lain untuk dieksplorasi.

Setelah VM dipilih, waktu selesai core tersebut diperbarui dengan durasi eksekusi task.

### 3 Evaluasi dan pembaruan feromon

Setelah semua semut membangun jadwal, setiap jadwal dievaluasi menggunakan fungsi `evaluate()`. Algoritma menyimpan solusi terbaik pada iterasi berjalan (*iteration best*) dan solusi terbaik sejak awal pencarian (*global best*).

Skala deposit feromon dihitung sebagai:

\[
Q = \frac{\text{total panjang seluruh task}}
         {\text{total kapasitas MIPS seluruh VM}}
\]

Pembaruan feromon dilakukan untuk setiap task:

1. **Evaporasi:** semua feromon dikalikan `0.8`, sehingga terjadi penguapan sebesar 20%.
2. **Deposit solusi terbaik iterasi:** feromon pada VM yang dipilih oleh *iteration best* ditambah `Q / iterationMakespan`.
3. **Deposit solusi terbaik global:** feromon pada VM yang dipilih oleh *global best* ditambah `0.5 × Q / bestMakespan`.
4. **Pembatasan nilai:** feromon dijaga pada rentang `0.01` sampai `10`.

Evaporasi mengurangi dominasi pilihan lama, sedangkan deposit memperkuat kombinasi task-VM yang menghasilkan makespan lebih baik.

### 4 Parameter ACO pada simulasi

Pada pemanggilan dari `Main.java`, ACO menggunakan seed `42`, jumlah semut `12`, dan `50` iterasi. Seed membuat urutan bilangan acak dapat direproduksi untuk konfigurasi yang sama. Implementasi juga menolak jumlah semut atau iterasi kurang dari satu.

---

## Algoritma Grey Wolf Optimizer (GWO)

GWO (`runGwo`) meniru perilaku berburu serigala abu-abu. Dalam implementasi ini, setiap serigala merepresentasikan satu kandidat jadwal task ke VM. Tujuannya sama dengan ACO, yaitu mencari makespan sekecil mungkin.

### 1 Representasi posisi dan solusi

- Populasi terdiri dari sejumlah serigala.
- Setiap serigala memiliki vektor posisi dengan satu elemen untuk setiap task.
- Nilai posisi berupa bilangan pecahan dalam rentang indeks VM.
- Posisi diterjemahkan menjadi indeks VM oleh `decode()`, menggunakan pembulatan ke bawah (`floor`) dan pembatasan agar indeks tetap valid.

Contohnya, posisi task sebesar `1.7` diterjemahkan menjadi VM indeks `1`. Karena itu, GWO bekerja pada posisi numerik kontinu, tetapi solusi penjadwalannya bersifat diskrit (memilih satu VM).

Posisi awal setiap serigala dibangkitkan secara acak pada rentang `0` hingga jumlah VM.

### 2 Pemimpin: alpha, beta, dan delta

Pada setiap iterasi, seluruh serigala diurutkan berdasarkan nilai fitness, yaitu makespan hasil `evaluate()`:

- **Alpha:** serigala dengan makespan terbaik.
- **Beta:** serigala dengan makespan terbaik kedua.
- **Delta:** serigala dengan makespan terbaik ketiga.

Ketiga pemimpin tersebut menjadi panduan bagi pergerakan serigala lainnya. Oleh sebab itu, jumlah populasi GWO minimal tiga.

### 3 Pergerakan posisi serigala

Parameter `a` berkurang secara linear dari `2` menuju `0` selama iterasi:

\[
a = 2\left(1-\frac{iteration}{iterations-1}\right)
\]

Untuk setiap dimensi posisi (setiap task) dan setiap pemimpin, program membangkitkan bilangan acak `r1` dan `r2`, lalu menghitung:

\[
A = 2ar_1-a,\qquad C=2r_2
\]

\[
D = |C \times leaderPosition - currentPosition|
\]

\[
X = leaderPosition - A \times D
\]

Posisi baru serigala merupakan rata-rata hasil perhitungan terhadap tiga pemimpin:

\[
position_{new} = \frac{X_{\alpha}+X_{\beta}+X_{\delta}}{3}
\]

Nilai posisi baru dibatasi ke rentang `0` sampai `jumlahVM - 1e-9`, kemudian didekode menjadi assignment VM dan dievaluasi kembali.

Secara konseptual, nilai `a` yang besar pada awal pencarian memberi ruang eksplorasi lebih luas. Ketika `a` mendekati nol, pergerakan cenderung lebih terarah pada posisi para pemimpin sehingga pencarian berfokus pada eksploitasi solusi yang telah ditemukan.

### 4 Parameter GWO pada simulasi

Pada `Main.java`, GWO menggunakan seed `42`, populasi `12` serigala, dan `50` iterasi. Implementasi mensyaratkan minimal tiga serigala dan minimal satu iterasi. Jika iterasi hanya satu, nilai `a` ditetapkan menjadi nol untuk menghindari pembagian dengan nol.

---

## Perbandingan ACO dan GWO dalam Project

Kedua algoritma menyelesaikan masalah yang sama: menentukan VM untuk setiap task agar makespan yang diprediksi sekecil mungkin. Perbedaannya terletak pada cara membangun dan memperbaiki kandidat jadwal.

| Aspek | ACO | GWO |
|---|---|---|
| Representasi kandidat | Assignment task-ke-VM yang dibangun semut | Vektor posisi numerik yang didekode menjadi assignment |
| Mekanisme pencarian | Pemilihan VM probabilistik berdasarkan feromon dan perkiraan waktu selesai | Perubahan posisi berdasarkan tiga solusi terbaik: alpha, beta, delta |
| Informasi yang memandu pencarian | Feromon dan heuristik waktu selesai | Posisi tiga pemimpin dan parameter `A`, `C`, serta `a` |
| Pembaruan kandidat | Feromon mengalami evaporasi dan deposit | Posisi populasi diperbarui setiap iterasi |
| Pengendalian eksplorasi | Roulette wheel dan evaporasi feromon | Koefisien `a` menurun dari 2 ke 0 |
| Solusi terbaik | Disimpan sebagai *global best* | Disimpan sebagai *bestMakespan* dan *bestAssignment* |
| Fitness | Makespan dari `evaluate()` | Makespan dari `evaluate()` |

**Kesamaan penting:** kedua algoritma menggunakan fungsi evaluasi dan representasi hasil akhir yang sama, yaitu array assignment task ke VM. Keduanya juga menerima seed, ukuran populasi, dan jumlah iterasi dari `Main.java`.

**Perbedaan interpretasi:** ACO memilih VM untuk setiap task secara berurutan ketika semut membangun jadwal. GWO memperbarui vektor posisi seluruh serigala, lalu mengubah nilai posisi tersebut menjadi indeks VM. Karena proses `decode()` menggunakan `floor`, beberapa nilai posisi kontinu dapat dipetakan ke VM yang sama.

Perbandingan hasil ACO dan GWO pada project ini dilakukan dengan konfigurasi populasi `12`, iterasi `50`, dan seed `42`. Kesimpulan algoritma mana yang lebih baik harus didasarkan pada hasil metrik simulasi, bukan hanya karakteristik teoritisnya.

---

## Fungsi Fitness, Parameter, dan Metrik Evaluasi

### 1 Fungsi fitness: makespan

Fungsi `evaluate(int[] assignment)` menghitung prediksi makespan suatu jadwal. Untuk setiap VM, program menyimpan waktu selesai pada masing-masing core. Task diproses menurut urutan array/dataset dan ditempatkan pada core dengan waktu selesai paling kecil (`earliestCore()`).

Perkiraan durasi sebuah task pada VM dihitung dengan:

\[
executionTime = \frac{taskLength}{vmMips}
\]

Waktu selesai core ditambah durasi tersebut. Makespan adalah waktu selesai terbesar yang tercatat pada seluruh core:

\[
Makespan = \max(finishTime)
\]

Karena ACO dan GWO sama-sama meminimalkan makespan, nilai fitness yang lebih kecil dianggap lebih baik.

**Catatan:** fungsi `evaluate()` merupakan model prediksi internal scheduler. CloudSim Plus kemudian menjalankan task dan menghasilkan waktu penyelesaian aktual. Keduanya dapat berbeda karena simulasi aktual memiliki mekanisme penjadwalan dan alokasi sumber daya tersendiri.

### 2 Parameter eksperimen

| Parameter | Nilai | Keterangan |
|---|---:|---|
| `ALGORITHM_SEED` | 42 | Seed bilangan acak algoritma |
| `POPULATION` | 12 | Jumlah semut pada ACO atau serigala pada GWO |
| `ITERATIONS` | 50 | Jumlah iterasi pencarian |
| Jumlah task | 100–1000 | Skenario dataset, bertambah 100 task |
| `COST_PER_MIPS_SECOND` | 0.00001 | Tarif yang digunakan dalam perhitungan biaya |

Seed yang sama membantu menjaga eksperimen dapat direproduksi, tetapi tidak berarti ACO dan GWO menghasilkan proses pencarian yang identik.

### 3 Metrik evaluasi

Selain makespan prediksi, `Main.java` menghitung metrik berdasarkan hasil eksekusi CloudSim Plus:

| Metrik | Cara perhitungan dalam kode | Makna |
|---|---|---|
| **Predicted Makespan** | Nilai `schedule.makespan()` dari `evaluate()` | Estimasi waktu penyelesaian jadwal oleh metaheuristik |
| **Actual Makespan** | Waktu selesai maksimum seluruh Cloudlet | Waktu penyelesaian terlama yang tercatat pada simulasi |
| **Gap (%)** | `abs(actual - prediction) / prediction × 100` | Selisih relatif antara prediksi dan hasil aktual |
| **Throughput** | Jumlah task selesai / actual makespan | Jumlah task selesai per satuan waktu |
| **Utilization (%)** | Rata-rata `busyTime / (jumlahCore × actualMakespan)`, dikali 100 | Tingkat pemakaian kapasitas core VM menurut perhitungan program |
| **Imbalance** | Simpangan baku waktu selesai VM | Menggambarkan seberapa tidak merata waktu selesai antar-VM |
| **Total Cost** | Jumlah `finishTimeVM × MIPS × jumlahCore × tarif` | Estimasi biaya berdasarkan waktu selesai dan kapasitas VM |

Untuk metrik utilization, `busyTime` VM dihitung dengan menjumlahkan `taskLength / vmMips` dari task yang dijalankan VM tersebut. Nilai utilization kemudian dirata-ratakan terhadap seluruh VM, termasuk VM yang tidak menerima task.

Untuk imbalance, program menghitung simpangan baku populasi dari waktu selesai setiap VM. VM yang tidak menerima task diberi waktu selesai `0`. Nilai imbalance yang lebih kecil menunjukkan waktu selesai antar-VM lebih merata menurut definisi ini.

**Perhatian dalam membaca hasil:** makespan, throughput, utilization, imbalance, dan total cost memiliki definisi serta arah interpretasi yang berbeda. Misalnya, makespan dan imbalance umumnya diharapkan lebih kecil, sedangkan throughput umumnya diharapkan lebih besar. Metrik perlu dibandingkan pada jumlah task dan konfigurasi simulasi yang sama.

Hasil perbandingan disimpan ke `output/hasil_metrik_perbandingan.csv` dengan kolom algoritma, jumlah task, prediksi makespan, makespan aktual, gap, throughput, utilization, imbalance, dan total cost.
