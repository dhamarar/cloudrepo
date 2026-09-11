# Rencana: Streamed Provider

## Pendekatan
Membangun ekstensi CloudStream 3 untuk **Streamed (streamed.pk)** menggunakan API resmi `https://streamed.pk/api/` untuk katalog pertandingan sepak bola (`football`) dan balap (`motor-sports`), pengelompokan tanggal di halaman beranda, format waktu 24 jam (`HH:mm`), countdown waktu sebelum kick-off, dan ekstraksi link streaming live (`embed.st` / `golf` / external player) menggunakan `WebViewResolver` dan native HTTP parsing.

## Cakupan
- **In**:
  - Filter kategori ketat: Hanya `football` dan `motor-sports`.
  - Halaman Beranda (`getMainPage`): Dikelompokkan per tanggal (`Hari Ini / Today`, `Besok / Tomorrow`, `dd MMM yyyy`) menggunakan `HomePageList`.
  - Waktu Pertandingan: Format 24 jam (`HH:mm`) berdasarkan waktu kick-off.
  - Countdown: Indikator sisa waktu pertandingan jika belum dimulai (`[Starts in 2h 15m]`), dan badge `[LIVE]` jika pertandingan sedang berlangsung.
  - Pencarian (`search`): Pencarian pertandingan sepak bola & balap motor dengan filter query.
  - Detail Pertandingan (`load`): Menampilkan poster/badge tim, sinopsis, kick-off time, dan daftar stream server per bahasa & resolusi sebagai episode/opsi stream.
  - Ekstraksi Stream (`loadLinks` & `ExtractorApi`):
    - Dukungan resolver `golf` (HTTP parsing langsung dari player `maestrohd1`).
    - Dukungan resolver `embed.st` (menggunakan `WebViewResolver` untuk bypass JS & WebAssembly stream encryption).
    - Dukungan direct/nested iframe extraction menggunakan `loadExtractor`.
- **Out**:
  - Kategori olahraga selain sepak bola dan balap motor (misal: basket, baseball, kriket, MMA dikecualikan sesuai instruksi).

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery & Reverse-Engineering] Verifikasi endpoint API `streamed.pk` (`/api/matches/football`, `/api/matches/motor-sports`, `/api/stream/{source}/{id}`) dan mekanisme player `embed.st`.
- [x] 2. [Scaffold] Buat modul folder `Streamed` dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 3. [Plugin] Buat kelas `StreamedPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 4. [MainAPI] Implementasikan `Streamed.kt` mewarisi `MainAPI()` dengan `TvType.Live`.
- [x] 5. [Catalog] Implementasikan pengelompokan tanggal di `getMainPage` (`HomePageList` per tanggal) dan helper countdown + format waktu 24 jam.
- [x] 6. [Search] Implementasikan `search(query)` dengan pencarian judul dan nama tim sepak bola/balap.
- [x] 7. [Load] Implementasikan `load(url)` yang memanggil `/api/stream/{source}/{id}` untuk semua source pertandingan.
- [x] 8. [Extractor] Implementasikan `EmbedStExtractor.kt` dengan `WebViewResolver` dan handler server `golf`.
- [x] 9. [Gradle Build] Jalankan `.\gradlew.bat Streamed:make`.
- [x] 10. [Verifikasi] Konfirmasi plugin `Streamed.cs3` berhasil terkompilasi tanpa error.
