# Rencana: Indoav Provider

## Pendekatan
Membangun ekstensi CloudStream 3 untuk **IndoAV (indoav.com)** dengan tiga bagian yang diminta: **Latest** (`/?filter=terbaru`), **Trending** (`/trending`), dan **Pencarian** (`/cari?kata-kunci=keyword`), ditambah beberapa filter dan genre bawaan situs. Katalog diambil dari HTML yang dirender server (`article.video-card`), sedangkan Trending memakai JSON `GET /trending/feed`.

Bagian tersulit bukan katalognya, melainkan pemutaran. Situs sengaja mempersulit pengambilan tautan video:

1. URL `.m3u8` tidak pernah muncul di HTML. Ia hanya keluar dari `POST /video/load/<token>/`, dan tokennya harus dirakit sendiri di sisi klien.
2. Token memakai RC4 dengan kunci yang diturunkan dari bundle player, lalu dibalik dan di-base64 dua tahap.
3. Nilai `video=` pada body harus di-encode **dua kali** (`encodeURI("video=" + encodeURIComponent(d))`).
4. Segmen HLS dikirim dengan nama `imgN.jpeg` — sebenarnya berkas MPEG-TS yang dibungkus header JPEG (`FF D8 .. FF D9`) lalu dienkripsi AES-CBC.

Karena poin 4 tidak bisa ditangani ExoPlayer secara langsung, ekstensi ini menjalankan **HTTP server lokal** (`IndoavProxy`) di `127.0.0.1` yang menulis ulang playlist dan mendekripsi segmen saat diminta.

## Cakupan
- **In**:
  - Beranda (`getMainPage`): Trending (Sekarang / Hari Ini / Minggu Ini / Bulan Ini), Terbaru, Banyak Dilihat, Banyak Disukai, Banyak Dikomentari, Durasi Panjang, dan 7 genre populer.
  - Paginasi daftar: `?filter={f}` -> `/halaman/{n}?filter={f}` dan `/genre/{slug}` -> `/genre/{slug}/halaman/{n}`.
  - Pencarian berhalaman (`search(query, page)`): `/cari?kata-kunci=` dan `/cari/halaman/{n}?kata-kunci=`.
  - Detail (`load`): judul, poster, sinopsis, durasi (`meta[itemprop=duration]`), plus rekomendasi dari feed trending.
  - Pemutaran (`loadLinks`): rakit token -> `POST /video/load/<token>/` -> dekripsi balasan -> URL `.m3u8` -> proxy lokal -> `ExtractorLink` bertipe `M3U8`.
  - `IndoavCrypto`: port lengkap RC4, perakit token, dekripsi balasan, dan pembuka bungkus JPEG + AES-128-CBC untuk segmen.
  - `IndoavProxy`: server `ServerSocket` lokal yang menulis ulang playlist dan menyajikan segmen `.ts` hasil dekripsi (mendukung `Range` / HTTP 206).
- **Out**:
  - Unduhan berkas (CloudStream akan memakai ulang jalur proxy yang sama, tapi tidak ada UI unduhan khusus).
  - Fitur akun, komentar, suka, dan halaman `/d/{filecode}` (tautan unduhan internal situs).
  - Bypass iklan/popup (`displayPop`) — tidak dipakai karena tidak relevan untuk pemutaran langsung.

## Catatan Teknis
- **AES hanya 128-bit.** Implementasi situs (`uint8ArrayToUint32Array_`) mengalokasikan `new Uint32Array(4)` dan membaca tepat 4 word = 16 byte, sehingga dari 32 byte yang dihasilkan seed hanya 16 byte pertama yang dipakai.
- **Rangkaian CBC + padding.** Situs memakai ciphertext blok sebelumnya sebagai IV blok berikutnya (`y = n.slice(-16)`) lalu membuang padding berdasarkan byte terakhir — sama dengan `AES/CBC/NoPadding` + buang PKCS7.
- **RC4 kunci kosong.** `rc4(seed, "")` di JavaScript menghasilkan `NaN` pada indeks S-box; hasilnya tidak bisa direproduksi dengan RC4 biasa, jadi kuncinya dipatok langsung sebagai konstanta (`TOKEN_KEY`) dan sudah diverifikasi byte-per-byte.
- **Label stream wajib.** Bagian `:.s:` pada payload token harus berisi `aria-label` tombol stream (yaitu `EM`). Mengisinya dengan `undefined` membuat server menolak token.
- **Penanda token.** Untuk label `EM` penandanya `data-filecode`; untuk label lain penandanya slug pada URL.
- **Token sekali pakai.** Satu token hanya berlaku untuk satu permintaan `/video/load/`.
- **`data-play-token` di HTML bukan token stream.** Elemen itu dipakai fungsi anti-adblock `displayPop`, bukan untuk meminta video.
- **Cleartext ke loopback aman.** Manifest CloudStream memakai `android:usesCleartextTraffic="true"` tanpa `networkSecurityConfig`, jadi ExoPlayer boleh menarik `http://127.0.0.1:<port>`.

## Daftar Tindakan (Action Items)
- [x] 1. [Discovery] Petakan struktur situs: filter `?filter=`, paginasi `/halaman/{n}`, genre `/genre/{slug}`, pencarian `/cari?kata-kunci=`, markup `article.video-card`, dan JSON `/trending/feed`.
- [x] 2. [Reverse-Engineering] Bongkar bundle player (`dist/js/video.*.js` + `encryption.*.js`) dan player embed (`dist/js/file.*.js`): alur token, header `X-REQUESTED-WITH: official-app`, endpoint `/video/load/`, serta skema enkripsi segmen.
- [x] 3. [Verifikasi] Uji rantai penuh di luar Android (Node/Java): token -> `/video/load/` -> `.m3u8` -> unduh segmen -> dekripsi AES -> cek sync MPEG-TS `0x47`.
- [x] 4. [Verifikasi] Jalankan tiruan `IndoavProxy` di Node, lalu putar playlist lokalnya dengan `ffprobe` + `ffmpeg` untuk membuktikan desain proxy benar-benar bisa dikonsumsi klien HLS sungguhan.
- [x] 5. [Scaffold] Buat modul `Indoav` dengan `build.gradle.kts` dan `AndroidManifest.xml`.
- [x] 6. [Crypto] Implementasikan `IndoavCrypto.kt` (RC4, perakit token, dekripsi balasan, pembuka bungkus JPEG + AES-128-CBC).
- [x] 7. [Proxy] Implementasikan `IndoavProxy.kt` sebagai server HTTP lokal untuk playlist dan segmen.
- [x] 8. [MainAPI] Implementasikan `Indoav.kt` (`getMainPage`, `search`, `load`, `loadLinks`).
- [x] 9. [Plugin] Buat `IndoavPlugin.kt` dengan anotasi `@CloudstreamPlugin`.
- [x] 10. [Gradle Build] Jalankan `.\gradlew.bat Indoav:make`.
- [x] 11. [Verifikasi] Konfirmasi plugin `Indoav.cs3` berhasil terkompilasi tanpa error.

## Hasil Verifikasi Terakhir
Tiruan proxy dijalankan di Node dengan aturan tulis-ulang yang identik dengan
`IndoavProxy.kt`, lalu playlist lokalnya diuji klien HLS sungguhan:

```
ffprobe -> format_name=hls, duration=450.566661, 2 stream
           video: h264 406x720   audio: aac
ffmpeg  -> frame=1199, time=00:00:20.00, tanpa error (-v error senyap)
```

Durasi 450,57 detik cocok dengan "7m 30s" yang tertulis di kartu daftar video,
jadi segmen yang didekripsi memang utuh dari awal sampai akhir.
