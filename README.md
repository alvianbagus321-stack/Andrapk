# Andra Control (JARVIS-HP)

Aplikasi Android AI companion dengan **server MCP bawaan** (OAuth + HTTP di port 8765) sehingga bisa disambungkan ke **ChatGPT connector** lewat tunnel, plus **Termux agent engine** untuk kontrol perangkat secara otonom (tap, swipe, screenshot, shell, dll).

## 📱 Fitur Lengkap Aplikasi Andrapk (JARVIS — Device Automation Suite)

### 🗣️ 1. Asisten AI Chat + Agent Loop
- **Chat biasa** dengan AI (Gemini/OpenAI-compatible) — model & API key diatur user.
- **Agent Loop otomatis**: AI berpikir → eksekusi tool (tap, swipe, shell, dll) → amati hasil → lanjut sampai tugas selesai (bisa multi-aksi per giliran, maks 8/giliran).
- **Anti-loop**: aksi identik diulang ke-3 → dilewati dgn peringatan; ke-4 → loop berhenti dgn pesan jelas.
- **Anti-timeout**: riwayat dirampingkan (maks 6 giliran, output dipangkas) + **retry koneksi otomatis 1×** + instruksi "efisiensi berpikir" (dilarang mengulang perhitungan yang sama).
- **SOP Remote Desktop**: saat StarDesk aktif, AI diarahkan pakai `ocr_screenshot` → tap koordinat (bukan read_screen yang kosong), scroll hanya via roda/PageDown.

### 🧠 2. AI Quiz Analyzer (HUD melayang) — 3 mode
| Mode | Isi |
|---|---|
| **PRO** | Semua fitur: Auto Analyze, Auto Submit, Auto Jawab, multi-capture, Scan Penuh, Mode advance, Diagnostic, Penjelasan |
| **DEFAULT** | Bersih: 1 scan → AI jawab → Auto Submit (nol gesture) |
| **MANUAL** | Hanya: tombol **📸 Screenshot** (auto-minimize + langsung terlampir), lampiran **ber-thumbnail + viewer penuh** (hapus per item/semua), **Analyze** (kirim semua ke AI), toggle **Auto Jawab** |

- **Metode tangkap (PRO)**: `Adaptif` (video dulu, AI yang kontrol, fallback screenshot), `Screenshot` (klasik), `ScreenRec` (frame video per halaman; soal terpotong wajib scroll; halaman terkirim ke AI berurutan).
- **QuestionCaptureManager**: capture → cek kelengkapan (soal + opsi A–F/1–5/Benar-Salah) → belum lengkap = scroll → capture lagi (merge anti-duplikat). Batas aman 15 halaman.
- **Completion gate**: AI **dilarang menjawab** soal yang belum lengkap — tidak menebak.
- **Kejujuran hitung**: hasil hitung tak ada di opsi → pilih terdekat tapi confidence maks 40% + ditulis jelas.
- **Auto Submit** hanya bila keyakinan ≥70%; kurang → "⚠ AI kurang yakin — DILEWATI".
- **Auto Jawab**: loop jawab+submit sampai selesai (batas soal bisa diatur), support soal **isian** (mengetik).
- **Auto-minimize HUD** saat capture, muncul lagi sendiri.
- **HUD**: bisa **digulir** (drag kartu hanya dari header), chip mode eksklusif, peringatan merah **"Sesi tangkap layar MATI"** = 1 ketuk → dialog izin ulang, chip **Gulir otomatis Off/30/50/70**, Delay (500ms–10s), Posisi roda Otomatis/Kiri/Kanan, tombol **⏹ Stop** + watchdog (120s/panggilan, 240s total).

### 🌀 3. Sistem Scroll (RemoteController)
- **Android asli**: node `isScrollable` → `ACTION_SCROLL_*`, fallback gesture dinamis (0.80H→0.30H, tanpa koordinat mati).
- **StarDesk/remote**: drag pelan **tepat di widget roda** (posisi dideteksi dari screenshot; kiri/kanan) → kalau roda tak terkonfirmasi, drag **dilewati** (kursor PC aman) → fallback **PageUp/PageDown** via Shizuku/Termux-ADB.
- **Scroll = tool call**: `quiz_scroll_page` & `quiz_capture` — AI hanya menggulir bila OCR membuktikan konten terpotong (anti-scroll-tanpa-alasan).

### 📸 4. Screenshot, OCR & Rekaman
- Screenshot **adaptif semua orientasi** (portrait/landscape, MediaProjection + accessibility + **Termux-ADB** sebagai cadangan).
- **OCR dengan bounding-box** → koordinat tap presisi (screenshot 1:1 layar), upscale otomatis untuk teks kecil di video remote, tangkapan gelap/FLAG_SECURE terdeteksi & ditangani.
- **Screen recorder** (MediaRecorder→mp4) + tool `screen_record` untuk agent.

### 🛠️ 5. Tool Registry (dipakai agent)
`open_app, read_screen, screenshot, ocr_screenshot, tap, tap_by_text, swipe, press_key, input_text, adb_shell, get_device_resolution, screen_record, quiz_scroll_page, quiz_capture, create_tool` (AI bahkan bisa membuat tool baru), plus permission mode (SAFE/FULL_ACCESS) & konfirmasi penghapusan.

### 🐧 6. Integrasi Termux-ADB (pengganti Shizuku)
- One-liner `setup_adb.sh`: auto `allow-external-apps`, install android-tools, adb connect, helper `jad` (status/reconnect/shell). Kartu **HELP hijau** in-app.
- Jalur `adb shell` & `screencap` lewat Termux (screenshot tetap jalan walau izin capture mati).

### 🎛️ 7. Overlay & UI
- Kartu floating UHD (Thinking/Executing/Result) **dibatasi 85% layar + scroll internal**, float bar tidak menghalangi klik layar, pill minimize, spektrum suara.
- **Mode advance** persisten (semua setelan tersimpan), laporan crash in-app (tap = salin log).

### 🎙️ 8. Suara & Server
- **Hotword "Jarvis"**, voice call, TTS/STT.
- **Local HTTP server** (127.0.0.1:8765) + **MCP server** + **OAuth** + tunnel — MCP bridge Python stdlib-only di Termux.

<img width="720" height="1600" alt="Screenshot_20261006-214925_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/8df56668-685d-4304-bd93-7c8275722646" />
<img width="720" height="1600" alt="Screenshot_20261006-213805_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/ac0f3258-c908-4bac-ace6-ac34e1924bf4" />
<img width="720" height="1600" alt="Screenshot_20261006-213814_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/8f3c385d-122f-42e7-85c3-c389b45c9e54" />
<img width="720" height="1600" alt="Screenshot_20261006-214936_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/4e863348-1d31-45b1-b953-4e3175d22218" />
<img width="720" height="1600" alt="Screenshot_20261006-213735_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/d537b923-f3ce-4888-9503-af7a23546b17" />
<img width="720" height="1600" alt="Screenshot_20261006-213730_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/1130b98b-61bf-469f-89b8-674f70de5634" />
<img width="720" height="1600" alt="Screenshot_20261006-213723_transfer_2026-10-06_215036" src="https://github.com/user-attachments/assets/9795058e-d6d7-4e90-aa75-f0c30e4308c2" />
