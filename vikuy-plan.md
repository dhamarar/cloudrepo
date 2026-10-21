# Rencana: Vikuy Provider (vikuy.click)

## Pendekatan
Membangun ekstensi CloudStream 3 untuk situs streaming video **Vikuy (vikuy.click)** dengan Jsoup scraping untuk katalog utama, halaman watch, pencarian, dan pemutar video direct streaming:
1. **Katalog Utama (Jsoup)**: Halaman penjelajahan utama diambil dari `vikuy.click/explore.php?tab=latest&safe=0` sebagai beranda / video terbaru sesuai permintaan pengguna. Kartu video dirender server-side sebagai `<article class="video-card">` lengkap dengan tautan video, thumbnail, durasi, dan nama pembuat.
2. **Paginasi**: Menggunakan format query URL standar `explore.php?tab=latest&safe=0&page=%d` (tersedia lebih dari 1.700 halaman katalog).
3. **Pencarian (Search)**:
   - Dukungan pencarian profil kreator / uploader: `/user.php?u={username}`.
   - Fallback query `explore.php?tab=latest&q={query}` (jika perbaikan query SQL backend situs aktif).
   - Pencarian lokal judul video dari feed terbaru agar pengguna tetap dapat menemukan video tanpa galat HTTP 500 dari backend situs.
4. **Detail (Load)**: Mengambil judul dari `.video-title-big` / `og:title`, poster dari atribut `poster` pemutar video, informasi uploader (`.uploader-name`, `.uploader-handle`), deskripsi, serta 5 video terkait (`a.related-card`).
5. **Ekstraksi Tautan Video (LoadLinks)**:
   - Sumber video berada di tag `<video id="mainVid"><source src="..." type="video/mp4"></video>`.
   - URL `https://vikuy.click/serve_video.php?t=...` mengembalikan 302 redirect ke file MP4 CDN langsung (`https://vid.vikuy.click/videos/...mp4`).
   - Ekstensi mendeteksi redirect dan mengirim tautan MP4 langsung ke CloudStream dengan `ExtractorLinkType.VIDEO` (mendukung HTTP Range / instant seek dan playback tanpa proxy lokal).
   - Fallback ke `download.php?v={id}` jika `<source>` video tidak dimuat di DOM utama.

## Cakupan
- **In**:
  - Modul `Vikuy` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
  - Plugin `VikuyPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
  - Beranda (`mainPage`):
    - **Terbaru**: `explore.php?tab=latest&safe=0` (Safe Search OFF).
    - **Terbaru (Safe Search)**: `explore.php?tab=latest` (Safe Search ON).
  - Paginasi beranda: `explore.php?tab=latest&safe=0&page=%d`.
  - Pencarian (`search`): kreator `/user.php?u=...`, pencarian kata kunci dengan penanganan galat aman.
  - Halaman Detail (`load`): judul, poster, sinopsis/uploader, dan rekomendasi video terkait (`TvType.NSFW`).
  - Ekstraksi Tautan Video (`loadLinks`): resolusi redirect `serve_video.php` ke file direct MP4 CDN `vid.vikuy.click`.
  - Kompilasi Gradle: `./gradlew.bat Vikuy:make` menghasilkan `Vikuy.cs3`.
- **Out**:
  - Fitur login/registrasi akun situs.
  - Proxy lokal (tidak diperlukan karena video berupa MP4 standar dengan header `Accept-Ranges: bytes`).

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery] Petakan struktur situs: `explore.php`, sistem tab (`tab=latest`, `tab=premium`), toggle `safe=0`, kartu `<article class="video-card">`, dan paginasi `page=%d`.
- [x] 2. [Discovery] Analisis mekanisme pemutar video di `watch?v=...`, endpoint `serve_video.php`, dan redirect ke CDN MP4 `vid.vikuy.click`.
- [x] 3. [Verifikasi] Uji rantai MP4 (Range request 0-100 byte, verifikasi container ftyp / moov atom).
- [x] 4. [Scaffold] Buat modul folder `Vikuy` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 5. [Plugin] Buat `VikuyPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 6. [MainAPI] Implementasikan `Vikuy.kt` (`getMainPage`, `search`, `load`, `loadLinks`).
- [x] 7. [Gradle Build] Jalankan kompilasi `Vikuy:make`.
- [x] 8. [Verifikasi] Konfirmasi plugin `Vikuy.cs3` berhasil terkompilasi tanpa galat.
