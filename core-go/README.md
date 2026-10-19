# Core lokal Kelola Uangku

Library domain Go (stdlib saja), tanpa server atau penyimpanan produksi. Kontrak: [INTERNAL_API.md](../docs/INTERNAL_API.md).

```powershell
cd core-go
go test -cover ./...
go vet ./...
go build ./...
```

Gunakan Go 1.22 atau lebih baru. `go build` memverifikasi package library, tidak menghasilkan APK/executable. Adapter Repository produksi harus memakai SQLite privat Android; test double memori hanya digunakan oleh unit test.

Helper Windows: `scripts/check-core.ps1` dari root proyek jika kebijakan PowerShell mengizinkan script. Bila script diblokir, jalankan tiga perintah Go di atas langsung. Toolchain lokal sesi ini tersedia di `.tools/compiler/go/bin/`; folder toolchain dan cache diabaikan dari version control.

Verifikasi 2026-10-17, Go 1.27.2 Windows/amd64: unit test lulus dengan coverage 91,4%, `go vet` dan `go build` lulus. GOPROXY=off dan GOTOOLCHAIN=local digunakan. Ini bukan benchmark APK atau uji SQLite/perangkat; helper PowerShell sendiri belum dieksekusi karena execution policy, tetapi perintah Go-nya sudah dijalankan langsung.

Injeksi Clock lokal dan Repository dengan transaksi atomic melalui `New`. Jalankan SeedCategories saat DB pertama dibuat. Jalankan PurgeExpiredHistory sebelum membaca dashboard pada startup, saat foreground, dan pergantian hari aktif. Android bertanggung jawab atas worker thread dan URI ekspor. Go binding belum final sampai spike ukuran dan startup disetujui.
