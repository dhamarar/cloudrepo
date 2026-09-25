# Rencana: PPV (ppv.st) Provider

## Pendekatan
Membangun ekstensi CloudStream 3 untuk **PPV (ppv.st)** menggunakan REST API resmi (`https://api.ppv.st/api/streams` dan `https://api.ppv.st/api/streams/{id}`) untuk katalog pertandingan multi-olahraga live streaming (American Football, Football/Soccer, Motorsports, Basketball, Baseball, Combat Sports, Ice Hockey, Wrestling, Rugby, Cricket, 24/7 Streams), serta ekstraksi stream embed (`embedindia.st` / `embed.st`) menggunakan `WebViewResolver` dan native HLS link generation.

## Cakupan
- **In**:
  - Halaman Beranda (`getMainPage`):
    - Tab `Semua Pertandingan` (`all`): Pengelompokan baris per kategori olahraga menggunakan `HomePageList`.
    - Tab `Sedang Live` (`live`): Menampilkan semua pertandingan yang sedang berlangsung saat ini.
    - Tab per Kategori Olahraga: Football (Soccer), Motorsports, American Football (NFL), Basketball (NBA/WNBA), Baseball (MLB), Combat Sports (UFC/BJJ), Ice Hockey (NHL), Wrestling (WWE/AEW), Rugby, Cricket, 24/7 Channels.
  - Indikator Waktu: Format waktu kick-off lokal 24 jam (`HH:mm`), badge status `LIVE`, `24/7 LIVE`, atau hitung mundur countdown (`Starts in 2h 15m`).
  - Pencarian (`search`): Pencarian pertandingan berdasarkan nama tim/laga atau tag turnamen.
  - Detail Pertandingan (`load`): Menampilkan judul, poster logo tim/acara, jadwal kick-off, sinopsis/info acara, jumlah penonton, serta daftar stream utama dan substreams (multibahasa / multi-source) sebagai episode.
  - Ekstraksi Video Stream (`loadLinks` & Extractor):
    - Resolver kustom untuk player embed `embedindia.st` dan mirror `embed.st`.
    - Dukungan `WebViewResolver` untuk bypass perlindungan player JS / WebAssembly dan menangkap stream `.m3u8` / `.mpd`.
    - Fallback ke `loadExtractor` untuk embed umum pihak ketiga jika ada.
- **Out**:
  - Fitur login/VIP berbayar jika ada.

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery & Reverse-Engineering] Inspeksi API `https://api.ppv.st/api/streams`, `/api/streams/{id}`, format timestamps Unix, dan mekanisme embed `embedindia.st`.
- [x] 2. [Scaffold] Buat modul folder `Ppv` dengan `build.gradle.kts` dan `AndroidManifest.xml` di dalam `cloudrepo`.
- [x] 3. [Plugin] Buat kelas `PpvPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 4. [MainAPI] Implementasikan `Ppv.kt` mewarisi `MainAPI()` dengan `TvType.Live`.
- [x] 5. [Catalog] Implementasikan pengelompokan kategori olahraga, tab live, filter kategori, format waktu 24 jam, dan `getMainPage`.
- [x] 6. [Search] Implementasikan `search(query)` dengan pencarian judul laga, nama tim, dan tag olahraga.
- [x] 7. [Load] Implementasikan `load(url)` yang memanggil `/api/streams/{id}` atau cache untuk mengambil detail laga, server utama, dan substreams.
- [x] 8. [Extractor] Implementasikan `PpvExtractor.kt` dengan `WebViewResolver` untuk player `embedindia.st`.
- [x] 9. [Gradle Build] Jalankan Gradle make `.\gradlew.bat Ppv:make` di dalam `cloudrepo`.
- [x] 10. [Verifikasi] Konfirmasi file plugin `Ppv.cs3` berhasil diproduksi tanpa error kompilasi.
