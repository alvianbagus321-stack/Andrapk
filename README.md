# Andra Control (JARVIS-HP)

Aplikasi Android AI companion dengan **server MCP bawaan** (OAuth + HTTP di port 8765) sehingga bisa disambungkan ke **ChatGPT connector** lewat tunnel, plus **Termux agent engine** untuk kontrol perangkat secara otonom (tap, swipe, screenshot, shell, dll).

---

## 🔨 Cara Build APK

Ada 2 cara — pilih salah satu:

- **Cara A — Otomatis lewat script `build.sh`** (disarankan, tanpa Android Studio)
- **Cara B — Android Studio** (cara klasik)

> Script `build.sh` akan menyiapkan **semua** yang dibutuhkan: JDK, Android SDK, Gradle, license, sampai keystore. Kamu tinggal jalankan satu perintah.

### Persyaratan

| Kebutuhan | Keterangan |
|---|---|
| OS | Linux (Ubuntu/Debian/Fedora/Arch/openSUSE), macOS, atau **WSL** di Windows |
| RAM | ≥ 4 GB (build memakai `-Xmx4g`) |
| Internet | Wajib saat build pertama (unduh SDK + dependensi, ±1–1,5 GB) |
| HP (opsional) | Untuk `./build.sh install`: USB debugging aktif |

> ⚠️ Android SDK **tidak bisa** jalan langsung di Termux (bukan glibc). Untuk build di HP, pakai `proot-distro` (lihat bagian [Build di HP via Termux](#-build-di-hp-via-termux)).

---

## Cara A — Build Otomatis dengan `build.sh` ⚡

### 1. Tarik kode terbaru

```bash
cd lokasi/folder/Andrapk
git pull
```

### 2. Jalankan script

```bash
chmod +x build.sh     # sekali saja
./build.sh
```

Pertama kali dijalankan, script **otomatis**:

1. Memasang **JDK 21** (via `apt`/`dnf`/`pacman`/`zypper`/`brew`)
2. Mengunduh **Android SDK** ke `~/Android/Sdk` + menerima lisensi
3. Mengunduh **Gradle 9.3.1** (versi mengikuti `gradle-wrapper.properties`)
4. Membuat `local.properties`, `.env`, `debug.keystore`
5. Build **APK debug**

> ⏱️ Build pertama butuh waktu **10–30 menit** (banyak unduhan). Build kedua dst. jauh lebih cepat karena semua ter-cache.

### 3. Ambil APK-nya

Kalau sukses, APK ada di:

```
app/build/outputs/apk/debug/app-debug.apk
```

Pasang ke HP — pilih salah satu:

- **Lewat kabel USB** (termudah): sambungkan HP (USB debugging aktif) lalu
  ```bash
  ./build.sh install
  ```
- **Manual**: salin `app-debug.apk` ke HP (WhatsApp/kabel/Drive) → buka → izinkan "instal dari sumber tidak dikenal" → pasang.
  > Kalau muncul **"App not installed"**, uninstall APK lama dulu lalu coba lagi.

### Semua perintah `build.sh`

| Perintah | Fungsi |
|---|---|
| `./build.sh` | Build APK debug (default) |
| `./build.sh install` | Build + pasang langsung ke HP via adb |
| `./build.sh release` | Build APK release (keystore dibuat otomatis) |
| `./build.sh setup` | Hanya siapkan JDK + SDK + Gradle (tanpa build) |
| `./build.sh info` | Cek status lingkungan build |
| `./build.sh clean` | Bersihkan hasil build |

### Build release

```bash
./build.sh release
```

Saat pertama kali build release, script otomatis membuat:

- `my-upload-key.jks` — keystore tanda tangan
- `release-signing.env` — simpanan kredensial keystore

> 🔴 **SIMPAN kedua file itu baik-baik** (sudah di-gitignore, tidak ikut ter-commit). Tanpa keduanya, update APK berikutnya **tidak bisa** dipasang di atas versi lama (tanda tangan beda).

Hasilnya: `app/build/outputs/apk/release/app-release.apk`

---

## Cara B — Build via Android Studio 🖥️

1. Buka **Android Studio** (versi terbaru / Ladybug+).
2. **File → Open** → pilih folder repo ini → tunggu **Gradle Sync** selesai (butuh internet untuk unduh ML Kit & dependensi lain).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. APK muncul di `app/build/outputs/apk/debug/app-debug.apk` (notif *locate* di kanan bawah).

> Kalau Android Studio minta SDK/JDK, biasanya cukup ikon ⚠️ di kanan atas yang menyarankan otomatis memasangnya.

---

## Build via Terminal (setelah wrapper ada)

Setelah `./build.sh` sukses pertama kali, script otomatis membuat **`gradlew`**. Sejak itu build bisa juga lewat cara standar:

```bash
./gradlew assembleDebug     # APK debug
./gradlew assembleRelease   # APK release
./gradlew installDebug      # pasang ke HP yang tersambung
```

---

## 🐧 Build di HP via Termux

Android SDK resmi tidak jalan di Termux, tapi bisa lewat distro Ubuntu di dalam `proot`:

```bash
pkg update && pkg install proot-distro git curl unzip
proot-distro install ubuntu
proot-distro login ubuntu
# di dalam Ubuntu:
apt update && apt install -y curl unzip git
cd lokasi/repo/Andrapk     # repo bisa di-clone ulang di dalam proot
./build.sh
```

> Di dalam proot, build agak lebih lambat dan butuh ruang penyimpanan ±3 GB.

---

## 🔧 Troubleshooting Build

| Gejala | Solusi |
|---|---|
| Build gagal di tengah jalan (internet putus) | Jalankan ulang `./build.sh` — lanjut dari yang sudah terunduh |
| `Could not resolve ...` / dependensi gagal | Pastikan internet aktif; jalankan ulang |
| `Out of memory` / `Metaspace` | Tutup aplikasi lain; pastikan RAM bebas ≥ 4 GB |
| "App not installed" saat pasang di HP | Uninstall APK lama dulu, lalu pasang lagi |
| `sdkmanager` gagal saat setup | Lisensi sudah diterima script; AGP akan mengunduh otomatis saat build — jalankan ulang |
| Cek kondisi lingkungan | `./build.sh info` |

Masih gagal? Copy-paste pesan error lengkapnya untuk dianalisis.

---

## 📡 Setelah APK Terpasang (Server MCP + ChatGPT)

1. Buka aplikasi, pastikan **server MCP ONLINE** (berjalan di port 8765).
2. Jalankan tunnel di Termux/komputer yang satu jaringan dengan HP:
   ```bash
   cloudflared tunnel --url http://127.0.0.1:8765
   ```
3. Di ChatGPT (Settings → Connectors → Create), daftarkan:
   ```
   https://<URL-dari-cloudflare>.trycloudflare.com/mcp
   ```
4. ChatGPT otomatis menemukan OAuth app → muncul **halaman IZINKAN** di HP → setujui → selesai.
   > Jangan pilih "No authentication" — app baru wajib OAuth (401 tanpa token itu memang by design).
5. URL `trycloudflare` berubah setiap tunnel di-restart — daftarkan ulang bila berubah.

Untuk APK lama yang belum ada OAuth in-app, tetap bisa pakai bridge standalone: `termux/mcp_bridge.py` (lihat petunjuk di dalam file / `setup-mcp.sh`).

---

## 🗂️ Struktur Penting

| Path | Isi |
|---|---|
| `app/` | Source code aplikasi Android (Kotlin + Compose) |
| `app/src/main/java/com/example/server/` | HTTP server MCP + OAuth (`JarvisHttpServer.kt`, `OAuthManager.kt`) |
| `termux/mcp_bridge.py` | Bridge standalone untuk APK lama (stdlib-only, port 9000) |
| `build.sh` | Script build otomatis all-in-one |
| `gradle/libs.versions.toml` | Katalog versi dependensi |
