# Rencana: Pemersatufun (pemersatufun.com)

## Pendekatan
Membangun ekstensi CloudStream 3 untuk situs streaming dewasa lokal `pemersatufun.com` dengan Jsoup scraping untuk katalog/pencarian, lalu extractor kustom untuk pemutar videonya:
1. **Katalog & Pencarian (Jsoup)**: kartu video sudah dirender server-side sebagai `<article data-video-code data-video-url data-video-thumb>`, jadi tidak butuh JavaScript engine.
2. **Detail**: metadata diambil dari blok JSON-LD `VideoObject` (`name`, `description`, `thumbnailUrl`, `duration` ISO-8601, `uploadDate`, `interactionStatistic`) dengan fallback ke DOM (`h1`, `og:image`, `<iframe>`).
3. **Extractor luluvdo.com**: URL HLS diselipkan di konfigurasi JW Player yang diobfuscate packer **Dean Edwards** (`eval(function(p,a,c,k,e,d){...})`), sehingga perlu di-unpack dulu untuk membaca field `file` (master `.m3u8`).

### Temuan penting saat riset
- **CDN video (`*.tnmr.org`) memfilter User-Agent.** Path `/hls2/...` dijawab `403` untuk UA desktop Chrome, Firefox, Safari, curl, okhttp, ExoPlayerLib, maupun Dalvik. Hanya UA browser mobile (mis. Android Chrome Mobile) yang dilayani `200`. Karena itu `ExtractorLink` wajib mengirim `User-Agent` mobile + `Referer` `https://luluvdo.com/`.
- **Stream bersih.** Master playlist menunjuk satu varian 720x1280, segmennya MPEG-TS (`video/MP2T`, sync byte `0x47` di offset 0/188/376), tanpa `EXT-X-KEY`, `EXT-X-BYTERANGE`, maupun `EXT-X-MAP`. URL segmen sudah absolut dan membawa token yang sama, jadi **tidak perlu proxy lokal** — cukup kirim header yang benar.
- **Token m3u8** (`?t=...&s=...&e=28800&f=...`) berumur 8 jam dan diterbitkan ulang setiap kali halaman embed dimuat, jadi selalu diambil fresh.
- **Paginasi arsip vs cursor.** `/arsip-malam/{n}` deterministik, sedangkan `/cari-malam/{slug}/page/{n}?cursor=...` memakai cursor opaque sehingga halaman ke-N ditempuh dengan mengikuti `link[rel=next]`.
- **`/find?q=` = `/cari-malam/{slug}`.** Form pencarian situs 301 ke `/cari-malam/{slug}` dan memotong query menjadi 2 kata pertama; pencarian mengikuti jalur redirect ini agar normalisasinya sama, dengan fallback slug manual.
- **Host cluster.** `window.__clusterHosts` memuat 5 host (pemersatufun.com, bokeppemersatu.net, imperial.pemersatufun.com, legacy.pemersatu.store, pemersatu.store) yang menyajikan konten identik, dipakai sebagai cadangan domain.

## Cakupan
- **In**:
  - Modul folder `Pemersatufun` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
  - Plugin `PemersatufunPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
  - Halaman Utama (`mainPage`):
    - **Terbaru**: `/` (halaman 1) dan `/arsip-malam/%d` (halaman ≥2).
    - **Hijab, ABG, Binal, Talent, 18yo, OMETV, Jilbab**: `cari-malam/{tag}` dengan paginasi cursor.
  - Pencarian (`search`): `/find?q={keyword}` (redirect ke `/cari-malam/{slug}`), fallback `/cari-malam/{slug}`.
  - Halaman Detail (`load`): judul, poster, deskripsi + jumlah tayangan, tahun, durasi, dan rekomendasi dari grid "UP NEXT" (`TvType.NSFW`).
  - Ekstraksi Tautan Video (`loadLinks`): unpacker packer Dean Edwards untuk `luluvdo.com`/`lulustream.com` menghasilkan `ExtractorLinkType.M3U8`, plus fallback `loadExtractor(...)` untuk host lain.
  - Failover domain ke host cluster saat host aktif gagal.
  - Kompilasi Gradle: `./gradlew Pemersatufun:make` menghasilkan `Pemersatufun.cs3`.
- **Out**:
  - Login/akun, unduhan berbayar, dan komentar (`community`) yang tidak menambah pengalaman menonton.
  - Proxy HLS lokal: tidak diperlukan karena segmen tidak terenkripsi dan URL-nya absolut.

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery] Petakan struktur beranda, arsip, pencarian, dan halaman detail via curl/Jsoup.
- [x] 2. [Discovery] Bongkar konfigurasi JW Player (packer Dean Edwards) dan dapatkan master `.m3u8` (`luluvdo.com/e/{code}`).
- [x] 3. [Discovery] Identifikasi filter User-Agent CDN `tnmr.org` dan tentukan UA yang diterima.
- [x] 4. [Verifikasi] Uji rantai penuh master → varian → segmen di Node: 309 segmen, MPEG-TS valid, tanpa enkripsi.
- [x] 5. [Verifikasi] Putar lewat `ffprobe` + `ffmpeg` (H.264 720x1280 + AAC, 20 detik terdekode tanpa error).
- [x] 6. [Scaffold] Buat modul folder `Pemersatufun` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 7. [Plugin] Buat kelas `PemersatufunPlugin.kt` dengan registrasi `Pemersatufun()`.
- [x] 8. [MainAPI] Implementasikan `Pemersatufun.kt` (`mainUrl`, `name`, `supportedTypes`, `mainPage`).
- [x] 9. [Catalog] Implementasikan `getMainPage` dan helper `toSearchResult()`.
- [x] 10. [Search] Implementasikan `search(query)` dan `search(query, page)` dengan penelusuran cursor.
- [x] 11. [Load] Implementasikan `load(url)` berbasis JSON-LD `VideoObject` + fallback DOM.
- [x] 12. [Extractor] Implementasikan `unpackPacker()` dan `getStream()` di `PemersatufunExtractor.kt`.
- [x] 13. [Gradle] Jalankan `./gradlew Pemersatufun:make` di direktori repo.
- [x] 14. [Verifikasi] Konfirmasi `Pemersatufun.cs3` berisi `manifest.json` + `classes.dex` yang benar.
