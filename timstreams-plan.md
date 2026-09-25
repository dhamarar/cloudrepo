# Rencana: TimStreams Provider

## Pendekatan
Membangun ekstensi CloudStream 3 untuk **TimStreams (timst.cfd)** menggunakan REST API resmi (`/api/live-upcoming` dan `/api/watch/{url}`) untuk katalog pertandingan live olahraga, memfilter dan membagi ke dalam 4 kategori utama yang diminta (**Motorsport**, **Mixed Martial Arts**, **American Football**, dan **Baseball**), serta de-obfuscation player stream JavaScript untuk mengekstrak direct HLS master/media playlist (`.m3u8`).

## Cakupan
- **In**:
  - Filter kategori khusus sesuai permintaan:
    - `Motorsport` (Genre ID: 2 / keyword: Motorsport)
    - `Mixed Martial Arts` (Genre ID: 3 / keyword: Mixed Martial Arts, MMA, UFC)
    - `American Football` (Genre ID: 8 / keyword: American Football, NFL)
    - `Baseball` (Genre ID: 9 / keyword: Baseball, MLB)
  - Halaman Beranda (`getMainPage`): Menampilkan baris/kategori untuk 4 kategori tersebut serta ringkasan Semua Pertandingan Terpilih (`all`).
  - Indikator Waktu: Format waktu kick-off lokal 24 jam (`HH:mm`), badge status `LIVE` atau hitung mundur countdown (`Starts in 2h 15m`).
  - Pencarian (`search`): Pencarian pertandingan berdasarkan nama tim/laga dalam lingkup kategori yang didukung.
  - Detail Pertandingan (`load`): Menampilkan judul, poster logo tim/acara, jadwal kick-off, sinopsis/info acara, dan daftar stream sebagai episode (misal: "Floracing", "NFL Game Pass on DAZN", "MLB.TV Feed").
  - Ekstraksi Video Stream (`loadLinks` & Extractor):
    - Resolver kustom untuk player embed `exmxbxe.cfd` (dan mirror sejenis).
    - Membongkar skrip ter-obfuscate algoritma bitwise XOR + offset: `((code ^ key1) - key2 + 256) % 256`.
    - Mengekstrak `SIGNED_URL` (`.m3u8`) dan menghasilkan stream link menggunakan `M3u8Helper.generateM3u8` dan fallback `ExtractorLink`.
    - Fallback ke `loadExtractor` untuk embed umum pihak ketiga jika ada.
- **Out**:
  - Kategori di luar yang diminta (Soccer, Basketball, Tennis, Darts, Cricket, Cycling, Rugby, Live Shows).
  - Fitur login/VIP berbayar.

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery & Reverse-Engineering] Inspeksi API `https://timst.cfd/api/live-upcoming`, `/api/watch/{url}`, dan algoritma dekripsi stream player embed `exmxbxe.cfd` (berhasil 14/14 stream).
- [x] 2. [Scaffold] Buat modul folder `TimStreams` dengan `build.gradle.kts` dan `AndroidManifest.xml` di dalam `cloudrepo`.
- [x] 3. [Plugin] Buat kelas `TimStreamsPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 4. [MainAPI] Implementasikan `TimStreams.kt` mewarisi `MainAPI()` dengan `TvType.Live`.
- [x] 5. [Catalog] Implementasikan filtering 4 kategori (`Motorsport`, `Mixed Martial Arts`, `American Football`, `Baseball`), kalkulasi countdown, format waktu 24 jam, dan `getMainPage`.
- [x] 6. [Search] Implementasikan `search(query)` dengan pencarian judul laga dan filter kategori.
- [x] 7. [Load] Implementasikan `load(url)` yang memanggil `/api/watch/{url}` atau event cache untuk mengambil detail laga dan daftar stream.
- [x] 8. [Extractor] Implementasikan dekripsi stream ter-enkripsi di `loadLinks` (atau kustom extractor `TimStreamsExtractor`) untuk mengekstrak direct `.m3u8` dengan header referer.
- [x] 9. [Gradle Build] Jalankan Gradle make `.\gradlew.bat TimStreams:make` di dalam `cloudrepo`.
- [x] 10. [Verifikasi] Konfirmasi file plugin `TimStreams.cs3` berhasil diproduksi tanpa error kompilasi.
