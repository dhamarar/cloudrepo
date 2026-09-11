# Rencana: AVTub (avtub.cx)

## Pendekatan
Membangun ekstensi CloudStream 3 untuk situs streaming dewasa lokal `avtub.cx` menggunakan Jsoup scraping untuk katalog/pencarian dan extractor kustom untuk pemutar video:
1. **Morencius (Vidhide Clone)**: Menggunakan de-obfuscator Dean Edwards `getAndUnpack()` untuk mengekstrak master `.m3u8` dari script player.
2. **YStream / Byse Player**: Menggunakan solver PoW SHA-256 leading zero bits bawaan dan dekripsi AES-256-GCM untuk mengekstrak direct HLS playlist master `.m3u8`.

## Cakupan
- **In**:
  - Modul folder `CloudX-V2-main/Avtub` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
  - Plugin `AvtubPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
  - Halaman Utama (`mainPage`):
    - **Terbaru / Bokep Indo**: `category/bokep-indo/page/%d/?filter=latest`
    - **Jilbab**: `category/bokep-jilbab/page/%d/?filter=latest`
  - Pencarian (`search`): `?s=%s` dengan paginasi `page/%d/?s=%s`.
  - Halaman Detail (`load`): Judul, Poster, Deskripsi, Durasi, Tags, dan URL pemutar video (`Movie` / `TvType.NSFW`).
  - Ekstraksi Tautan Video (`loadLinks`):
    - Resolver `morencius.com` (Dean Edwards unpacked m3u8)
    - Resolver `ystream.id` (PoW solver + AES-256-GCM decryptor)
    - Fallback ke `loadExtractor(...)` untuk provider video generik lainnya.
  - Kompilasi Gradle: `./gradlew Avtub:make` menghasilkan `Avtub.cs3`.
- **Out**:
  - Fitur VIP download berbayar (streaming gratis sudah 1080p).

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery] Uji coba manual scraping & ekstraksi video link (Morencius 200 OK m3u8, YStream PoW + AES-GCM 200 OK m3u8).
- [x] 2. [Scaffold] Buat modul folder `CloudX-V2-main/Avtub` lengkap dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 3. [Plugin] Buat kelas `AvtubPlugin.kt` dengan registrasi `Avtub()`.
- [x] 4. [MainAPI] Implementasikan `Avtub.kt` (`mainUrl`, `name`, `supportedTypes`, `mainPage`).
- [x] 5. [Catalog] Implementasikan `getMainPage` dan helper `toSearchResult()`.
- [x] 6. [Search] Implementasikan fungsi `search(query)`.
- [x] 7. [Load] Implementasikan `load(url)` untuk parsing detail konten.
- [x] 8. [Extractor] Implementasikan logika ekstraksi `morencius.com` dan solver `ystream.id` di `Extractors.kt` atau `Avtub.kt`.
- [x] 9. [Gradle] Jalankan `./gradlew Avtub:make` di direktori `CloudX-V2-main`.
- [x] 10. [Verifikasi] Konfirmasi file `Avtub.cs3` berhasil dikompilasi tanpa error.
