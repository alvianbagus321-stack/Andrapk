#!/usr/bin/env python3
# ============================================================
# Andra Control — MCP Bridge v2 (untuk APK versi lama tanpa
# rantai discovery OAuth lengkap)
#
# MCP server mini di Termux (port 9000) yang:
#   - Mengiklankan discovery OAuth standar (401 + WWW-Authenticate,
#     RFC 9728 protected-resource metadata, RFC 8414 auth-server
#     metadata) sehingga ChatGPT/Claude mengenalinya
#   - Mem-proxy OAuth (register/authorize/token) ke app Andra Control
#     — halaman izin (IZINKAN) tetap dari app, token diterbitkan app
#   - Mem-proxy tools/call ke HTTP API app (X-Local-Token internal)
#   - Menolak /mcp tanpa OAuth token yang valid (diverifikasi ke app)
#
# Python stdlib only — tidak perlu pip install apa pun.
#
# Pakai:
#   1. Edit APP_TOKEN di bawah (Dashboard > Kredensial Lokal)
#   2. python ~/mcp/mcp_bridge.py
#   3. Terminal lain: cloudflared tunnel --url http://127.0.0.1:9000
#   4. Daftarkan https://xxxx.trycloudflare.com/mcp di ChatGPT/Claude
# ============================================================
import http.client
import json
import urllib.request
import urllib.error
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_BASE = "http://127.0.0.1:8765"
APP_TOKEN = "GANTI_TOKEN_KAMU"   # <-- WAJIB DIGANTI (Dashboard > Kredensial Lokal)
BRIDGE_PORT = 9000
SCOPE = "mcp:tools"
MAX_TEXT = 8000

TOOLS = [
    {
        "name": "open_app",
        "description": "Membuka aplikasi di HP pengguna",
        "inputSchema": {"type": "object", "properties": {"package_name": {"type": "string", "description": "contoh: com.whatsapp"}}, "required": ["package_name"]},
        "exec": lambda a: forward_action("/app/open", a),
    },
    {
        "name": "tap",
        "description": "Tap pada koordinat layar (x, y pixel)",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}}, "required": ["x", "y"]},
        "exec": lambda a: forward_action("/action/tap", a),
    },
    {
        "name": "swipe",
        "description": "Swipe/scroll layar dari (x1,y1) ke (x2,y2)",
        "inputSchema": {"type": "object", "properties": {"x1": {"type": "number"}, "y1": {"type": "number"}, "x2": {"type": "number"}, "y2": {"type": "number"}, "duration_ms": {"type": "number"}}},
        "exec": lambda a: forward_action("/action/swipe", a),
    },
    {
        "name": "type_text",
        "description": "Mengetik teks ke kolom input yang aktif",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "element_id": {"type": "string"}}, "required": ["text"]},
        "exec": lambda a: forward_action("/action/type", a),
    },
    {
        "name": "press_key",
        "description": "Menekan tombol sistem: BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
        "inputSchema": {"type": "object", "properties": {"keycode": {"type": "string"}}, "required": ["keycode"]},
        "exec": lambda a: forward_action("/action/key", {"keycode": a.get("keycode", "BACK")}),
    },
    {
        "name": "read_screen",
        "description": "Membaca semua elemen UI yang tampil di layar (teks, id, posisi)",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: forward_action("/screen/elements", None, "GET"),
    },
    {
        "name": "screenshot",
        "description": "Mengambil tangkapan layar HP (base64, dipotong agar muat)",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: forward_action("/screen/screenshot", None, "GET"),
    },
    {
        "name": "battery",
        "description": "Cek status baterai HP",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: forward_action("/system/battery", None, "GET"),
    },
    {
        "name": "clipboard_get",
        "description": "Membaca isi clipboard HP",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: forward_action("/clipboard", None, "GET"),
    },
    {
        "name": "clipboard_set",
        "description": "Menulis teks ke clipboard HP",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
        "exec": lambda a: forward_action("/clipboard", {"text": a.get("text", "")}),
    },
    {
        "name": "notify",
        "description": "Mengirim notifikasi lokal ke HP",
        "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "message": {"type": "string"}}, "required": ["title", "message"]},
        "exec": lambda a: forward_action("/notify", {"title": a.get("title", ""), "message": a.get("message", "")}),
    },
    {
        "name": "shell",
        "description": "Menjalankan perintah shell/ADB di HP (butuh Shizuku/ADB aktif)",
        "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]},
        "exec": lambda a: forward_action("/adb/shell", {"command": a.get("command", "")}),
    },
    {
        "name": "wifi_status",
        "description": "Cek status koneksi WiFi HP",
        "inputSchema": {"type": "object", "properties": {}},
        "exec": lambda a: forward_action("/system/wifi", None, "GET"),
    },
]

SERVER_INFO = {"name": "andra-mcp-bridge", "title": "Andra Control (Termux Bridge)", "version": "2.0.0"}


# ============================================================
# HTTP helper ke app
# ============================================================

def app_request_raw(path, method="GET", payload=None, auth_header=None):
    """Panggil app. auth_header: None (pakai APP_TOKEN) atau string Bearer utk verifikasi OAuth."""
    req = urllib.request.Request(APP_BASE + path, method=method)
    if auth_header:
        req.add_header("Authorization", auth_header)
    else:
        req.add_header("X-Local-Token", APP_TOKEN)
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception:
            return e.code, "{}"
    except Exception as e:
        return 0, json.dumps({"status": "error", "message": f"Gagal menghubungi app: {e}"})


def forward_action(path, payload=None, method=None):
    status, raw = app_request_raw(path, method or ("POST" if payload is not None else "GET"), payload)
    try:
        data = json.loads(raw)
        status_field = data.get("status")
        text = data.get("result") or data.get("message") or json.dumps(data, ensure_ascii=False)
        return {"content": [{"type": "text", "text": str(text)[:MAX_TEXT]}], "isError": status_field == "error"}
    except Exception:
        return {"content": [{"type": "text", "text": raw[:MAX_TEXT]}], "isError": status != 200}


def is_valid_oauth_token(auth_header):
    """Verifikasi Bearer token ke app: kalau diterima di /status berarti token OAuth valid."""
    if not auth_header or not auth_header.lower().startswith("bearer "):
        return False
    status, raw = app_request_raw("/status", "GET", None, auth_header.strip())
    try:
        data = json.loads(raw)
        return status == 200 and data.get("status") == "ok"
    except Exception:
        return False


# ============================================================
# MCP handling
# ============================================================

def handle_mcp(req):
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
    server_version = "AndraBridge/2.0"

    # ---------- helpers ----------
    def _base_url(self):
        host = self.headers.get("Host", f"127.0.0.1:{BRIDGE_PORT}")
        proto = (self.headers.get("X-Forwarded-Proto") or "http").split(",")[0].strip()
        return f"{proto}://{host}"

    def _reply(self, code, obj=None, content_type="application/json", extra=None):
        body = b"" if obj is None else (obj if isinstance(obj, bytes) else json.dumps(obj).encode("utf-8"))
        self.send_response(code)
        if obj is not None:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Local-Token, MCP-Protocol-Version")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _proxy(self, app_path, method):
        """Teruskan request apa adanya ke app (OAuth endpoints) via http.client
        mentah — redirect TIDAK diikuti, header Location diteruskan ke klien."""
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length) if length else None
        parts = urllib.parse.urlsplit(APP_BASE)
        conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=25)
        headers = {}
        ct = self.headers.get("Content-Type")
        if ct:
            headers["Content-Type"] = ct
        try:
            conn.request(method, app_path, body=body, headers=headers)
            resp = conn.getresponse()
            raw = resp.read()
            loc = resp.getheader("Location")
            extra = {"Location": loc} if loc else None
            self._reply(resp.status, raw, resp.getheader("Content-Type", "application/json"), extra=extra)
        except Exception as e:
            self._reply(502, json.dumps({"error": f"bridge: gagal menghubungi app: {e}"}).encode())
        finally:
            conn.close()

    # ---------- GET ----------
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        base = self._base_url()

        if path == "/.well-known/oauth-protected-resource" or path == "/.well-known/oauth-protected-resource/mcp":
            self._reply(200, {
                "resource": f"{base}/mcp",
                "authorization_servers": [base],
                "scopes_supported": [SCOPE],
                "bearer_methods_supported": ["header"],
            })
            return

        if path == "/.well-known/oauth-authorization-server":
            self._reply(200, {
                "issuer": base,
                "authorization_endpoint": f"{base}/oauth/authorize",
                "token_endpoint": f"{base}/oauth/token",
                "registration_endpoint": f"{base}/oauth/register",
                "response_types_supported": ["code"],
                "grant_types_supported": ["authorization_code"],
                "code_challenge_methods_supported": ["S256", "plain"],
                "token_endpoint_auth_methods_supported": ["none", "client_secret_post"],
                "scopes_supported": [SCOPE],
            })
            return

        if path.startswith("/oauth/"):
            self._proxy(self.path, "GET")
            return

        self._reply(405, {"error": "Gunakan POST (MCP Streamable HTTP stateless)"})

    # ---------- POST ----------
    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/oauth/register" or path == "/oauth/token":
            self._proxy(self.path, "POST")
            return

        if path != "/mcp":
            self._reply(404, {"error": "Endpoint tidak dikenal"})
            return

        # ---- /mcp: wajib OAuth token valid (diverifikasi ke app) ----
        auth = self.headers.get("Authorization")
        if not is_valid_oauth_token(auth):
            base = self._base_url()
            self._reply(401, {"jsonrpc": "2.0", "id": None, "error": {"code": -32001, "message": "Unauthorized: OAuth required"}},
                        extra={"WWW-Authenticate": f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource/mcp"'})
            return

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
            result = handle_mcp(req)
        except Exception as e:
            self._reply(200, {"jsonrpc": "2.0", "id": req.get("id"), "error": {"code": -32601, "message": str(e)}})
            return
        self._reply(200, {"jsonrpc": "2.0", "id": req.get("id"), "result": result})

    def do_OPTIONS(self):
        self._reply(204)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("=" * 52)
    print("🌉 Andra MCP Bridge v2 (dengan discovery OAuth)")
    print(f"   App target : {APP_BASE}")
    print(f"   Bridge     : http://127.0.0.1:{BRIDGE_PORT}/mcp")
    print(f"   Tunnel     : cloudflared tunnel --url http://127.0.0.1:{BRIDGE_PORT}")
    print("=" * 52)

    print("🔍 Self-test koneksi ke app...")
    st, raw = app_request_raw("/status", "GET")
    try:
        data = json.loads(raw)
    except Exception:
        data = {}
    if st == 401 or data.get("error_code") == "UNAUTHORIZED":
        print("❌ TOKEN SALAH! Edit APP_TOKEN di file ini (Dashboard > Kredensial Lokal), lalu jalankan ulang.")
    elif st == 200 and data.get("status") == "ok":
        print("✅ App terhubung — siap melayani MCP (OAuth discovery aktif)")
    else:
        print("⚠️ App belum merespons — buka app & pastikan toggle server ONLINE (port 8765).")

    ThreadingHTTPServer(("127.0.0.1", BRIDGE_PORT), Handler).serve_forever()
