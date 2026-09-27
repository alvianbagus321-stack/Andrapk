#!/data/data/com.termux/files/usr/bin/bash
# =====================================================================
#  JARVIS/ANDRA — Setup ADB Wireless di Termux
#  Tujuan : Termux jadi jembatan ADB shell (tanpa Shizuku/root) agar
#           app bisa: input keyevent (gulir PC via StarDesk), screencap,
#           uiautomator, pm grant, dll lewat `adb shell`.
#  Pakai  : bash setup_adb.sh   (jalankan lagi kapan pun utk reconnect)
# =====================================================================
set -u
PORT_FILE="$HOME/.jad_adb_port"

c() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
step() { c "1;36" "== $1 =="; }
ok()   { c "1;32" "  [OK] $1"; }
err()  { c "1;31" "  [!!] $1"; }
info() { c "0;37" "  $1"; }

# ---------- 0) Pastikan environment Termux ----------
if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ]; then
  err "Jalankan script ini DI DALAM Termux (bukan shell lain)."
  exit 1
fi

step "1/6 Izinkan app mengirim perintah ke Termux (allow-external-apps)"
mkdir -p "$HOME/.termux"
touch "$HOME/.termux/termux.properties"
grep -q 'allow-external-apps=true' "$HOME/.termux/termux.properties" 2>/dev/null || \
  echo 'allow-external-apps=true' >> "$HOME/.termux/termux.properties"
termux-reload-settings 2>/dev/null || true
ok "termux.properties siap (app JARVIS boleh mengirim perintah)"

step "2/6 Instal android-tools (berisi adb)"
pkg update -y >/dev/null 2>&1 || true
pkg install -y android-tools || { err "Gagal install android-tools"; exit 1; }
ok "adb: $(command -v adb)"

step "3/6 Nyalakan server ADB di Termux"
adb start-server >/dev/null 2>&1
termux-wake-lock 2>/dev/null || true
info "termux-wake-lock aktif — JAGA sesi Termux tetap hidup (jangan swipe dari recents),"
info "kalau Termux mati, koneksi ADB ikut mati. Jalankan ulang script ini untuk reconnect."

menu_connect() {
  echo
  c "1;33" "Aktifkan: Setelan > Opsi developer > Wireless debugging > ON"
  echo "  [1] Pairing pertama kali  (pakai 'Pair device with pairing code')"
  echo "  [2] Connect langsung      (pakai IP:PORT di layar Wireless debugging utama)"
  echo "  [3] Reconnect             (pakai port yang tersimpan)"
  echo "  [4] Lewati (sudah connect?)"
  printf "Pilih [1-4]: "
  read -r ch
  case "$ch" in
    1)
      printf "Port PAIRING (di layar 'Pair device with pairing code', mis. 41523): "
      read -r pport
      printf "Kode 6 digit  : "
      read -r code
      adb pair "127.0.0.1:$pport" "$code" || { err "Pairing gagal — cek port/kode, lalu ulangi"; exit 1; }
      ok "Pairing sukses"
      printf "Port CONNECT  (di layar utama 'Wireless debugging', mis. 38521): "
      read -r cport
      adb connect "127.0.0.1:$cport"
      echo "$cport" > "$PORT_FILE"
      ;;
    2)
      printf "Port CONNECT (layar utama Wireless debugging): "
      read -r cport
      adb connect "127.0.0.1:$cport"
      echo "$cport" > "$PORT_FILE"
      ;;
    3)
      cport=$(cat "$PORT_FILE" 2>/dev/null || true)
      if [ -z "$cport" ]; then err "Belum ada port tersimpan — pakai menu 2 dulu"; exit 1; fi
      info "Reconnect ke 127.0.0.1:$cport ..."
      adb connect "127.0.0.1:$cport"
      ;;
    *) ;;
  esac
}

step "4/6 Koneksi ke daemon ADB HP (loopback, tanpa PC)"
menu_connect

step "5/6 Verifikasi adb shell"
if adb shell echo ok 2>/dev/null | grep -q ok; then
  ok "ADB SHELL BERFUNGSI — Termux sekarang punya akses shell level ADB!"
  info "Coba: adb shell input keyevent 93   (PageDown — menggulung halaman PC via StarDesk)"
else
  err "Belum terhubung. Cek: Wireless debugging masih ON? Port sudah benar?"
  info "Jalankan ulang: bash setup_adb.sh   (atau: jad reconnect setelah port berubah)"
fi

step "6/6 Pasang helper 'jad' (reconnect/status/shell)"
cat > "$PREFIX/bin/jad" <<'HELPER'
#!/data/data/com.termux/files/usr/bin/bash
# Helper JARVIS-ADB: jad <status|reconnect|shell <cmd>|key <kode>>
PORT_FILE="$HOME/.jad_adb_port"
case "${1:-}" in
  status)     adb devices ;;
  reconnect)  p=$(cat "$PORT_FILE" 2>/dev/null || true)
              [ -z "$p" ] && { echo "Belum ada port tersimpan"; exit 1; }
              adb connect "127.0.0.1:$p" && adb devices ;;
  shell)      shift; adb shell "$@" ;;
  key)        adb shell input keyevent "$2" ;;
  *) echo "pakai: jad status | jad reconnect | jad shell <perintah> | jad key <keycode>";;
esac
HELPER
chmod +x "$PREFIX/bin/jad"
ok "helper 'jad' terpasang — contoh: jad status, jad reconnect, jad shell input keyevent 93"

echo
c "1;32" "SELESAI. App JARVIS kini bisa mengirim 'adb shell ...' lewat Termux (RUN_COMMAND)."
info "Catatan: port Wireless debugging BERUBAH tiap toggle/reboot -> jalankan 'jad reconnect' (isi port baru)."
