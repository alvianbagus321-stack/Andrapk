#!/usr/bin/env bash
# ============================================================================
#  build.sh — Build otomatis JARVIS-HP (APK)
#  ---------------------------------------------------------------------------
#  Satu script untuk menyiapkan SEMUA kebutuhan build lalu membuat APK:
#    1. JDK 21 (Ada/OpenJDK) — via package manager (apt/dnf/pacman/zypper/brew)
#    2. Android SDK          — cmdline-tools + sdkmanager (platform 36.1,
#                              build-tools 36.0.0, platform-tools) + license
#    3. Gradle 9.3.1         — versi mengikuti gradle/wrapper/gradle-wrapper.properties
#    4. File pendukung       — local.properties, .env, debug.keystore,
#                              keystore release (dibuat otomatis bila belum ada)
#    5. Build                — assembleDebug / assembleRelease + adb install
#
#  PEMAKAIAN:
#    ./build.sh              # = debug: setup semua + assembleDebug
#    ./build.sh setup        # hanya siapkan JDK+SDK+Gradle (tanpa build)
#    ./build.sh debug        # build APK debug
#    ./build.sh release      # build APK release (keystore dibuat otomatis)
#    ./build.sh install      # build debug + pasang ke HP via adb
#    ./build.sh clean        # bersihkan hasil build
#    ./build.sh info         # tampilkan status lingkungan build
#
#  LINGKUNGAN YANG DIDUKUNG:
#    - Linux x86_64 (Ubuntu/Debian/Fedora/Arch/openSUSE)  [utama]
#    - macOS (Intel & Apple Silicon)                      [via Homebrew]
#    - WSL (Windows)                                      [perlakukan sbg Linux]
#    - Termux/Alpine: TIDAK langsung — lihat petunjuk yang muncul otomatis
#
#  VARIABEL ENV (opsional):
#    ANDROID_HOME       lokasi SDK (default: ~/Android/Sdk)
#    JAVA_HOME          lokasi JDK bila terpasang di lokasi khusus
#    GRADLE_VERSION     override versi Gradle (default: ikuti wrapper)
#    BUILD_TOOLS        override build-tools (default: 36.0.0)
#    KEYSTORE_PATH / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD  (release)
# ============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------- tampilan ---
if [[ -t 1 ]]; then
  C_G=$'\033[1;32m'; C_Y=$'\033[1;33m'; C_R=$'\033[1;31m'; C_B=$'\033[1;36m'; C_N=$'\033[0m'
else
  C_G=""; C_Y=""; C_R=""; C_B=""; C_N=""
fi
log()  { printf '%s\n' "${C_B}[build]${C_N} $*"; }
ok()   { printf '%s\n' "${C_G}  OK ${C_N} $*"; }
warn() { printf '%s\n' "${C_Y}  !  ${C_N} $*" >&2; }
err()  { printf '%s\n' "${C_R}  X  ${C_N} $*" >&2; }
die()  { err "$*"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# ------------------------------------------------------------ deteksi OS -----
OS_NAME="$(uname -s)"
ARCH="$(uname -m)"
IS_MAC=false; IS_LINUX=false
case "$OS_NAME" in
  Darwin) IS_MAC=true ;;
  Linux)  IS_LINUX=true ;;
  MINGW*|MSYS*|CYGWIN*)
    err "Windows native tidak didukung script ini."
    echo  "  Pilihan: (1) jalankan di WSL:  wsl --install -d Ubuntu   lalu ulangi ./build.sh"
    echo  "           (2) pakai Android Studio seperti biasa."
    exit 1 ;;
  *) die "OS tidak dikenali: $OS_NAME" ;;
esac

# Termux (Android) → arahkan ke proot-distro
if [[ "${TERMUX_VERSION:-}" != "" || "${PREFIX:-}" == *com.termux* ]]; then
  err "Termux terdeteksi — Android SDK resmi tidak jalan di Termux (bukan glibc)."
  echo "  Cara tetap bisa build di HP ini:"
  echo "    pkg update && pkg install proot-distro"
  echo "    proot-distro install ubuntu"
  echo "    proot-distro login ubuntu   # lalu di dalamnya: apt update && apt install -y curl unzip git"
  echo "    git clone/pull repo ini, lalu jalankan ./build.sh lagi"
  exit 1
fi

# Alpine/musl → aapt2 dari AGP hanya glibc, tidak akan jalan
if $IS_LINUX && ldd --version 2>&1 | head -1 | grep -qi musl; then
  err "Alpine (musl) tidak didukung — aapt2 Android memakai glibc."
  echo "  Gunakan container/distro glibc (Ubuntu/Debian/Fedora) lalu jalankan script ini."
  exit 1
fi

# ------------------------------------------------------------- konfigurasi ---
# Versi Gradle: ikuti gradle-wrapper.properties (sumber kebenaran repo)
GRADLE_VERSION="${GRADLE_VERSION:-$(sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin.zip.*/\1/p' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)}"
GRADLE_VERSION="${GRADLE_VERSION:-9.3.1}"

# Platform & build-tools: parse dari app/build.gradle.kts (compileSdk release(N) minor M)
_ver_major="$(sed -n 's/.*release(\([0-9]\+\)).*/\1/p' app/build.gradle.kts 2>/dev/null | head -1)"
_ver_minor="$(sed -n 's/.*minorApiLevel[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' app/build.gradle.kts 2>/dev/null | head -1)"
PLATFORM="${PLATFORM:-android-${_ver_major:-36}${_ver_minor:+.${_ver_minor}}}"
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
GRADLE_ROOT="$HOME/.gradle-dist"
JDK_MIN=17

APK_DEBUG="app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="app/build/outputs/apk/release/app-release.apk"

# ------------------------------------------------------------ util dasar -----
download() { # download <url> <tujuan>
  local url="$1" out="$2"
  if have curl; then curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -# -o "$out" "$url"
  elif have wget; then wget -q --tries=3 -O "$out" "$url"
  else return 127; fi
}

SUDO=""
if [[ $EUID -ne 0 ]] && have sudo; then SUDO="sudo"; fi

pkg_install() { # pkg_install <paket...>
  local pm=""
  if have apt-get; then pm="apt-get"
  elif have dnf; then pm="dnf"
  elif have yum; then pm="yum"
  elif have pacman; then pm="pacman"
  elif have zypper; then pm="zypper"
  elif have brew; then pm="brew"
  fi
  [[ -z "$pm" ]] && return 1
  case "$pm" in
    apt-get) $SUDO apt-get update -y >/dev/null 2>&1 || true; DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y "$@" ;;
    dnf)     $SUDO dnf install -y "$@" ;;
    yum)     $SUDO yum install -y "$@" ;;
    pacman)  $SUDO pacman -Sy --noconfirm "$@" ;;
    zypper)  $SUDO zypper --non-interactive install "$@" ;;
    brew)    brew install "$@" ;;
  esac
}

# --------------------------------------------------------------- 1) JDK ------
java_major() {
  java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]\+\).*/\1/p'
}

ensure_jdk() {
  if have java; then
    local v; v="$(java_major || true)"
    if [[ -n "$v" && "$v" -ge "$JDK_MIN" ]] 2>/dev/null && have javac; then
      ok "JDK $v sudah terpasang"
      return 0
    fi
    warn "Java terpasang versi ${v:-?} (< $JDK_MIN) atau javac hilang — akan dipasang JDK 21"
  fi

  log "Memasang JDK 21 ..."
  if $IS_MAC; then
    have brew || die "Homebrew belum ada. Pasang dulu: /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
    brew list openjdk@21 >/dev/null 2>&1 || pkg_install openjdk@21
    export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    if have apt-get; then pkg_install openjdk-21-jdk-headless || pkg_install openjdk-17-jdk-headless
    elif have dnf || have yum; then pkg_install java-21-openjdk-devel
    elif have pacman; then pkg_install jdk21-openjdk
    elif have zypper; then pkg_install java-21-openjdk-devel
    else die "Tidak ada package manager yang dikenali. Pasang JDK $JDK_MIN+ manual, lalu jalankan ulang."; fi
    # temukan JAVA_HOME kandidat
    if [[ -z "${JAVA_HOME:-}" ]]; then
      local cand
      cand="$(ls -1d /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/java-21* 2>/dev/null | head -1 || true)"
      [[ -n "$cand" && -x "$cand/bin/java" ]] && export JAVA_HOME="$cand"
    fi
    [[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"
  fi

  local v; v="$(java_major || true)"
  [[ -n "$v" && "$v" -ge "$JDK_MIN" ]] 2>/dev/null || die "JDK $JDK_MIN+ gagal terpasang. Pasang manual lalu jalankan ulang."
  ok "JDK $v siap (${JAVA_HOME:-dari PATH})"
}

# ------------------------------------------------------- 2) Android SDK ------
cmdline_tools_urls() { # daftar URL kandidat sesuai OS (fallback bila 404)
  local base="https://dl.google.com/android/repository"
  local nums=(13114759 11076708 10406996 9862970 9477386)
  local os="linux"; $IS_MAC && os="mac"
  local out=() n
  for n in "${nums[@]}"; do out+=("$base/commandlinetools-${os}-${n}_latest.zip"); done
  printf '%s\n' "${out[@]}"
}

ensure_sdk() {
  if [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]] \
     || [[ -d "$SDK_DIR/platforms" && -d "$SDK_DIR/build-tools" ]]; then
    ok "Android SDK ada di $SDK_DIR"
  else
    log "Mengunduh Android cmdline-tools ke $SDK_DIR ..."
    mkdir -p "$SDK_DIR"
    local tmp_zip="$SDK_DIR/cmdline-tools.zip" url installed=false
    while IFS= read -r url; do
      log "  coba: ${url##*/}"
      if download "$url" "$tmp_zip" && unzip -q -o "$tmp_zip" -d "$SDK_DIR/.ctmp"; then
        mkdir -p "$SDK_DIR/cmdline-tools"
        rm -rf "$SDK_DIR/cmdline-tools/latest"
        mv "$SDK_DIR/.ctmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
        installed=true; break
      fi
      rm -f "$tmp_zip"; rm -rf "$SDK_DIR/.ctmp"
    done < <(cmdline_tools_urls)
    $installed || die "Gagal mengunduh cmdline-tools. Unduh manual dari https://developer.android.com/studio#command-line-tools-only lalu ekstrak ke $SDK_DIR/cmdline-tools/latest"
    rm -f "$tmp_zip"
    ok "cmdline-tools terpasang"
  fi

  local SDKMGR="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "$SDKMGR" ]] || die "sdkmanager tidak ditemukan di $SDKMGR"

  log "Menerima lisensi Android SDK ..."
  yes 2>/dev/null | "$SDKMGR" --licenses --sdk_root="$SDK_DIR" >/dev/null 2>&1 || true

  log "Memasang paket SDK: platform-tools, $PLATFORM, build-tools;$BUILD_TOOLS"
  if ! yes 2>/dev/null | "$SDKMGR" --sdk_root="$SDK_DIR" "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" >/dev/null; then
    warn "Gagal pasang $PLATFORM — coba platform tanpa minor (android-${_ver_major:-36}) ..."
    yes 2>/dev/null | "$SDKMGR" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-${_ver_major:-36}" "build-tools;$BUILD_TOOLS" >/dev/null || \
      warn "sdkmanager gagal — AGP akan mencoba mengunduh otomatis saat build (lisensi sudah diterima)."
  fi
  ok "Android SDK siap"

  # local.properties (dibaca AGP; .gitignore sudah mengabaikannya)
  local sdk_esc="${SDK_DIR//\\/\\\\}"; sdk_esc="${sdk_esc//:/\\:}"
  printf 'sdk.dir=%s\n' "$sdk_esc" > local.properties
  ok "local.properties -> $SDK_DIR"
}

# ------------------------------------------------------------ 3) Gradle ------
ensure_gradle() {
  # a) pakai wrapper bila lengkap (jar + gradlew)
  if [[ -x "./gradlew" && -f "gradle/wrapper/gradle-wrapper.jar" ]]; then
    GRADLE_CMD="./gradlew"; ok "Memakai Gradle wrapper (./gradlew $GRADLE_VERSION)"
    return 0
  fi
  # b) gradle sistem yang cukup baru
  if have gradle; then
    local gv; gv="$(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9.]\+\).*/\1/p' | head -1)"
    if [[ -n "$gv" && "${gv%%.*}" -ge 9 ]] 2>/dev/null; then
      GRADLE_CMD="gradle"; ok "Memakai Gradle sistem $gv"
      return 0
    fi
  fi
  # c) unduh distribusi resmi ke ~/.gradle-dist
  local dist="$GRADLE_ROOT/gradle-$GRADLE_VERSION"
  if [[ -x "$dist/bin/gradle" ]]; then
    GRADLE_CMD="$dist/bin/gradle"; ok "Memakai Gradle $GRADLE_VERSION (terpasang sebelumnya)"
    return 0
  fi
  log "Mengunduh Gradle $GRADLE_VERSION ..."
  mkdir -p "$GRADLE_ROOT"
  local zip="$GRADLE_ROOT/gradle-$GRADLE_VERSION-bin.zip"
  download "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" "$zip" \
    || die "Gagal mengunduh Gradle. Cek internet atau unduh manual: https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q -o "$zip" -d "$GRADLE_ROOT" && rm -f "$zip"
  [[ -x "$dist/bin/gradle" ]] || die "Arsip Gradle tidak valid."
  GRADLE_CMD="$dist/bin/gradle"
  ok "Gradle $GRADLE_VERSION terpasang di $dist"
}

# --------------------------------------------------- 4) file pendukung -------
ensure_project_files() {
  # .env untuk secrets plugin (tidak wajib diisi)
  if [[ ! -f .env && -f .env.example ]]; then
    cp .env.example .env
    warn ".env dibuat dari .env.example (GEMINI_API_KEY masih placeholder — isi bila perlu)"
  fi
  # debug.keystore (signing debugConfig; .gitignore mengabaikannya)
  if [[ ! -f debug.keystore ]]; then
    log "Membuat debug.keystore ..."
    "$(java_home_bin)/keytool" -genkeypair -keystore debug.keystore -storepass android \
      -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    ok "debug.keystore dibuat"
  fi
}

java_home_bin() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then echo "$JAVA_HOME/bin"
  else dirname "$(command -v java)"; fi
}

ensure_release_keystore() {
  # prioritas: env user > release-signing.env > buat baru
  if [[ -f release-signing.env ]]; then
    # shellcheck disable=SC1091
    source release-signing.env
  fi
  export KEYSTORE_PATH="${KEYSTORE_PATH:-$REPO_DIR/my-upload-key.jks}"
  export STORE_PASSWORD="${STORE_PASSWORD:-jarvis-release}"
  export KEY_ALIAS="${KEY_ALIAS:-upload}"
  export KEY_PASSWORD="${KEY_PASSWORD:-$STORE_PASSWORD}"

  if [[ ! -f "$KEYSTORE_PATH" ]]; then
    log "Membuat keystore release: $KEYSTORE_PATH (alias $KEY_ALIAS)"
    "$(java_home_bin)/keytool" -genkeypair -v -keystore "$KEYSTORE_PATH" \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
      -dname "CN=JARVIS,OU=Dev,O=Personal,L=Surabaya,ST=JawaTimur,C=ID" >/dev/null
    cat > release-signing.env <<EOF
# Dipakai otomatis oleh build.sh saat build release. JANGAN di-commit / dibagikan.
KEYSTORE_PATH="$KEYSTORE_PATH"
STORE_PASSWORD="$STORE_PASSWORD"
KEY_ALIAS="$KEY_ALIAS"
KEY_PASSWORD="$KEY_PASSWORD"
EOF
    chmod 600 release-signing.env "$KEYSTORE_PATH"
    warn "SIMPAN my-upload-key.jks + release-signing.env — tanpa keduanya APK berikutnya tidak bisa di-update dengan tanda tangan sama"
  fi
  ok "Signing release: $KEYSTORE_PATH"
}

# ------------------------------------------------------------- build ---------
run_gradle() { # run_gradle <task...>
  # Memori ADAPTIF v2: keputusan berdasar memori AVAILABLE (bukan total) —
  # mesin CI sering total besar tapi tersisa sedikit. "Gradle daemon
  # disappeared" = hampir selalu OOM. GRADLE_OPTS pemicu DIHORMATI.
  if [[ -z "${GRADLE_OPTS:-}" ]]; then
    local avail_mb; avail_mb="$(free -m 2>/dev/null | awk '/^Mem:/{print $7}' | head -1)"
    avail_mb="${avail_mb:-0}"
    local xmx kx workers wtxt extra=""
    if [[ "$avail_mb" -ge 6000 ]] 2>/dev/null; then
      xmx=4g; kx=2g; workers=0; wtxt="otomatis"
    elif [[ "$avail_mb" -ge 3500 ]] 2>/dev/null; then
      xmx=2560m; kx=1024m; workers=2; wtxt="2"
    else
      # Mesin sempit: SATU JVM saja (Kotlin in-process, tanpa daemon terpisah)
      xmx=1536m; kx=""; workers=1; wtxt="1"
      extra="-Dorg.gradle.parallel=false -Dkotlin.compiler.execution.strategy=in-process"
    fi
    unset JAVA_TOOL_OPTIONS || true
    export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx$xmx -Dfile.encoding=UTF-8 -Djava.awt.headless=true"
    if [[ -n "$kx" ]]; then export GRADLE_OPTS="$GRADLE_OPTS -Dkotlin.daemon.jvmargs=-Xmx$kx"; fi
    if [[ "$workers" != 0 ]]; then export GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.workers.max=$workers"; fi
    if [[ -n "$extra" ]]; then export GRADLE_OPTS="$GRADLE_OPTS $extra"; fi
    ok "Memori tersedia ${avail_mb}MB → heap Gradle $xmx${kx:+, Kotlin daemon $kx}${kx:-, Kotlin in-process}, workers $wtxt"
  fi
  # Hentikan daemon BAWAAN lama: daemon hidup mempertahankan heap settings
  # lama dan dipakai ulang tanpa memedulikan GRADLE_OPTS baru (jebakan OOM).
  "$GRADLE_CMD" --stop >/dev/null 2>&1 || true
  log "Menjalankan: $GRADLE_CMD $*"
  if ! "$GRADLE_CMD" "$@"; then
    err "Build GAGAL. Hal yang lazim dicek:"
    echo  "  - Internet aktif (dependensi diunduh dari google()/mavenCentral())"
    echo  "  - RAM >= 4 GB (gradle.properties memakai -Xmx4g)"
    echo  "  - Jalankan ulang ./build.sh sekali lagi (unduhan bisa terputus di tengah)"
    exit 1
  fi
}

gen_wrapper() { # buat ./gradlew sekali agar Android Studio/skrip lain bisa pakai
  [[ -x "./gradlew" && -f "gradle/wrapper/gradle-wrapper.jar" ]] && return 0
  log "Membuat Gradle wrapper (gradlew) untuk pemakaian berikutnya ..."
  "$GRADLE_CMD" wrapper --gradle-version "$GRADLE_VERSION" >/dev/null 2>&1 \
    && ok "gradlew dibuat (boleh di-commit agar Android Studio bisa sync)" \
    || warn "Gagal membuat wrapper (tidak fatal — build tetap bisa via ./build.sh)"
}

do_build_debug() {
  ensure_jdk; ensure_sdk; ensure_gradle; ensure_project_files
  run_gradle assembleDebug
  gen_wrapper
  echo
  ok "APK DEBUG siap:"
  ls -lh "$APK_DEBUG" 2>/dev/null || true
  echo "   $REPO_DIR/$APK_DEBUG"
  echo "   Pasang:  adb install -r $APK_DEBUG   (atau ./build.sh install)"
}

do_build_release() {
  ensure_jdk; ensure_sdk; ensure_gradle; ensure_project_files; ensure_release_keystore
  run_gradle assembleRelease
  gen_wrapper
  echo
  ok "APK RELEASE siap:"
  ls -lh "$APK_RELEASE" 2>/dev/null || true
  echo "   $REPO_DIR/$APK_RELEASE"
}

do_install() {
  do_build_debug
  local ADB="$SDK_DIR/platform-tools/adb"
  [[ -x "$ADB" ]] || die "adb tidak ada di $ADB"
  log "Mendeteksi perangkat ..."
  local dev
  dev="$("$ADB" devices | grep -E '^[[:alnum:]._:-]+[[:space:]]+device' | head -1 || true)"
  if [[ -z "$dev" ]]; then
    warn "Tidak ada HP terhubung (USB debugging aktif + izin komputer di HP)."
    echo "   APK tetap bisa dipasang manual: $REPO_DIR/$APK_DEBUG"
    exit 2
  fi
  log "Memasang ke ${dev%%[[:space:]]*} ..."
  "$ADB" install -r "$APK_DEBUG"
  ok "Terpasang. Buka aplikasi JARVIS di HP."
}

do_clean() {
  ensure_jdk; ensure_gradle
  run_gradle clean
  ok "Selesai dibersihkan."
}

do_info() {
  echo "┌─ Status lingkungan build ─────────────────────────────"
  echo "│ OS/Arch     : $OS_NAME ($ARCH)"
  echo "│ Repo        : $REPO_DIR"
  if have java; then
    echo "│ Java        : $(java -version 2>&1 | head -1) ${JAVA_HOME:+(JAVA_HOME=$JAVA_HOME)}"
  else
    echo "│ Java        : BELUM ADA (butuh JDK $JDK_MIN+)"
  fi
  echo "│ Android SDK : ${SDK_DIR}$([[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]] && echo ' ✓' || echo ' (belum ada — akan diunduh)')"
  if have gradle; then echo "│ Gradle      : $(gradle --version 2>/dev/null | sed -n 's/^Gradle \(.*\)/\1/p' | head -1)"; else
    echo "│ Gradle      : belum di PATH (akan diunduh $GRADLE_VERSION)"; fi
  echo "│ Platform    : $PLATFORM   | Build-tools: $BUILD_TOOLS"
  echo "│ .env        : $([[ -f .env ]] && echo ada || echo 'belum (akan dibuat)')"
  echo "│ debug.ks    : $([[ -f debug.keystore ]] && echo ada || echo 'belum (akan dibuat)')"
  echo "│ release.ks  : $([[ -f my-upload-key.jks || -n "${KEYSTORE_PATH:-}" ]] && echo ada || echo 'belum (akan dibuat saat build release)')"
  echo "└───────────────────────────────────────────────────────"
}

# --------------------------------------------------------------- main --------
CMD="${1:-debug}"
case "$CMD" in
  setup)   ensure_jdk; ensure_sdk; ensure_gradle; ensure_project_files; ok "Semua komponen siap. Jalankan: ./build.sh debug" ;;
  debug)   do_build_debug ;;
  release) do_build_release ;;
  install) do_install ;;
  clean)   do_clean ;;
  info)    do_info ;;
  *) sed -n '2,30p' "$0"; die "Perintah tidak dikenal: $CMD" ;;
esac
