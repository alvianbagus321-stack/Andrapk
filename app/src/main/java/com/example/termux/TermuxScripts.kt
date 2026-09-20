package com.example.termux

object TermuxScripts {

    val instructionMd: String = """
# JARVIS-HP Tool Registry & Custom Tool Guide
=============================================

Sistem JARVIS-HP dirancang dengan **3-Layer Architecture** modular:

               JARVIS Agent
                    │
             ┌──────▼──────┐
             │ Tool Registry│  (tools/__init__.py)
             └──────┬──────┘
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
   Android       Termux       Custom
    Tools         Tools        Tools
       │            │            │
    APK/API       Shell       Python
(Accessibility) (Native CLI)  (tools/custom/*.py)

Struktur Direktori:
-------------------
JARVIS-HP/
├── agent.py               # AI Agent Engine & loop
├── config.py              # Konfigurasi token & endpoint
├── memory.py              # SQLite memory & action logger
├── INSTRUCTION.md         # Panduan ini
└── tools/
    ├── __init__.py        # Tool Registry & Decorator @tool & Dynamic Scanner
    ├── android_tools.py   # HTTP bridge ke Android Companion APK (127.0.0.1:8765)
    ├── termux_tools.py    # Perintah Shell & Termux API
    └── custom/            # Tempat menyimpan Tool buatanmu sendiri!
        ├── __init__.py
        ├── cek_storage.py # Contoh tool disk storage
        ├── buka_youtube.py# Contoh tool deep link YouTube
        └── cek_ram.py     # Contoh tool cek RAM

---

## 1. Konsep Decorator @tool

Setiap tool didefinisikan menggunakan decorator `@tool` di dalam modul python:

```python
from tools import tool

@tool(
    name="nama_tool",
    description="Penjelasan fungsi tool agar AI mengerti kapan harus menggunakannya",
    schema={
        "type": "object",
        "properties": {
            "parameter_1": {
                "type": "string",
                "description": "Keterangan parameter"
            }
        },
        "required": ["parameter_1"]
    },
    requires_confirmation=False  # True jika butuh persetujuan user sebelum jalan
)
def nama_tool(parameter_1: str):
    # Logika eksekusi tool di sini
    return {"status": "ok", "hasil": parameter_1}
```

---

## 2. Cara Menambahkan Custom Tool Baru

1. Buat file baru di dalam folder `tools/custom/`, misalnya `tools/custom/cek_suhu.py`.
2. Tulis fungsi dengan decorator `@tool`.
3. Simpan file.
4. Selesai! Saat JARVIS dijalankan (`python agent.py`), modul Scanner otomatis:
   - Mendeteksi semua file `.py` di dalam `tools/custom/`
   - Meregistrasikan fungsi `@tool` ke dalam `TOOLS`
   - Menjadikan tool tersebut langsung dapat dipilih oleh AI tanpa mengubah `agent.py`!

---

## 3. Contoh-Contoh Custom Tool

### Contoh A: Cek Kapasitas Storage HP (`tools/custom/cek_storage.py`)
```python
import shutil
from tools import tool

@tool(
    name="get_storage",
    description="Mendapatkan informasi penyimpanan HP (total, used, free dalam GB)",
    schema={
        "type": "object",
        "properties": {}
    }
)
def get_storage():
    total, used, free = shutil.disk_usage("/")
    return {
        "total_gb": round(total / 1024**3, 2),
        "used_gb": round(used / 1024**3, 2),
        "free_gb": round(free / 1024**3, 2)
    }
```

### Contoh B: Membuka YouTube & Mencari Video (`tools/custom/buka_youtube.py`)
```python
from tools import tool
from tools.android_tools import open_app, tap, type_text
import time

@tool(
    name="buka_youtube",
    description="Membuka aplikasi YouTube dan mencari video tertentu",
    schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "Judul video yang ingin dicari"}
        }
    }
)
def buka_youtube(query: str = ""):
    open_app("com.google.android.youtube")
    if query:
        time.sleep(2)
        type_text(None, query)
    return {"status": "ok", "message": f"YouTube dibuka dengan query: {query}"}
```

### Contoh C: Menjalankan Perintah Terminal / Shell (`termux_tools.py`)
```python
# Tersedia langsung di tools.termux_tools:
# AI dapat memanggil tool: 'shell' atau 'termux_command'
# dengan parameter: {"command": "ls -la /sdcard"}
```

---

## 4. Keamanan & Konfirmasi Manual
Tool yang bertanda `requires_confirmation=True` (misalnya penghapusan file, eksekusi shell berisiko, atau transfer data) akan secara otomatis memunculkan prompt konfirmasi keamanan di terminal sebelum kode dieksekusi.
""".trimIndent()

    val toolsInitPy: String = """
# ==========================================================
# JARVIS-HP Dynamic Tool Registry & Decorator Engine
# ==========================================================
import os
import glob
import importlib.util
import json

# Global dictionary holding all discovered tools
# Format: name -> {"function": callable, "description": str, "schema": dict, "requires_confirmation": bool, "category": str}
TOOLS = {}

def tool(name: str, description: str, schema: dict = None, requires_confirmation: bool = False, category: str = "custom"):
    ""${'"'}
    Decorator to register a function as an autonomous AI tool.
    ""${'"'}
    def decorator(func):
        TOOLS[name] = {
            "function": func,
            "description": description.strip(),
            "schema": schema or {"type": "object", "properties": {}},
            "requires_confirmation": requires_confirmation,
            "category": category
        }
        return func
    return decorator

def load_all_tools():
    ""${'"'}
    Scans and imports:
      1. tools.android_tools (Layer 1: APK Accessibility/HTTP API)
      2. tools.termux_tools  (Layer 2: Termux Native Shell & Device APIs)
      3. tools/custom/*.py   (Layer 3: Dynamic user-created tools)
    ""${'"'}
    # 1 & 2. Load built-in layer tools
    try:
        import tools.android_tools
    except ImportError as e:
        print(f"[ToolRegistry] Notice loading android_tools: {e}")

    try:
        import tools.termux_tools
    except ImportError as e:
        print(f"[ToolRegistry] Notice loading termux_tools: {e}")

    # 3. Dynamic Scan of tools/custom/*.py
    base_dir = os.path.dirname(os.path.abspath(__file__))
    custom_dir = os.path.join(base_dir, "custom")
    
    if os.path.isdir(custom_dir):
        py_files = glob.glob(os.path.join(custom_dir, "*.py"))
        for file_path in py_files:
            file_name = os.path.basename(file_path)
            if file_name == "__init__.py" or file_name.startswith("."):
                continue
            
            module_name = f"tools.custom.{file_name[:-3]}"
            try:
                spec = importlib.util.spec_from_file_location(module_name, file_path)
                if spec and spec.loader:
                    mod = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(mod)
            except Exception as e:
                print(f"[ToolRegistry] Error loading custom tool '{file_name}': {e}")

    return TOOLS

def execute_tool(name: str, args: dict = None) -> dict:
    ""${'"'}
    Executes a tool from the registry with safety verification.
    ""${'"'}
    args = args or {}
    if name not in TOOLS:
        return {
            "status": "error",
            "error_code": "UNKNOWN_TOOL",
            "message": f"Tool '{name}' is not registered in Tool Registry.",
            "available_tools": list(TOOLS.keys()),
            "retryable": False
        }

    meta = TOOLS[name]
    
    # Enforce confirmation if marked
    if meta.get("requires_confirmation", False):
        print(f"\n⚠️  [SECURITY CONFIRMATION REQUIRED]")
        print(f"Tool '{name}' wants to run with arguments: {json.dumps(args, indent=2)}")
        answer = input("Approve this sensitive action? (y/N): ").strip().lower()
        if answer != "y":
            return {
                "status": "error",
                "error_code": "USER_REJECTED",
                "message": "Action was explicitly rejected by user confirmation.",
                "retryable": False
            }

    try:
        result = meta["function"](**args)
        if isinstance(result, dict):
            return result
        return {"status": "ok", "result": result}
    except TypeError as e:
        return {
            "status": "error",
            "error_code": "INVALID_ARGUMENTS",
            "message": f"Invalid arguments for tool '{name}': {e}",
            "expected_schema": meta["schema"],
            "retryable": False
        }
    except Exception as e:
        return {
            "status": "error",
            "error_code": "EXECUTION_EXCEPTION",
            "message": str(e),
            "retryable": False
        }

def get_tools_prompt_description() -> str:
    lines = []
    for name, item in sorted(TOOLS.items()):
        schema_props = item["schema"].get("properties", {})
        props_str = ", ".join([f"{k}: {v.get('type', 'any')}" for k, v in schema_props.items()])
        lines.append(f"- {name}({props_str}): {item['description']}")
    return "\n".join(lines)
""".trimIndent()

    val androidToolsPy: String = """
# ==========================================================
# Layer 1: Android Companion APK Bridge Tools
# Connects to Companion App at http://127.0.0.1:8765
# ==========================================================
import requests
import json
import time
from config import ANDROID_ENDPOINT, LOCAL_TOKEN, REQUEST_TIMEOUT_SECONDS
from tools import tool

def _headers():
    return {
        "Content-Type": "application/json",
        "X-Local-Token": LOCAL_TOKEN
    }

def android_request(path: str, body: dict = None, method: str = "POST", retries: int = 3) -> dict:
    url = f"{ANDROID_ENDPOINT}{path}"
    backoff = [0.5, 1.0, 2.0]

    for attempt in range(retries):
        try:
            if method.upper() == "GET":
                resp = requests.get(url, headers=_headers(), timeout=REQUEST_TIMEOUT_SECONDS)
            else:
                resp = requests.post(url, headers=_headers(), json=body or {}, timeout=REQUEST_TIMEOUT_SECONDS)
            return resp.json()
        except (requests.ConnectionError, requests.Timeout) as e:
            if attempt < retries - 1:
                time.sleep(backoff[attempt])
                continue
            return {
                "status": "error",
                "error_code": "CONNECTION_REFUSED",
                "message": f"Companion app server unreachable at {url}: {e}",
                "retryable": True
            }
        except Exception as e:
            return {
                "status": "error",
                "error_code": "UNKNOWN_ERROR",
                "message": str(e),
                "retryable": False
            }

@tool(
    name="open_app",
    description="Membuka aplikasi di Android berdasarkan package name atau nama umum",
    schema={
        "type": "object",
        "properties": {
            "package_name": {"type": "string", "description": "Package name aplikasi, e.g. com.google.android.youtube"}
        },
        "required": ["package_name"]
    },
    category="android"
)
def open_app(package_name: str):
    return android_request("/app/open", {"package_name": package_name})

@tool(
    name="close_app",
    description="Menutup aplikasi target",
    schema={
        "type": "object",
        "properties": {
            "package_name": {"type": "string", "description": "Package name aplikasi"}
        },
        "required": ["package_name"]
    },
    category="android"
)
def close_app(package_name: str):
    return android_request("/app/close", {"package_name": package_name})

@tool(
    name="current_app",
    description="Melihat nama aplikasi yang sedang aktif di layar pengguna",
    schema={"type": "object", "properties": {}},
    category="android"
)
def current_app():
    return android_request("/app/current", method="GET")

@tool(
    name="read_screen",
    description="Membaca semua elemen UI yang tampil di layar (teks, ID elemen, bounds)",
    schema={"type": "object", "properties": {}},
    category="android"
)
def read_screen():
    return android_request("/screen/elements", method="GET")

@tool(
    name="tap",
    description="Menekan layar Android pada koordinat (x, y) atau identifier elemen",
    schema={
        "type": "object",
        "properties": {
            "x": {"type": "number", "description": "Koordinat horizontal X"},
            "y": {"type": "number", "description": "Koordinat vertikal Y"},
            "element_id": {"type": "string", "description": "Opsional: ID elemen UI"}
        }
    },
    category="android"
)
def tap(x: float = None, y: float = None, element_id: str = None):
    body = {}
    if x is not None and y is not None:
        body["x"] = float(x)
        body["y"] = float(y)
    elif element_id:
        body["element_id"] = element_id
    else:
        return {"status": "error", "message": "Harus memberikan x, y atau element_id"}
    return android_request("/action/tap", body)

@tool(
    name="swipe",
    description="Menggeser layar dari koordinat awal (x1, y1) ke (x2, y2)",
    schema={
        "type": "object",
        "properties": {
            "x1": {"type": "number"},
            "y1": {"type": "number"},
            "x2": {"type": "number"},
            "y2": {"type": "number"},
            "duration_ms": {"type": "integer", "description": "Durasi geser dalam milidetik (default 300)"}
        },
        "required": ["x1", "y1", "x2", "y2"]
    },
    category="android"
)
def swipe(x1: float, y1: float, x2: float, y2: float, duration_ms: int = 300):
    return android_request("/action/swipe", {
        "x1": float(x1), "y1": float(y1),
        "x2": float(x2), "y2": float(y2),
        "duration_ms": int(duration_ms)
    })

@tool(
    name="type_text",
    description="Mengetik teks pada kolom input aktif di Android",
    schema={
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Teks yang ingin diketik"},
            "element_id": {"type": "string", "description": "Opsional ID elemen target"}
        },
        "required": ["text"]
    },
    category="android"
)
def type_text(text: str, element_id: str = None):
    body = {"text": text}
    if element_id:
        body["element_id"] = element_id
    return android_request("/action/type", body)

@tool(
    name="press_key",
    description="Menekan tombol sistem seperti BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
    schema={
        "type": "object",
        "properties": {
            "keycode": {"type": "string", "description": "Kode tombol: BACK, HOME, RECENTS, ENTER"}
        },
        "required": ["keycode"]
    },
    category="android"
)
def press_key(keycode: str):
    return android_request("/action/key", {"keycode": keycode})

@tool(
    name="screenshot",
    description="Mengambil tangkapan layar perangkat dalam format Base64",
    schema={"type": "object", "properties": {}},
    category="android"
)
def screenshot():
    return android_request("/screen/screenshot", method="GET")

@tool(
    name="send_notification",
    description="Mengirim notifikasi lokal ke status bar Android",
    schema={
        "type": "object",
        "properties": {
            "title": {"type": "string"},
            "message": {"type": "string"}
        },
        "required": ["title", "message"]
    },
    category="android"
)
def send_notification(title: str, message: str):
    return android_request("/notify", {"title": title, "message": message})
""".trimIndent()

    val termuxToolsPy: String = """
# ==========================================================
# Layer 2: Termux Native Shell & Hardware Telemetry Tools
# ==========================================================
import subprocess
import os
from tools import tool

@tool(
    name="shell",
    description="Mengeksekusi perintah bash / shell terminal di Termux secara langsung",
    schema={
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "Perintah bash yang akan dijalankan di terminal"}
        },
        "required": ["command"]
    },
    requires_confirmation=True,
    category="termux"
)
def shell(command: str):
    cmd_clean = command.strip()
    if not cmd_clean:
        return {"status": "error", "message": "Command cannot be empty"}
    
    try:
        res = subprocess.run(
            cmd_clean,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30
        )
        return {
            "status": "ok" if res.returncode == 0 else "error",
            "exit_code": res.returncode,
            "stdout": res.stdout,
            "stderr": res.stderr
        }
    except subprocess.TimeoutExpired:
        return {"status": "error", "error_code": "TIMEOUT", "message": "Command execution timed out (30s limit)"}
    except Exception as e:
        return {"status": "error", "error_code": "EXEC_ERROR", "message": str(e)}

@tool(
    name="get_battery",
    description="Mendapatkan persentase dan status pengisian baterai perangkat via Termux API",
    schema={"type": "object", "properties": {}},
    category="termux"
)
def get_battery():
    try:
        res = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=5)
        if res.returncode == 0:
            import json
            return {"status": "ok", "battery": json.loads(res.stdout.strip())}
    except Exception:
        pass
    from tools.android_tools import android_request
    return android_request("/system/battery", method="GET")

@tool(
    name="get_wifi",
    description="Mendapatkan status koneksi WiFi dan SSID via Termux API",
    schema={"type": "object", "properties": {}},
    category="termux"
)
def get_wifi():
    try:
        res = subprocess.run(["termux-wifi-connectioninfo"], capture_output=True, text=True, timeout=5)
        if res.returncode == 0:
            import json
            return {"status": "ok", "wifi": json.loads(res.stdout.strip())}
    except Exception:
        pass
    from tools.android_tools import android_request
    return android_request("/system/wifi", method="GET")

@tool(
    name="read_clipboard",
    description="Membaca teks yang sedang tersimpan di clipboard perangkat",
    schema={"type": "object", "properties": {}},
    category="termux"
)
def read_clipboard():
    try:
        res = subprocess.run(["termux-clipboard-get"], capture_output=True, text=True, timeout=5)
        if res.returncode == 0:
            return {"status": "ok", "text": res.stdout.strip()}
    except Exception:
        pass
    from tools.android_tools import android_request
    return android_request("/clipboard", method="GET")

@tool(
    name="write_clipboard",
    description="Menyalin teks ke clipboard perangkat",
    schema={
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Teks yang disalin"}
        },
        "required": ["text"]
    },
    category="termux"
)
def write_clipboard(text: str):
    try:
        res = subprocess.run(["termux-clipboard-set", text], capture_output=True, text=True, timeout=5)
        if res.returncode == 0:
            return {"status": "ok", "result": "Teks disalin ke clipboard via Termux"}
    except Exception:
        pass
    from tools.android_tools import android_request
    return android_request("/clipboard", {"text": text})

@tool(
    name="termux_vibrate",
    description="Menggetarkan perangkat HP dalam durasi milidetik tertentu",
    schema={
        "type": "object",
        "properties": {
            "duration_ms": {"type": "integer", "description": "Durasi getar dalam milidetik (misal: 500)"}
        }
    },
    category="termux"
)
def termux_vibrate(duration_ms: int = 500):
    try:
        subprocess.run(["termux-vibrate", "-d", str(duration_ms)], capture_output=True, timeout=5)
        return {"status": "ok", "message": f"HP bergetar selama {duration_ms}ms"}
    except Exception as e:
        return {"status": "error", "message": f"Termux-api vibrate error: {e}"}

@tool(
    name="termux_toast",
    description="Menampilkan notifikasi toast pop-up di layar HP via Termux API",
    schema={
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Pesan toast"}
        },
        "required": ["text"]
    },
    category="termux"
)
def termux_toast(text: str):
    try:
        subprocess.run(["termux-toast", text], capture_output=True, timeout=5)
        return {"status": "ok", "message": f"Toast ditampilkan: '{text}'"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@tool(
    name="termux_tts",
    description="Mengucapkan teks bersuara menggunakan Text-To-Speech HP",
    schema={
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Teks yang akan diucapkan"}
        },
        "required": ["text"]
    },
    category="termux"
)
def termux_tts(text: str):
    try:
        subprocess.run(["termux-tts-speak", text], capture_output=True, timeout=10)
        return {"status": "ok", "message": f"TTS diucapkan: '{text}'"}
    except Exception as e:
        return {"status": "error", "message": str(e)}
""".trimIndent()

    val customInitPy: String = """
# Directory for custom dynamic tools.
# Any python file added here containing @tool will be automatically discovered!
""".trimIndent()

    val customCekStoragePy: String = """
import shutil
from tools import tool

@tool(
    name="get_storage",
    description="Mendapatkan informasi kapasitas penyimpanan HP (total, used, free dalam GB)",
    schema={
        "type": "object",
        "properties": {}
    },
    category="custom"
)
def get_storage():
    total, used, free = shutil.disk_usage("/")
    return {
        "total_gb": round(total / 1024**3, 2),
        "used_gb": round(used / 1024**3, 2),
        "free_gb": round(free / 1024**3, 2)
    }
""".trimIndent()

    val customBukaYoutubePy: String = """
from tools import tool
from tools.android_tools import open_app, type_text
import time

@tool(
    name="buka_youtube",
    description="Membuka aplikasi YouTube dan mencari video tertentu jika query diberikan",
    schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "Kata kunci pencarian video YouTube opsional"}
        }
    },
    category="custom"
)
def buka_youtube(query: str = ""):
    res = open_app("com.google.android.youtube")
    if query:
        time.sleep(2)
        type_text(query)
        return {"status": "ok", "message": f"YouTube dibuka dan mencari: '{query}'"}
    return {"status": "ok", "message": "YouTube berhasil dibuka"}
""".trimIndent()

    val customCekRamPy: String = """
from tools import tool

@tool(
    name="cek_ram",
    description="Mengecek penggunaan memori RAM perangkat HP dalam MB",
    schema={"type": "object", "properties": {}},
    category="custom"
)
def cek_ram():
    mem = {}
    try:
        with open("/proc/meminfo", "r") as f:
            for line in f:
                parts = line.split(":")
                if len(parts) == 2:
                    mem[parts[0].strip()] = parts[1].strip()
        total_kb = int(mem.get("MemTotal", "0 kB").split()[0])
        avail_kb = int(mem.get("MemAvailable", "0 kB").split()[0])
        return {
            "total_mb": round(total_kb / 1024, 1),
            "available_mb": round(avail_kb / 1024, 1),
            "used_mb": round((total_kb - avail_kb) / 1024, 1)
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}
""".trimIndent()

    fun getConfigPy(token: String, port: Int): String {
        return """
# ==========================================================
# JARVIS-HP Config (Termux Side)
# API keys are securely stored here, NEVER embedded in APK!
# ==========================================================
import os

ANDROID_ENDPOINT = os.getenv("ANDROID_ENDPOINT", "http://127.0.0.1:$port")
LOCAL_TOKEN = os.getenv("LOCAL_TOKEN", "$token")

MAX_TOOL_CALLS = 20
REQUEST_TIMEOUT_SECONDS = 10

AI_PROVIDER = os.getenv("AI_PROVIDER", "gemini")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.0-flash")

SYSTEM_PROMPT = ""${'"'}
Kamu adalah JARVIS-HP, AI Agent otonom tingkat lanjut yang mengendalikan smartphone Android secara langsung lewat tools.

Arsitektur Tool:
1. Android Tools: open_app, tap, swipe, type_text, press_key, read_screen, screenshot.
2. Termux Tools: shell (menjalankan command terminal), get_battery, get_wifi, read_clipboard, write_clipboard, termux_vibrate, termux_tts, termux_toast.
3. Custom Tools (tools/custom/): get_storage, buka_youtube, cek_ram, dan tool lain yang ditambahkan user.

Aturan Operasional:
- Selalu panggil read_screen() sebelum tap untuk memastikan posisi elemen di layar.
- Untuk instruksi berurutan, jalankan langkah per langkah secara terstruktur.
- Jika tool mengembalikan error, baca pesan error lalu sesuaikan parameter sebelum mencoba ulang.
- Jawab secara ringkas, solutif, dan konfirmasikan hasil ke pengguna.
""${'"'}
""".trimIndent()
    }

    val memoryPy: String = """
# ==========================================================
# JARVIS-HP SQLite Memory & Action Logger
# ==========================================================
import sqlite3
import json
import time
import os

DB_PATH = os.path.expanduser("~/jarvis-hp/jarvis_memory.db")

def init_db():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute('''
        CREATE TABLE IF NOT EXISTS sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_instruction TEXT,
            status TEXT,
            started_at REAL,
            ended_at REAL
        )
    ''')
    cur.execute('''
        CREATE TABLE IF NOT EXISTS action_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER,
            tool_name TEXT,
            args_json TEXT,
            status TEXT,
            result_json TEXT,
            timestamp REAL
        )
    ''')
    conn.commit()
    conn.close()

def log_action(session_id, tool_name, args, status, result):
    try:
        conn = sqlite3.connect(DB_PATH)
        cur = conn.cursor()
        cur.execute('''
            INSERT INTO action_logs (session_id, tool_name, args_json, status, result_json, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (session_id, tool_name, json.dumps(args), status, json.dumps(result), time.time()))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"[Memory Error] {e}")

init_db()
""".trimIndent()

    val agentPy: String = """
# ==========================================================
# JARVIS-HP Autonomous Agent Engine
# Powered by Dynamic 3-Layer Tool Registry
# ==========================================================
import sys
import json
import re
import time
import requests
from config import (
    MAX_TOOL_CALLS, ANDROID_ENDPOINT,
    GEMINI_API_KEY, GEMINI_MODEL, SYSTEM_PROMPT
)
from tools import load_all_tools, execute_tool, get_tools_prompt_description, TOOLS
import memory

def run_agent_loop(user_instruction: str):
    # Initialize and scan all tools (Android + Termux + Custom)
    all_tools = load_all_tools()
    
    print("=" * 60)
    print("🤖 JARVIS-HP Autonomous Agent Started")
    print(f"📋 User Instruction: {user_instruction}")
    print(f"🛠️  Loaded Tools ({len(all_tools)} available):")
    for name, item in all_tools.items():
        cat = item.get("category", "custom")
        print(f"   [{cat.upper():7}] {name}: {item['description']}")
    print("=" * 60)
    
    tools_desc = get_tools_prompt_description()
    
    step_count = 0
    history = [
        {"role": "user", "parts": [{"text": f"{SYSTEM_PROMPT}\n\nAvailable Tools:\n{tools_desc}\n\nInstruksi user: {user_instruction}"}]}
    ]
    
    while step_count < MAX_TOOL_CALLS:
        step_count += 1
        print(f"\n▶ Step {step_count}/{MAX_TOOL_CALLS}")
        
        # Local Rule-based heuristic fallback if API Key is not yet configured
        if not GEMINI_API_KEY:
            print("ℹ️  No GEMINI_API_KEY set. Running smart autonomous heuristics...")
            cmd_lower = user_instruction.lower()
            
            if "storage" in cmd_lower or "penyimpanan" in cmd_lower:
                res = execute_tool("get_storage", {})
                print(f"📥 Tool result (get_storage): {json.dumps(res, indent=2)}")
                break
            elif "ram" in cmd_lower or "memori" in cmd_lower:
                res = execute_tool("cek_ram", {})
                print(f"📥 Tool result (cek_ram): {json.dumps(res, indent=2)}")
                break
            elif "youtube" in cmd_lower:
                if step_count == 1:
                    res = execute_tool("buka_youtube", {"query": "Minecraft"})
                    print(f"📥 Tool result: {res}")
                elif step_count == 2:
                    res = execute_tool("read_screen", {})
                    print(f"📥 Screen read: {res.get('status')}")
                    break
            elif "baterai" in cmd_lower or "battery" in cmd_lower:
                res = execute_tool("get_battery", {})
                print(f"📥 Battery: {res}")
                break
            elif "ls" in cmd_lower or "command" in cmd_lower or "terminal" in cmd_lower:
                res = execute_tool("shell", {"command": "ls -la"})
                print(f"📥 Shell output:\n{res.get('stdout') or res.get('stderr')}")
                break
            else:
                res = execute_tool("read_screen", {})
                print(f"📥 Default inspection: {res}")
                break
            continue
        
        # When GEMINI_API_KEY is configured: Full Gemini 2.0 Flash REST loop
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}"
        payload = {
            "contents": history,
            "generationConfig": {"temperature": 0.2}
        }
        
        try:
            resp = requests.post(url, json=payload, timeout=20)
            data = resp.json()
            candidate = data.get("candidates", [{}])[0].get("content", {})
            text_response = candidate.get("parts", [{}])[0].get("text", "")
            print(f"🧠 AI Thought/Response:\n{text_response}")
            
            # Look for JSON action block(s) — supports multiple tool calls per turn
            action_blocks = re.findall(r"```json:action([\s\S]*?)```", text_response)
            if action_blocks:
                # Parse every block: single object, batch object {"actions": [...]}, or raw array
                actions = []
                for raw_block in action_blocks:
                    try:
                        parsed = json.loads(raw_block.strip())
                    except Exception:
                        continue
                    if isinstance(parsed, list):
                        actions.extend([a for a in parsed if isinstance(a, dict)])
                    elif isinstance(parsed, dict):
                        batch = parsed.get("actions") or parsed.get("tools")
                        if isinstance(batch, list):
                            actions.extend([a for a in batch if isinstance(a, dict)])
                        else:
                            actions.append(parsed)
                actions = actions[:8]

                # Append model turn once, then execute all actions sequentially
                history.append({"role": "model", "parts": [{"text": text_response}]})

                result_lines = []
                for idx, action_data in enumerate(actions, start=1):
                    tool_name = action_data.get("tool")
                    tool_params = action_data.get("params", {})
                    if not tool_name:
                        result_lines.append(f"Action {idx}: skipped (missing 'tool' field)")
                        continue
                    label = f"Action {idx}/{len(actions)}" if len(actions) > 1 else "Action"
                    print(f"⚡ Executing selected tool [{label}]: {tool_name}({tool_params})")
                    exec_result = execute_tool(tool_name, tool_params)
                    print(f"📥 Execution Output: {json.dumps(exec_result)}")
                    result_lines.append(f"Action {idx} - tool '{tool_name}' result: {json.dumps(exec_result)}")

                # Send all results back so AI can evaluate each action outcome
                if result_lines:
                    history.append({"role": "user", "parts": [{"text": "\n".join(result_lines)}]})
                continue
            
            if "selesai" in text_response.lower() or "completed" in text_response.lower():
                print("🎯 Task confirmed completed by agent.")
                break
                
        except Exception as e:
            print(f"❌ AI loop error: {e}")
            break

    print("\n🏁 Agent session finished.")

if __name__ == "__main__":
    instruction = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else "get_storage"
    run_agent_loop(instruction)
""".trimIndent()

    val requirementsTxt: String = """
requests>=2.28.0
urllib3>=1.26.0
""".trimIndent()

    fun getSetupScript(token: String, port: Int): String {
        return """
#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
# JARVIS-HP 3-Layer Tool Registry Installer
# Generated automatically by JARVIS-HP Companion App
# ==========================================================

set -e
echo "=================================================="
echo "🤖 Installing JARVIS-HP Agent & Dynamic Tool Registry..."
echo "=================================================="

pkg update -y || true
pkg install -y python python-pip sqlite termux-api curl || true

PROJECT_DIR="${'$'}HOME/jarvis-hp"
mkdir -p "${'$'}PROJECT_DIR/tools/custom"
cd "${'$'}PROJECT_DIR"

echo "📥 Fetching core agent components from Companion App..."
curl -s "http://127.0.0.1:$port/termux/agent.py" -o agent.py
curl -s "http://127.0.0.1:$port/termux/config.py" -o config.py
curl -s "http://127.0.0.1:$port/termux/memory.py" -o memory.py
curl -s "http://127.0.0.1:$port/termux/INSTRUCTION.md" -o INSTRUCTION.md
curl -s "http://127.0.0.1:$port/termux/requirements.txt" -o requirements.txt

echo "📥 Fetching 3-Layer Tool Registry & Modular Tools..."
curl -s "http://127.0.0.1:$port/termux/tools/__init__.py" -o tools/__init__.py
curl -s "http://127.0.0.1:$port/termux/tools/android_tools.py" -o tools/android_tools.py
curl -s "http://127.0.0.1:$port/termux/tools/termux_tools.py" -o tools/termux_tools.py

echo "📥 Fetching Custom Tools samples (tools/custom/)..."
curl -s "http://127.0.0.1:$port/termux/tools/custom/__init__.py" -o tools/custom/__init__.py
curl -s "http://127.0.0.1:$port/termux/tools/custom/cek_storage.py" -o tools/custom/cek_storage.py
curl -s "http://127.0.0.1:$port/termux/tools/custom/buka_youtube.py" -o tools/custom/buka_youtube.py
curl -s "http://127.0.0.1:$port/termux/tools/custom/cek_ram.py" -o tools/custom/cek_ram.py

pip install -r requirements.txt || pip install requests

cat << 'EOF' > run.sh
#!/bin/bash
cd "${'$'}HOME/jarvis-hp"
python agent.py "$@"
EOF
chmod +x run.sh

echo "=================================================="
echo "✅ JARVIS-HP Agent & Tool Registry installed!"
echo "📁 Location: ${'$'}PROJECT_DIR"
echo "🛠️  Add new tools anytime in: ${'$'}PROJECT_DIR/tools/custom/"
echo "📖 Read guide: cat ${'$'}PROJECT_DIR/INSTRUCTION.md"
echo "🚀 Run example:"
echo "   cd ~/jarvis-hp"
echo "   python agent.py \"Cek kapasitas storage HP\""
echo "=================================================="
""".trimIndent()
    }
}
