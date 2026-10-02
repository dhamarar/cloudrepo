# Rencana: StreamCorner Provider

## Pendekatan
Membangun ekstensi CloudStream 3 untuk **StreamCorner (streamcorner.st)** dengan mereverse-engineer protokol RPC Cloudflare Workers terenkripsi (Salsa20-12 stream cipher + SHA-256 key derivation & integrity guard tags + Base64Url) untuk mengekstrak katalog event olahraga langsung, saluran 24/7, dan event live dari 10 backend provider (`admin`, `unl`, `alpha`, `beta`, `001`, `003`, `extra003`, `extra004`, `channels`, `slingtv_channels`). Mendukung 50+ kategori spesifik yang diminta pengguna (ALL, LIVE, TODAY, 24/7 STREAMS, AFCON QUALIFIERS, AMERICAN FOOTBALL, ATP 1000/500, BASEBALL, BASKETBALL, FOOTBALL, F1, UFC, dll.), serta mengekstrak direct HLS/DASH dan berbagai embed host pihak ketiga.

## Cakupan
- **In**:
  - Dukungan lengkap untuk seluruh 58 kategori yang diminta user:
    - ALL, LIVE, TODAY, 24/7 STREAMS
    - Sepak Bola: FOOTBALL, SOCCER, LALIGA, MLS, CANADIAN PREMIER LEAGUE, UCL WOMENS, UEFA, UEFA NATIONS, UEFA NATIONS LEAGUE, PRE SHOW, POST SHOW, AFCON QUALIFIERS, CONCACAF NATIONS LEAGUE, INTERNATIONAL FRIENDLY, EFL LEAGUE TWO, NORTHERN SUPER LEAGUE, NWSL
    - Basket: BASKETBALL, NBA, WNBA, AUSTRALIAN NBL
    - American Football & Rugby: AMERICAN FOOTBALL, NFL, NCAAF, NCAA DIVISION 1 FOOTBALL, CFL, RUGBY, AUSTRALIAN FOOTBALL, AUSTRALIAN NATIONAL RUGBY LEAGUE
    - Motorsport: MOTORSPORT, F1, FORMULA 1, MOTOGP, MOTOCROSS, NASCAR, BSB, WRC
    - Beladiri: FIGHTING, UFC, WRESTLING
    - Raket & Lainnya: TENNIS, ATP 1000, ATP 500, WTA 1000, BASEBALL, MLB, CRICKET, CYCLING, DARTS, ICE HOCKEY, NHL, SHOOTING, SNOOKER, OTHERS
  - Katalog Beranda (`getMainPage`): Menampilkan baris/kategori dengan filter cerdas, indikator LIVE / waktu WIB 24 jam.
  - Pencarian (`search`): Pencarian event berdasarkan judul tim, kompetisi, atau nama laga.
  - Detail Konten (`load`): Menampilkan detail pertandingan, waktu mulai, sinopsis, dan daftar server stream sebagai 1 stream terpadu (`newMovieLoadResponse`).
  - Ekstraksi Stream Video (`loadLinks` & Extractor):
    - Direct Salsa20 RPC query untuk event detail streams (`/corner?p={providerId}&id={streamId}`).
    - Ekstraksi semua server secara paralel via `amap` sebagai multiple sources di player Cloudstream.
    - Resolusi multi-track/kualitas via `M3u8Helper.generateM3u8` dan direct DASH `.mpd`.
    - Resolusi player embed pihak ketiga (`embed.st`, `rockystream.st`, `pandecocogaming.sbs`, dll.).
    - Fallback ke `loadExtractor` dan `WebViewResolver` jika diperlukan.
- **Out**:
  - Fitur chat interaktif bawaan situs (`streamcorner-chat.pages.dev`).
  - Akun login/VIP berbayar.

## Daftar Tindakan (Action Items)
- [x] 1. [Reverse Engineering] Bongkar arsitektur enkripsi `provider-data.worker-D-vC_ByW.js` (Salsa20-12, SHA-256, Base64Url) dan verifikasi sukses dekripsi event & detail stream secara mandiri.
- [x] 2. [Scaffold] Buat modul folder `StreamCorner` dengan `build.gradle.kts` dan `AndroidManifest.xml` di dalam `cloudrepo`.
- [x] 3. [Plugin] Buat kelas `StreamCornerPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 4. [Cipher & Network Helper] Buat kelas helper murni Kotlin `StreamCornerCipher.kt` untuk Salsa20, SHA-256, dan Base64Url tanpa dependensi pihak ketiga.
- [x] 5. [MainAPI] Implementasikan `StreamCorner.kt` dengan `TvType.Live`, definisi seluruh kategori di `mainPage`, dan in-memory cache event.
- [x] 6. [Catalog & Filter] Implementasikan logika filtering 58 kategori di `getMainPage`, formatting waktu 24 jam lokal, dan badge LIVE.
- [x] 7. [Search] Implementasikan fungsi `search(query)`.
- [x] 8. [Load] Implementasikan `load(url)` yang mengambil detail laga dan server stream melalui Salsa20 API.
- [x] 9. [Links & Extractor] Implementasikan `loadLinks` dan `StreamCornerExtractor.kt` untuk menangani direct stream, `embed.st`, dan embed player lainnya.
- [x] 10. [Gradle Build] Jalankan kompilasi Gradle `.\gradlew.bat StreamCorner:make`.
- [x] 11. [Verifikasi] Konfirmasi file biner `StreamCorner.cs3` berhasil diproduksi tanpa error.
