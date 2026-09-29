# Rencana: VirtuaVixen (virtuavixen.com)

## Pendekatan
Membangun ekstensi CloudStream 3 untuk situs streaming dewasa AI `virtuavixen.com` di folder `cloudrepo`:
1. **Katalog & Beranda (Jsoup)**:
   - Scraping kartu video berbasis DOM class `.btp_post_card`.
   - Kategori yang diminta:
     - Newest: `all-videos/page/%d/` (halaman 1: `all-videos/`)
     - Best Week: `best-ai-porn-videos-week/page/%d/`
     - Best Month: `best-ai-porn-videos-month/page/%d/`
     - Best All Time: `best-ai-porn-videos-of-all-time/page/%d/`
2. **Pencarian (Jsoup)**:
   - Menggunakan endpoint `/?s={query}` atau `/page/{page}/?s={query}`.
3. **Detail Konten (`load`)**:
   - Judul dari `h1`, poster dari OpenGraph / Schema.org `thumbnailUrl` / `data-item` splash.
   - Sinopsis / plot dari schema description / deskripsi konten.
   - Tag dari `a[href*="/tag/"]` dan model/pemeran dari `a[href*="/model/"]`.
4. **Ekstraksi Video (`loadLinks`) & Proxy Kunci AES-128**:
   - Video player menggunakan FV Player Pro (Flowplayer) terproteksi anti-rip.
   - Melakukan otentikasi via `POST wp-admin/admin-ajax.php` dengan action `fv_player_performance` dan summary `streamLoaderUrl`.
   - Menjalankan `VirtuaVixenProxy` lokal pada `127.0.0.1:$port` untuk menyajikan playlist yang telah disuntik kunci AES-128 16-byte asli. Hal ini mencegah ExoPlayer menerima dummy key (error `Cannot find sync byte. Most likely not a Transport Stream`) saat token satu kali pakai upstream hangus.
   - Segmen video (.ts) tetap diunduh langsung dari CDN DigitalOcean tanpa bottleneck proxy.

## Cakupan
- **In**:
  - Modul subfolder `cloudrepo/VirtuaVixen` dengan `build.gradle.kts` dan `AndroidManifest.xml`.
  - Plugin `VirtuaVixenPlugin.kt` dianotasi `@CloudstreamPlugin`.
  - MainAPI `VirtuaVixen.kt` (`mainUrl`, `name`, `supportedTypes = setOf(TvType.NSFW)`, `mainPage`).
  - Halaman Beranda (`getMainPage`) dengan 4 section: Newest, Best Week, Best Month, Best All Time.
  - Pencarian (`search`).
  - Detail (`load`) dengan sinopsis, poster, tag, dan model.
  - `VirtuaVixenProxy.kt` untuk melayani kunci HLS AES-128 statis lokal.
  - Build `./gradlew VirtuaVixen:make` menghasilkan `VirtuaVixen.cs3`.
- **Out**:
  - Fitur login VIP/unduhan berbayar khusus akun terdaftar.

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery] Uji akses situs `virtuavixen.com` & respons server (200 OK, tanpa Cloudflare block).
- [x] 2. [Discovery] Petakan struktur kartu video (`.btp_post_card`), thumbnail, judul, dan badge resolusi/durasi.
- [x] 3. [Discovery] Analisis player video FV Player Pro (`data-item` JSON) dan verifikasi pemutaran HLS via `stream-loader.php` (AES-128 key & segment 200 OK).
- [x] 4. [Discovery] Uji coba pagination untuk 4 kategori (Newest, Best Week, Best Month, Best All Time) dan pencarian `/?s=query`.
- [x] 5. [Scaffold] Buat modul folder `cloudrepo/VirtuaVixen` dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 6. [Plugin] Buat kelas `VirtuaVixenPlugin.kt` mendaftarkan `VirtuaVixen()`.
- [x] 7. [MainAPI] Implementasikan `VirtuaVixen.kt` (`mainUrl`, `name`, `supportedTypes`, `mainPage`, `getMainPage`, `search`, `load`, `loadLinks`).
- [x] 8. [Fix Anti-Rip] Identifikasi kegagalan sync byte di logcat dan terapkan `fv_player_performance` + `VirtuaVixenProxy` lokal.
- [x] 9. [Build] Jalankan `.\gradlew.bat VirtuaVixen:make` di folder `cloudrepo`.
- [x] 10. [Verifikasi] Verifikasi file `VirtuaVixen.cs3` berhasil ter-generate di `cloudrepo/VirtuaVixen/build/VirtuaVixen.cs3`.
