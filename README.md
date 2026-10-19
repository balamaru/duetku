# duetku; kelola uangku

**Catat keuangan dengan tenang. Data keuangan tersimpan di perangkat Anda.**

duetku adalah aplikasi Android untuk mencatat pemasukan, pengeluaran, dan memahami kebiasaan keuangan pribadi dalam Rupiah. Pencatatan harian, ringkasan, riwayat, serta laporan PDF lokal dapat digunakan tanpa internet dan tanpa membuat akun.

## Data Anda, di perangkat Anda

**Transaksi, kategori, dan profil lokal disimpan dalam database SQLite di ruang privat aplikasi pada perangkat pengguna.** duetku tidak memiliki server penyimpanan data pengguna, sinkronisasi cloud, ataupun database keuangan jarak jauh.

Dengan penyimpanan lokal, Anda tidak perlu menitipkan seluruh riwayat keuangan kepada layanan cloud duetku. Pendekatan ini mengurangi paparan data melalui server terpusat dan memberi Anda kendali atas kapan informasi diekspor atau dibagikan.

- **Tanpa akun cloud:** tidak perlu mendaftar atau menyerahkan email untuk mencatat keuangan.
- **Fungsi inti offline:** catat dan tinjau keuangan tanpa koneksi ke server.
- **Tanpa iklan dan telemetry pihak ketiga:** tidak ada SDK pelacak penggunaan yang ditambahkan.
- **Backup otomatis dinonaktifkan:** aplikasi dikonfigurasi untuk tidak mengikuti backup sistem Android.
- **Ekspor data:** ekspor CSV/PDF dilakukan melalui pilihan simpan atau bagikan pada perangkat.

Penyimpanan lokal mengurangi risiko kebocoran melalui layanan pusat, tetapi bukan jaminan bebas kebocoran. Keamanan juga bergantung pada perangkat, akses orang lain, dan cara Anda membagikan file. Database belum memiliki lapisan enkripsi tambahan dari aplikasi; perlindungan akses menggunakan ruang privat Android serta PIN/biometrik aplikasi.

## Fitur utama

| Fitur | Kegunaan |
| --- | --- |
| Beranda | Melihat saldo periode, pemasukan, pengeluaran, dan transaksi terbaru. |
| Pencatatan transaksi | Menambahkan atau mengubah nominal, kategori, tanggal, dan catatan. |
| Riwayat dan filter | Menelusuri transaksi berdasarkan periode dan kriteria pencarian. |
| Kategori | Mengelola kategori pemasukan dan pengeluaran. |
| Analitik | Memahami komposisi pengeluaran dan ringkasan keuangan. |
| Ekspor PDF dan CSV | Menyimpan laporan analitik visual atau data tabular. |
| PIN dan biometrik | Membatasi akses aplikasi, dengan penguncian otomatis dan recovery key. |
| Profil dan tema | Mengatur nama lokal, warna profil, serta tampilan terang atau gelap. |
| Narasi AI opsional | Menambahkan penjelasan laporan menggunakan penyedia LLM pilihan Anda. |

Nominal uang disimpan sebagai bilangan bulat Rupiah. Ringkasan dan laporan memakai perhitungan dari core lokal. PDF merupakan laporan visual statis, bukan dashboard interaktif di dalam pembaca PDF.

## AI opsional: Anda menentukan kapan data keluar

**Anda tidak perlu mengaktifkan AI untuk menggunakan duetku atau membuat laporan PDF.** Analisis lokal tetap tersedia.

Jika menginginkan narasi tambahan, buka **Kelola → Konfigurasi LLM** dan isi:

| Field | Isi |
| --- | --- |
| `LLM_PROVIDER` | `gemini`, `openai`, atau `claude` |
| `LLM_API_KEY` | API key milik Anda dari penyedia tersebut |
| `LLM_MODEL` | ID model yang tersedia untuk akun penyedia Anda |

Setelah konfigurasi tersimpan, AI dapat digunakan saat ekspor PDF. Menyimpan konfigurasi tidak langsung mengunggah riwayat. Pada layar ekspor tersedia pratinjau agregat yang akan dikirim dan pilihan untuk memakai analisis lokal saja.

- Penggunaan AI mengirim **agregat keuangan, nama kategori, periode, dan pola** kepada penyedia pilihan melalui HTTPS. Agregat dan nama kategori tetap dapat bersifat sensitif.
- Nama profil, catatan transaksi, UUID, dan rincian transaksi individual tidak disertakan dalam payload analisis.
- API key disimpan terenkripsi dengan Android Keystore dan dipakai untuk autentikasi ke penyedia terkait.
- Koneksi internet perangkat harus tersedia. Aplikasi tidak menyalakan Wi-Fi atau data seluler secara otomatis.
- Biaya dan penanganan data oleh penyedia mengikuti layanan yang Anda gunakan. Narasi AI dapat keliru; angka laporan tetap dihitung secara lokal.
- Jika permintaan AI gagal, laporan menggunakan analisis lokal. Menghapus konfigurasi menonaktifkan penggunaan AI.

Jika Anda ingin menghindari pengiriman data ke penyedia AI, gunakan duetku tanpa konfigurasi LLM atau pilih analisis lokal saat ekspor.

## Menyimpan dan menghapus riwayat

duetku mempertahankan riwayat dalam jendela **12 bulan kalender bergulir + 3 hari**. Data yang melewati batas dibersihkan saat aplikasi dibuka, kembali aktif, atau tanggal berganti ketika aplikasi aktif.

Anda juga dapat menghapus satu transaksi, transaksi pada rentang tanggal tertentu, atau seluruh riwayat. Penghapusan bersifat permanen setelah konfirmasi. Menghapus seluruh riwayat tidak menghapus kategori.

File ekspor di luar ruang aplikasi tidak ikut terhapus oleh retensi. Jika Anda membagikannya melalui email, penyimpanan cloud, atau aplikasi lain, salinan tersebut mengikuti penanganan layanan tujuan.

**Uninstall, hapus data aplikasi, atau kehilangan perangkat dapat menyebabkan kehilangan riwayat.** Belum tersedia fitur impor/restore. Ekspor PDF/CSV berguna sebagai arsip, tetapi bukan cadangan yang bisa dipulihkan ke aplikasi. Recovery key digunakan untuk memulihkan akses PIN, bukan mengembalikan database yang hilang.

## Mulai menggunakan

1. Pasang APK duetku pada perangkat Android.
2. Buat PIN enam digit dan simpan recovery key di tempat aman. Tombol salin tersedia pada layar recovery key.
3. Atur profil lokal bila diperlukan, lalu ketuk **+** untuk mencatat transaksi pertama.
4. Tinjau **Beranda**, **Riwayat**, dan **Analitik** untuk memahami keuangan Anda.
5. Ekspor laporan dari **Kelola** atau **Analitik**. Konfigurasi AI bersifat opsional.

APK saat ini menargetkan **Android 13/API 33 ke atas, arm64-v8a**. Untuk pembaruan, pasang APK dengan identitas paket dan kunci penandatanganan yang sama tanpa menghapus instalasi sebelumnya.

## Status pengembangan

Versi saat ini: **2.1.3-preview**. APK berukuran sekitar **3,327 MiB**, menggunakan kunci uji, dan belum merupakan paket bertanda tangan produksi. Build debug/release, lint, signature, alignment, serta pemeriksaan permission dan backup telah dijalankan. Pemeriksaan visual logo, pembaruan instalasi, dan pengujian runtime menyeluruh di perangkat masih diperlukan sebelum rilis produksi.

## Untuk pengembang

Antarmuka memakai **Kotlin + Android Views/XML**, penyimpanan memakai **SQLite privat**, dan perhitungan domain memakai **Go melalui binding Android**. PDF dirender dengan Android `PdfDocument`; integrasi LLM menggunakan API platform tanpa SDK provider tambahan.

| Direktori | Isi |
| --- | --- |
| `android/` | Aplikasi native Android dan adapter penyimpanan. |
| `core-go/` | Validasi, perhitungan, retensi, dan dataset laporan. |
| `tests/` | Pengujian pendukung. |
| `scripts/` | Toolchain, build, dan verifikasi APK. |
| `docs/` | Spesifikasi, desain, identitas brand, dan catatan implementasi. |

Pada lingkungan Windows yang toolchain-nya sudah disiapkan:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-test-apk.ps1
```

Gunakan `-SkipBridge` hanya jika binding Go sudah tersedia dan tidak berubah.
