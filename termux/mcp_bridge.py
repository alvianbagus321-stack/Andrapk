#!/usr/bin/env python3
# ============================================================
# Andra Control — MCP Bridge (untuk APK versi lama tanpa /mcp)
#
# MCP server mini yang berjalan di Termux (port 9000) dan
# meneruskan eksekusi tool ke HTTP API aplikasi (port 8765).
# Python stdlib only — TIDAK perlu pip install apa pun.
#
# Pakai:
#   1. Edit APP_TOKEN di bawah (lihat Dashboard > Kredensial Lokal)
#   2. python ~/mcp/mcp_bridge.py
#   3. Terminal lain: cloudflared tunnel --url http://127.0.0.1:9000
#   4. Daftarkan https://xxxx.trycloudflare.com/mcp di ChatGPT/Claude
# ============================================================
import json
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_BASE = "http://127.0.0.1:8765"
APP_TOKEN = "GANTI_TOKEN_KAMU"   # <-- WAJIB DIGANTI (Dashboard > Kredensial Lokal)
BRIDGE_PORT = 9000
MAX_TEXT = 8000


def app_request(path, method="GET", payload=None):
    """Panggil HTTP API aplikasi Andra Control."""
    req = urllib.request.Request(APP_BASE + path, method=method)
    req.add_header("X-Local-Token", APP_TOKEN)
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            raw = e.read().decode("utf-8", "replace")
        except Exception:
            raw = "{}"
    except Exception as e:
        return {"status": "error", "message": f"Gagal menghubungi app: {e}"}
    try:
        return json.loads(raw)
    except Exception:
        return {"status": "ok", "raw": raw[:MAX_TEXT]}


def call_result(data):
    status = data.get("status")
    text = data.get("result") or data.get("message") or json.dumps(data, ensure_ascii=False)
    return {
        "content": [{"type": "text", "text": str(text)[:MAX_TEXT]}],
        "isError": status == "error",
    }


TOOLS = [
    {
        "name": "open_app",
        "description": "Membuka aplikasi di HP pengguna",
        "inputSchema": {"type": "object", "properties": {"package_name": {"type": "string", "description": "contoh: com.whatsapp"}}, "required": ["package_name"]},
        "exec": lambda a: call_result(app_request("/app/open", "POST", {"package_name": a.get("package_name", "")})),
    },
    {
        "name": "tap",
        "description": "Tap pada koordinat layar (x, y pixel)",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}}, "required": ["x", "y"]},
        "exec": lambda a: call_result(app_request("/action/tap", "POST", a)),
    },
    {
        "name": "swipe",
        "description": "Swipe/scroll layar dari (x1,y1) ke (x2,y2)",
        "inputSchema": {"type": "object", "properties": {"x1": {"type": "number"}, "y1": {"type": "number"}, "x2": {"type": "number"}, "y2": {"type": "number"}, "duration_ms": {"type": "number"}}},
        "exec": lambda a: call_result(app_request("/action/swipe", "POST", a)),
    },
    {
        "name": "type_text",
        "description": "Mengetik teks ke kolom input yang aktif",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "element_id": {"type": "string"}}, "required": ["text"]},
        "exec": lambda a: call_result(app_request("/action/type", "POST", a)),
    },
    {
        "name": "press_key",
        "description": "Menekan tombol sistem: BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
        "inputSchema": {"type": "object", "properties": {"keycode": {"type": "string"}}, "required": ["keycode"]},
        "exec": lambda a: call_result(app_request("/action/key", "POST", {"keycode": a.get("keycode", "BACK")})),
    },
    {
        "name": "read_screen",
        "description": "Membaca semua elemen UI yang tampil di layar (teks, id, posisi)",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: call_result(app_request("/screen/elements", "GET")),
    },
    {
        "name": "screenshot",
        "description": "Mengambil tangkapan layar (dihasilkan base64, dipotong agar muat teks)",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: call_result(app_request("/screen/screenshot", "GET")),
    },
    {
        "name": "battery",
        "description": "Cek status baterai HP",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: call_result(app_request("/system/battery", "GET")),
    },
    {
        "name": "clipboard_get",
        "description": "Membaca isi clipboard HP",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: call_result(app_request("/clipboard", "GET")),
    },
    {
        "name": "clipboard_set",
        "description": "Menulis teks ke clipboard HP",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
        "exec": lambda a: call_result(app_request("/clipboard", "POST", {"text": a.get("text", "")})),
    },
    {
        "name": "notify",
        "description": "Mengirim notifikasi lokal ke HP",
        "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "message": {"type": "string"}}, "required": ["title", "message"]},
        "exec": lambda a: call_result(app_request("/notify", "POST", {"title": a.get("title", ""), "message": a.get("message", "")})),
    },
    {
        "name": "shell",
        "description": "Menjalankan perintah shell/ADB di HP (butuh Shizuku/ADB aktif)",
        "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]},
        "exec": lambda a: call_result(app_request("/adb/shell", "POST", {"command": a.get("command", "")})),
    },
    {
        "name": "app_status",
        "description": "Cek status server & layanan aplikasi Andra Control",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: call_result(app_request("/status", "GET")),
    },
]

SERVER_INFO = {"name": "andra-mcp-bridge", "title": "Andra Control (Termux Bridge)", "version": "1.0.0"}


def handle_request(req):
    method = req.get("method", "")
    if method == "initialize":
        params = req.get("params") or {}
        return {
            "protocolVersion": params.get("protocolVersion", "2025-03-26"),
            "capabilities": {"tools": {}},
            "serverInfo": SERVER_INFO,
            "instructions": "MCP bridge ke HP Android melalui app Andra Control. Gunakan tools/list untuk melihat tool otomasi yang tersedia (buka app, tap, swipe, ketik, shell, dsb).",
        }
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": [{k: t[k] for k in ("name", "description", "inputSchema")} for t in TOOLS]}
    if method == "tools/call":
        params = req.get("params") or {}
        name = params.get("name", "")
        args = params.get("arguments") or {}
        tool = next((t for t in TOOLS if t["name"] == name), None)
        if tool is None:
            return {"content": [{"type": "text", "text": f"Tool '{name}' tidak dikenal"}], "isError": True}
        return tool["exec"](args)
    if method == "resources/list":
        return {"resources": []}
    if method == "prompts/list":
        return {"prompts": []}
    raise ValueError(f"Method tidak dikenal: {method}")


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, obj=None):
        if obj is None:
            self.send_response(code)
            self.end_headers()
            return
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        try:
            req = json.loads(body)
        except Exception:
            self._reply(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            return
        if "id" not in req or str(req.get("method", "")).startswith("notifications/"):
            self._reply(202)
            return
        try:
            result = handle_request(req)
        except Exception as e:
            self._reply(200, {"jsonrpc": "2.0", "id": req.get("id"), "error": {"code": -32601, "message": str(e)}})
            return
        self._reply(200, {"jsonrpc": "2.0", "id": req.get("id"), "result": result})

    def do_GET(self):
        self._reply(405, {"error": "Gunakan POST (MCP Streamable HTTP stateless)"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("=" * 52)
    print("🌉 Andra MCP Bridge (untuk APK lama)")
    print(f"   App target : {APP_BASE}")
    print(f"   Bridge     : http://127.0.0.1:{BRIDGE_PORT}/mcp")
    print(f"   Tunnel     : cloudflared tunnel --url http://127.0.0.1:{BRIDGE_PORT}")
    print("=" * 52)

    print("🔍 Self-test koneksi ke app...")
    st = app_request("/status", "GET")
    if isinstance(st, dict) and st.get("error_code") == "UNAUTHORIZED":
        print("❌ TOKEN SALAH! Edit APP_TOKEN di file ini (Dashboard > Kredensial Lokal), lalu jalankan ulang.")
    elif isinstance(st, dict) and st.get("status") == "ok":
        print("✅ App terhubung — siap melayani MCP")
    else:
        print("⚠️ App belum merespons — buka app & pastikan toggle server ONLINE (port 8765).")

    ThreadingHTTPServer(("127.0.0.1", BRIDGE_PORT), Handler).serve_forever()
