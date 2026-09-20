package com.example.server

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuthManager — server OAuth 2.0 minimalis untuk klien MCP eksternal
 * (ChatGPT connectors, Claude, dll).
 *
 * Mendukung alur yang dibutuhkan spesifikasi MCP HTTP:
 * - Discovery metadata:  /.well-known/oauth-authorization-server (RFC 8414)
 * - Dynamic Client Registration (RFC 7591): POST /oauth/register
 * - Authorization Endpoint + PKCE (S256):  GET  /oauth/authorize
 * - Token Endpoint:                        POST /oauth/token
 *
 * Halaman persetujuan dirender sebagai HTML — pengguna menekan "IZINKAN"
 * langsung di HP (atau browser yang membuka URL tunnel), lalu klien menerima
 * authorization code via redirect. Access token bersifat in-memory (24 jam).
 */
object OAuthManager {

    private const val TAG = "OAuthManager"
    private const val CODE_TTL_MS = 10 * 60 * 1000L
    private const val TOKEN_TTL_MS = 24 * 60 * 60 * 1000L
    private val SCOPE = "mcp:tools"

    data class RegisteredClient(
        val clientName: String,
        val redirectUris: List<String>,
        val createdAt: Long
    )

    data class AuthCode(
        val clientId: String,
        val redirectUri: String,
        val codeChallenge: String?,
        val challengeMethod: String,
        val resource: String?,
        val createdAt: Long
    )

    data class AccessTokenInfo(val clientId: String, val expiresAt: Long)

    private val clients = ConcurrentHashMap<String, RegisteredClient>()
    private val codes = ConcurrentHashMap<String, AuthCode>()
    private val tokens = ConcurrentHashMap<String, AccessTokenInfo>()
    private val pendingAuths = ConcurrentHashMap<String, PendingAuth>()

    data class PendingAuth(
        val clientId: String,
        val clientName: String,
        val redirectUri: String,
        val state: String?,
        val codeChallenge: String?,
        val challengeMethod: String,
        val resource: String?
    )

    // ==========================================================
    // Validasi token untuk route terproteksi (/mcp)
    // ==========================================================

    fun isValidAccessToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val info = tokens[token] ?: return false
        if (System.currentTimeMillis() > info.expiresAt) {
            tokens.remove(token)
            return false
        }
        return true
    }

    // ==========================================================
    // 1. Metadata (RFC 8414)
    // ==========================================================

    /**
     * Deteksi scheme (http/https). Cloudflared & proxy umumnya mengirim
     * X-Forwarded-Proto — dipakai agar URL metadata menjadi https saat lewat tunnel.
     */
    fun detectScheme(headers: Map<String, String>): String {
        val proto = headers["x-forwarded-proto"] ?: headers["X-Forwarded-Proto"]
        return proto?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "http"
    }

    /** host diambil dari header "host" request agar sesuai domain tunnel/LAN klien. */
    fun metadata(host: String, scheme: String = "http"): JSONObject {
        val base = "$scheme://$host"
        return JSONObject().apply {
            put("issuer", base)
            put("authorization_endpoint", "$base/oauth/authorize")
            put("token_endpoint", "$base/oauth/token")
            put("registration_endpoint", "$base/oauth/register")
            put("response_types_supported", JSONArray().put("code"))
            put("grant_types_supported", JSONArray().put("authorization_code"))
            put("code_challenge_methods_supported", JSONArray().put("S256").put("plain"))
            put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
            put("scopes_supported", JSONArray().put(SCOPE))
        }
    }

    /**
     * RFC 9728 — Protected Resource Metadata untuk /mcp.
     * Dipakai klien (ChatGPT/Claude) menemukan authorization server kita.
     */
    fun protectedResourceMetadata(host: String, scheme: String = "http"): JSONObject = JSONObject().apply {
        put("resource", "$scheme://$host/mcp")
        put("authorization_servers", JSONArray().put("$scheme://$host"))
        put("scopes_supported", JSONArray().put(SCOPE))
        put("bearer_methods_supported", JSONArray().put("header"))
    }

    // ==========================================================
    // 2. Dynamic Client Registration (RFC 7591)
    // ==========================================================

    /** @return HTTP status + body JSON */
    fun registerClient(body: String): Pair<Int, JSONObject> {
        val json: JSONObject? = try {
            JSONObject(body)
        } catch (_: Exception) {
            // fallback form-encoded
            try {
                val form = parseForm(body)
                JSONObject().put("redirect_uris", JSONArray().put(form["redirect_uri"] ?: ""))
            } catch (_: Exception) {
                null
            }
        }

        val urisJson = json?.optJSONArray("redirect_uris")
        val uris = mutableListOf<String>()
        if (urisJson != null) {
            for (i in 0 until urisJson.length()) {
                val u = urisJson.optString(i).trim()
                if (u.isNotEmpty()) uris.add(u)
            }
        }
        if (uris.isEmpty()) {
            return 400 to JSONObject().apply {
                put("error", "invalid_client_metadata")
                put("error_description", "redirect_uris wajib diisi")
            }
        }

        val clientId = "ac_" + UUID.randomUUID().toString().replace("-", "").take(24)
        val clientName = json?.optString("client_name", "MCP Client")?.ifBlank { "MCP Client" } ?: "MCP Client"
        clients[clientId] = RegisteredClient(clientName, uris, System.currentTimeMillis())
        Log.i(TAG, "OAuth DCR: '$clientName' terdaftar sebagai $clientId, redirect=$uris")

        return 201 to JSONObject().apply {
            put("client_id", clientId)
            put("client_name", clientName)
            put("redirect_uris", JSONArray(uris))
            put("grant_types", JSONArray().put("authorization_code"))
            put("response_types", JSONArray().put("code"))
            put("token_endpoint_auth_method", "none")
            put("scope", SCOPE)
        }
    }

    // ==========================================================
    // 3. Authorization Endpoint (+ halaman persetujuan)
    // ==========================================================

    /** @return HTML halaman persetujuan, atau (kode error, pesan). */
    fun createAuthorizePage(query: String?): Pair<Int, Any> {
        val p = parseQuery(query ?: "")
        val clientId = p["client_id"] ?: ""
        val redirectUri = p["redirect_uri"] ?: ""
        val responseType = p["response_type"] ?: ""
        val state = p["state"]
        val challenge = p["code_challenge"]
        val method = p["code_challenge_method"] ?: "plain"
        val resource = p["resource"]
        val scope = p["scope"] ?: SCOPE

        val client = clients[clientId]
        if (client == null) {
            return 400 to errorHtml("client_id tidak dikenal. Klien harus mendaftar dulu via /oauth/register.")
        }
        if (redirectUri !in client.redirectUris) {
            return 400 to errorHtml("redirect_uri tidak terdaftar untuk klien ini.")
        }
        if (responseType != "code") {
            return 400 to errorHtml("response_type harus 'code'.")
        }

        val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
        pendingAuths[reqId] = PendingAuth(
            clientId = clientId,
            clientName = client.clientName,
            redirectUri = redirectUri,
            state = state,
            codeChallenge = challenge,
            challengeMethod = method,
            resource = resource
        )

        val allowUrl = "/oauth/authorize/decision?req=$reqId&decision=allow"
        val denyUrl = "/oauth/authorize/decision?req=$reqId&decision=deny"
        val html = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Andra Control — Izin Akses</title>
<style>
 body{margin:0;background:#05060e;color:#f5f7ff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:20px;box-sizing:border-box}
 .card{max-width:420px;width:100%;background:#0c1122;border:1px solid #273052;border-radius:20px;padding:28px;text-align:center}
 .logo{width:64px;height:64px;margin:0 auto 16px;border-radius:50%;background:#0d1226;border:1px solid #3ae1fa;display:flex;align-items:center;justify-content:center;font-size:30px}
 h1{font-size:18px;margin:0 0 6px}
 p{font-size:13px;color:#8f97b8;line-height:1.6;margin:8px 0}
 .scope{background:#151b33;border:1px solid #273052;border-radius:10px;padding:10px;font-size:12px;color:#3ae1fa;margin:14px 0}
 .btn{display:block;padding:13px;border-radius:12px;font-weight:700;font-size:14px;text-decoration:none;margin-top:10px}
 .allow{background:linear-gradient(90deg,#3ae1fa,#b48cff);color:#04222b}
 .deny{background:transparent;border:1px solid #273052;color:#8f97b8}
 .foot{font-size:10px;color:#3d4568;margin-top:16px}
</style></head>
<body><div class="card">
 <div class="logo">🪐</div>
 <h1>Izin Koneksi MCP</h1>
 <p><b>${client.clientName}</b> ingin terhubung ke <b>Andra Control</b> di perangkat Android Anda dan mengendalikan tools-nya.</p>
 <div class="scope">Ruang lingkup: $scope<br><span style="font-size:10px;color:#8f97b8">(buka aplikasi, tap/swipe layar, shell, termux, decode gambar, dll)</span></div>
 <a class="btn allow" href="$allowUrl">IZINKAN</a>
 <a class="btn deny" href="$denyUrl">Tolak</a>
 <p class="foot">Hanya izinkan bila Anda memang menghubungkan AI ini sendiri.</p>
</div></body></html>
        """.trimIndent()
        return 200 to html
    }

    /** Keputusan pengguna. @return Triple(status, location|body, isRedirect) */
    fun decideAuthorization(reqId: String?, allow: Boolean): Triple<Int, String, Boolean> {
        val pending = reqId?.let { pendingAuths.remove(it) }
            ?: return Triple(400, errorHtml("Permintaan otorisasi tidak ditemukan / kedaluwarsa. Ulangi koneksi dari klien."), false)

        return if (allow) {
            val code = UUID.randomUUID().toString().replace("-", "")
            codes[code] = AuthCode(
                clientId = pending.clientId,
                redirectUri = pending.redirectUri,
                codeChallenge = pending.codeChallenge,
                challengeMethod = pending.challengeMethod,
                resource = pending.resource,
                createdAt = System.currentTimeMillis()
            )
            val sep = if (pending.redirectUri.contains("?")) "&" else "?"
            var location = "${pending.redirectUri}${sep}code=$code"
            pending.state?.let { location += "&state=${it}" }
            pending.resource?.let { location += "&resource=${it}" }
            Log.i(TAG, "OAuth: kode otorisasi diterbitkan untuk ${pending.clientName}")
            Triple(302, location, true)
        } else {
            val sep = if (pending.redirectUri.contains("?")) "&" else "?"
            var location = "${pending.redirectUri}${sep}error=access_denied"
            pending.state?.let { location += "&state=${it}" }
            Triple(302, location, true)
        }
    }

    // ==========================================================
    // 4. Token Endpoint
    // ==========================================================

    /** @return HTTP status + body JSON */
    fun exchangeToken(body: String): Pair<Int, JSONObject> {
        val form: Map<String, String> = if (body.trimStart().startsWith("{")) {
            try {
                val j = JSONObject(body)
                j.keys().asSequence().associateWith { j.optString(it) }
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            parseForm(body)
        }

        if (form["grant_type"] != "authorization_code") {
            return 400 to oauthError("unsupported_grant_type")
        }
        val code = form["code"] ?: return 400 to oauthError("invalid_request", "code wajib")
        val stored = codes[code]
            ?: return 400 to oauthError("invalid_grant", "Kode tidak dikenal / kedaluwarsa")
        codes.remove(code) // sekali pakai

        if (System.currentTimeMillis() - stored.createdAt > CODE_TTL_MS) {
            return 400 to oauthError("invalid_grant", "Kode kedaluwarsa")
        }
        val redirectUri = form["redirect_uri"] ?: ""
        if (redirectUri.isNotEmpty() && redirectUri != stored.redirectUri) {
            return 400 to oauthError("invalid_grant", "redirect_uri tidak cocok")
        }
        val verifier = form["code_verifier"]
        if (stored.codeChallenge != null) {
            if (verifier == null || !verifyPkce(stored.codeChallenge, stored.challengeMethod, verifier)) {
                return 400 to oauthError("invalid_grant", "Verifikasi PKCE gagal")
            }
        }

        val token = "at_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").take(16)
        tokens[token] = AccessTokenInfo(stored.clientId, System.currentTimeMillis() + TOKEN_TTL_MS)
        Log.i(TAG, "OAuth: access token diterbitkan untuk ${stored.clientId} (berlaku 24 jam)")

        val resp = JSONObject().apply {
            put("access_token", token)
            put("token_type", "bearer")
            put("expires_in", TOKEN_TTL_MS / 1000)
            put("scope", SCOPE)
        }
        stored.resource?.let { resp.put("resource", it) }
        return 200 to resp
    }

    // ==========================================================
    // Helpers
    // ==========================================================

    private fun verifyPkce(challenge: String, method: String, verifier: String): Boolean {
        return if (method.equals("S256", ignoreCase = true)) {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val computed = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            computed == challenge
        } else {
            verifier == challenge
        }
    }

    private fun oauthError(code: String, description: String? = null): JSONObject = JSONObject().apply {
        put("error", code)
        if (description != null) put("error_description", description)
    }

    fun parseQuery(query: String): Map<String, String> = parseForm(query)

    private fun parseForm(body: String): Map<String, String> {
        val result = HashMap<String, String>()
        body.split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            val key = if (idx < 0) pair else pair.substring(0, idx)
            val value = if (idx < 0) "" else pair.substring(idx + 1)
            try {
                result[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                result[key] = value
            }
        }
        return result
    }

    private fun errorHtml(message: String): String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Andra Control — Error</title></head>
<body style="margin:0;background:#05060e;color:#fb7185;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh">
<div style="text-align:center;padding:30px"><div style="font-size:40px">⚠️</div><p style="color:#8f97b8;font-size:13px">$message</p></div>
</body></html>
    """.trimIndent()
}
