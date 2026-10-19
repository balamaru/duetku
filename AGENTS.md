# AGENTS.md — Aturan AI Coding Agent untuk Kelola Uangku

## Keputusan pemilik untuk LLM (19 Oktober 2026)

Pemilik secara eksplisit mengizinkan INTERNET untuk Gemini/OpenAI/Claude: setelah konfigurasi LLM disimpan, ekspor PDF memakai AI otomatis. Satu APK tetap menyediakan seluruh fungsi inti dan PDF lokal tanpa koneksi. Ini menggantikan larangan jaringan/AI di bawah khusus koneksi HTTPS ke provider pilihan; bukan izin backend, sinkronisasi, telemetry, atau akun cloud. API key terenkripsi Keystore; data keluar hanya agregat laporan saat ekspor, tanpa profil/catatan/rincian transaksi. Lihat docs/ANALYTICS_REPORT.md. Retensi dan hard delete tetap.

## Keputusan pemilik untuk v2 (18 Oktober 2026)

Permintaan eksplisit terbaru mengaktifkan `docs/PRD_VERSI_2.md` untuk v2.0 dan UUID sebagai ID transaksi. Ketentuan di bawah tentang larangan autentikasi/profil/PDF digantikan **khusus** untuk PIN/biometrik lokal, profil satu pemilik, dan PDF analitik offline. Bukan akun cloud. v2.1/AI, INTERNET, import/restore, dan enkripsi database tambahan belum diaktifkan. Baca juga `docs/V2_IMPLEMENTATION.md`; retensi dan hard delete tetap berlaku.

Dokumen ini mengikat setiap AI agent yang membaca, mengubah, menguji, atau merilis repository Kelola Uangku. **Baca `docs/PRD.md` dan `docs/SYSTEM_DESIGN.md` sebelum mengerjakan fitur.** Bila PRD belum disalin ke repo, gunakan PRD yang berlaku di `docs/PRD.md` yang diberikan pemilik proyek dan minta lokasinya.

## 1. Misi

Bangun **APK Android pribadi, ringan, 100% offline**, untuk pencatatan keuangan IDR pada satu perangkat. Semua transaksi dan kategori berada di **SQLite privat lokal**. Retensi maksimum 12 bulan kalender bergulir + 3 hari, dengan hapus otomatis dan manual. Prioritaskan kesederhanaan, privasi, kebenaran angka, dan ukuran APK.

## 2. Prioritas instruksi

1. Permintaan eksplisit pemilik proyek dan keputusan arsitektur yang disetujui.
2. PRD yang berlaku di `docs/PRD.md` Mobile Offline.
3. `docs/SYSTEM_DESIGN.md` dan ADR yang telah disetujui.
4. Dokumen ini.
5. Konvensi kode yang telah ada di repository.

Jika ada konflik, **jelaskan konflik dan minta keputusan**, jangan diam-diam mengganti kebutuhan.

## 3. Larangan mutlak

- **JANGAN** membuat login, register, logout, user profile, password, JWT, OAuth, role, `user_id`, atau multi-user.
- **JANGAN** menambahkan backend, server, REST/GraphQL API, cloud sync, Firebase, Supabase, PostgreSQL, remote database, atau penyimpanan data ke internet.
- **JANGAN** menambahkan permission `INTERNET`, telemetry, analytics pihak ketiga, iklan, SDK cloud, atau background network.
- **JANGAN** menggunakan WebView, React Native, Flutter, atau framework berat tanpa persetujuan tertulis pemilik proyek.
- **JANGAN** menambahkan background service untuk purge; cukup startup, foreground, dan pergantian hari saat aplikasi aktif.
- **JANGAN** menyimpan saldo hasil hitung sebagai sumber kebenaran terpisah atau nominal uang sebagai float/double.
- **JANGAN** menggunakan soft delete untuk transaksi atau menyimpan transaksi melewati batas 12 bulan kalender + 3 hari.
- **JANGAN** menambahkan fitur impor/restore, PDF MVP, lampiran struk, integrasi bank, atau fitur di luar PRD tanpa persetujuan.
- **JANGAN** mengklaim penghapusan forensik sempurna atau ukuran APK pasti < 20 MB.

## 4. Stack yang diizinkan

- UI: Android native **Kotlin + Views/XML** minimal.
- Core bisnis: **Go** dengan binding Android **hanya setelah spike ukuran dan startup**. Jangan memaksa seluruh UI ditulis dalam Go.
- Database: SQLite embedded di sandbox privat Android, query terparameterisasi dan transaksi atomik.
- Ekspor MVP: CSV lokal via Android Storage Access Framework/Share Sheet; PDF hanya fase lanjutan.
- Build: Gradle Android, APK release single-ABI sesuai perangkat, resource/code shrinking jika sesuai.
- Fallback Kotlin-only membutuhkan keputusan pemilik proyek setelah bukti benchmark.

Hindari dependency baru bila fitur bisa dibuat dengan Android SDK, Go stdlib, atau SQLite. Setiap dependency baru harus dijustifikasi: fungsi, ukuran tambahan, lisensi, keamanan, dan alternatif lebih ringan.

## 5. Struktur dan pemisahan tanggung jawab

Ikuti struktur pada `docs/SYSTEM_DESIGN.md`: `android/`, `core-go/`, `tests/`, `scripts/`, `docs/`. UI tidak berisi SQL; adapter data tidak berisi aturan bisnis yang tersebar; core Go tidak mengakses jaringan. Gunakan interface sederhana dan sedikit lapisan. **Satu sumber kebenaran** untuk perhitungan saldo, tanggal retensi, dan validasi transaksi.

## 6. Aturan data dan tanggal

- Simpan uang sebagai integer 64-bit rupiah bulat (`amount_minor`), positif dan maksimal 13 digit; jangan float.
- `type` hanya `income` atau `expense`; kategori harus cocok dan aktif saat membuat transaksi.
- `transaction_date` menggunakan tanggal lokal `YYYY-MM-DD`, tervalidasi sebagai tanggal sah.
- Batas retensi: `start = today.minusMonths(12).plusDays(1).minusDays(3)` dan `end = today`, keduanya inklusif. Pada **2026-10-16**, batas awal **2025-10-14**.
- Hapus record dengan `transaction_date < start` saat startup sebelum dashboard dibaca, saat foreground, dan saat tanggal lokal berubah selama aktif.
- Create/edit harus menolak tanggal di luar jendela. Query, ringkasan, dan ekspor tetap membatasi jendela walaupun purge belum berjalan.
- Semua delete transaksi adalah hard delete dalam SQLite transaction. Jangan menghapus kategori yang masih direferensikan; nonaktifkan.
- Hapus seluruh riwayat **tidak** menghapus kategori. File ekspor di luar sandbox bukan bagian dari retensi otomatis.
- Gunakan `Clock` injectable dalam tes. Uji akhir bulan, leap year, dan perubahan tanggal perangkat.

## 7. Privasi dan keamanan

- Simpan database di direktori privat aplikasi; aktifkan FK setiap koneksi dan pertimbangkan `secure_delete`.
- Set `android:allowBackup="false"` serta aturan data extraction yang sesuai; periksa manifest akhir untuk memastikan tidak ada `INTERNET` permission.
- Jangan log nominal, catatan, isi CSV, atau data finansial pribadi; gunakan log minimal tanpa data sensitif.
- Jangan simpan file ekspor permanen di cache. Berbagi file harus tindakan eksplisit pengguna melalui URI grant yang tepat.
- Jangan mengklaim ekspor manual tetap lokal bila pengguna memilih aplikasi berbagi cloud.
- Jangan menjalankan I/O database atau ekspor pada UI thread.

## 8. Standar perubahan kode

Sebelum coding: baca file relevan, jelaskan perubahan kecil yang akan dilakukan, identifikasi dampak terhadap retensi/privasi/ukuran APK, dan tulis acceptance criteria. Buat perubahan terkecil yang menyelesaikan tugas; jangan refactor tidak terkait. Nama variabel dan API internal konsisten; UI/pesan validasi bahasa Indonesia. Gunakan SQL parametrik, error handling eksplisit, dan migrasi database non-destruktif untuk update aplikasi. Jangan membuat dependency atau file konfigurasi yang tidak dipakai.

Setelah coding: format/lint, jalankan tes relevan, verifikasi offline, periksa permission/dependency, dan laporkan file yang berubah, hasil tes, serta risiko tersisa. **Jangan menyatakan tes lulus jika tidak dijalankan.**

## 9. Gerbang kualitas wajib

Sebelum menyatakan fitur selesai, pastikan:

- [ ] Fitur bekerja dalam airplane mode dan tanpa server.
- [ ] Tidak ada auth, akun, jaringan, atau database remote yang ditambahkan.
- [ ] Tidak ada data transaksi di luar jendela retensi setelah purge selesai.
- [ ] Dashboard, riwayat, dan ekspor menghasilkan total yang konsisten.
- [ ] Penghapusan satu/rentang/semua meminta konfirmasi dan tidak dapat di-undo.
- [ ] SQLite transaction, foreign keys, query parametrik, dan validasi input benar.
- [ ] CSV aman dari formula injection dan escaping rusak.
- [ ] Tidak ada I/O berat pada main thread.
- [ ] Unit test domain (target cakupan ≥ 70%) dan tes batas tanggal dijalankan.
- [ ] APK release size dan cold start **diukur** saat ada perubahan build/dependency; target < 20 MB bersifat eksploratif.
- [ ] Manifest final tidak memuat permission `INTERNET` dan backup finansial dinonaktifkan.

Jika suatu tes belum dapat dijalankan, tandai **BELUM DIUJI** dan jelaskan cara menjalankannya.

## 10. Urutan pengerjaan

1. **Spike:** UI Android minimal, SQLite, Go bridge, benchmark APK dan startup; dokumentasikan hasil dan minta keputusan stack.
2. **Fondasi:** skema SQLite, kategori bawaan, create/edit/delete transaksi dan validasi.
3. **Alur inti:** dashboard, filter, riwayat, kategori.
4. **Kontrol data:** purge 12 bulan kalender + 3 hari, delete satu/rentang/semua, test edge cases.
5. **Rilis:** CSV, pengujian offline, optimasi ukuran, signing dan update APK.
6. **Opsional setelah persetujuan:** PDF lokal.

Jangan melewati spike dan mengunci Go binding sebagai keputusan final tanpa data ukuran nyata.

## 11. Template laporan setiap tugas

```text
Tujuan:
Perubahan:
File diubah:
Tes dijalankan dan hasil:
Pengaruh pada ukuran APK / dependency:
Pemeriksaan privasi dan offline:
Risiko / hal belum diuji:
Keputusan yang dibutuhkan (jika ada):
```

## 12. Kondisi yang mengharuskan berhenti dan bertanya

Minta persetujuan sebelum: mengganti Go dengan Kotlin-only; menambah dependency besar; mengubah definisi 12 bulan kalender + 3 hari; mengubah kebijakan hard delete; menambah permission atau jaringan; menambah backup/import/restore; mengubah cakupan MVP; atau mengambil keputusan platform Android minimum tanpa informasi perangkat yang memadai.

**Definisi selesai:** aplikasi tetap offline, data tetap privat di HP, transaksi hanya dalam 12 bulan kalender + 3 hari, penghapusan dapat diverifikasi, hasil perhitungan benar, dan ukuran APK diukur bukan diasumsikan.

<!-- session-memory:start -->
## Memori session (session-memory)
Proyek ini memakai `.agent-memory/` sebagai memori lintas session & lintas agent.
1. **Awal session, SEBELUM mengerjakan prompt apa pun**: jika konteks belum berisi
   `=== SESSION MEMORY`, jalankan `python .agent-memory/bin/memory.py start --agent <nama-agent>`. Cocokkan prompt
   dengan requirements & session terakhir di output-nya. Jangan membaca ulang seluruh kode:
   baca hanya file yang relevan dengan task dan file yang ditandai berubah.
2. **Selama session**: `python .agent-memory/bin/memory.py log <task|done|decision|todo|blocker|fix|note> "teks singkat"`
   setiap ada langkah berarti; `python .agent-memory/bin/memory.py req set R<n> <x|~|-> "bukti"` saat requirement maju;
   `python .agent-memory/bin/memory.py files add <path> "fungsi"` untuk file penting baru.
3. Perbarui bagian manual `.agent-memory/MEMORY.md` bila status/keputusan/langkah berikutnya berubah.
4. **Akhir session** (task tuntas / user pamit): `python .agent-memory/bin/memory.py end --summary "ringkasan 2–4 kalimat"`.
   Bila lebih dari satu agent aktif, tambahkan `--agent <nama-agent>` yang sama di `log`/`end`.
5. Jangan pernah menulis rahasia (API key, token, password) atau data pribadi ke memori.
Detail protokol: `.agent-memory/PROTOCOL.md` (gunakan `python3` bila `python` tidak ada).
<!-- session-memory:end -->
