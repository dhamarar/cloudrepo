# Rencana: WatchPorn

## Pendekatan
Memindahkan modul `WatchPorn` dari `Cs-GizliKeyif-master` ke `cloudrepo`. Menambahkan seluruh 63 kategori dari `https://watchporn.to/categories/` ke halaman utama, mempertahankan/memperbarui daftar studios/sites, dan menyediakan extension settings UI (dialog Android TV & Phone) untuk mengatur ON/OFF kategori dan studio yang ditampilkan di halaman utama.

## Cakupan
- **In**:
  - Salin dan adaptasi struktur modul `WatchPorn` ke `cloudrepo/WatchPorn`.
  - Integrasi 63 kategori dari `watchporn.to/categories/`.
  - Integrasi daftar studio/sites terkemuka dari `watchporn.to/sites/` (MissaX, PureTaboo, ManyVids, OnlyFans, dll.).
  - Dialog pengaturan ekstensi (`WatchPornSettingsDialog` & `WatchPornSettings`) dengan switch/checkbox ON/OFF untuk Kategori dan Studio, tombol Select All / Deselect All, Reset, dan Save & Apply.
  - Dynamic `mainPage` via getter yang membaca konfigurasi aktif dari CloudStream storage (`getKey`/`setKey`).
  - Pencegahan duplikasi paginasi dengan deteksi `hasNext` yang akurat.
- **Out**: Fitur download di luar CloudStream bawaan, modifikasi situs eksternal.

## Action Items
- [ ] 1. Buat struktur folder `cloudrepo/WatchPorn` dan file modul (`build.gradle.kts`, `AndroidManifest.xml`).
- [ ] 2. Buat `WatchPornSettings.kt` untuk menyimpan data default kategori, studios, fungsi `getKey`/`setKey`, serta dialog UI interaktif.
- [ ] 3. Buat `WatchPornPlugin.kt` dengan anotasi `@CloudstreamPlugin` dan handler `openSettings`.
- [ ] 4. Buat `WatchPorn.kt` yang mengimplementasikan `MainAPI`, dynamic `mainPage`, scraping video player via WebView, dan pagination aman.
- [ ] 5. Uji kompilasi dengan `./gradlew WatchPorn:make` dan pastikan file `.cs3` berhasil diproduksi.
- [ ] 6. Commit dan push ke remote git `cloudrepo`.
