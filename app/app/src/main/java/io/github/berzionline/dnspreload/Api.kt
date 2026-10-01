package io.github.berzionline.dnspreload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin client for the DNSpreload API; parsing only. */
data class Device(val ip: String, val name: String, val icon: String)
data class Switch(val name: String, val label: String, val scope: String, val kind: String, val on: Boolean, val until: Long?, val presets: List<Int>, val devices: List<Device> = emptyList())
data class Status(val unboundUp: Boolean, val ftlActive: Boolean, val digMs: Double?, val cache: Long, val rrset: Long, val failed: Int, val uptimeH: Double?)
data class Stats(val piholeQueries: Long, val piholeBlocked: Long, val piholePercent: Double, val piholeClients: Int,
                 val speed: List<Pair<String, Double>>, val lists: Map<String, Long>, val lastRun: LastRun?, val history: List<Pair<String, Long>>)
data class LastRun(val mode: String, val time: String, val domains: Long, val durationS: Double)
data class Token(val id: String, val role: String, val label: String, val token: String?, val created: Long?, val lastSeen: Long?)
data class Review(val purpose: String, val category: String, val ifBlocked: String, val recommendation: String, val confidence: Double, val reason: String, val model: String, val revisions: Int, val ts: Long?, val deviceNote: String = "")
/** A device that asked for a candidate domain; name comes from Pi-hole, may be empty. */
data class Asker(val ip: String, val name: String, val queries: Int) {
    val label: String get() = name.trim().takeIf { it.isNotEmpty() && !Regex("[0-9.:a-f]+").matches(it) }?.substringBefore('.') ?: ip
}
data class Candidate(val domain: String, val kind: String, val score: Int, val why: List<String>, val clients: Int, val queries: Int, val review: Review?, val devices: List<Asker> = emptyList())
data class Blocking(val over: List<Candidate>, val under: List<Candidate>, val time: Long?, val reviewedAt: Long?, val pending: Int, val dropped: Int)

// UI callers resolve this resource using their current Android context.
class ApiError(val messageRes: Int, val code: Int = 0) : Exception()

class Api(baseUrl: String, private val token: String?, client: OkHttpClient? = null) {
    val base = baseUrl.trimEnd('/')
    private val http = client ?: OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String) = Request.Builder().url(base + path).apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private fun get(path: String): JSONObject = exec(req(path).get().build())
    private fun post(path: String, body: JSONObject): JSONObject = exec(req(path).post(body.toString().toRequestBody(json)).build())
    private fun exec(r: Request): JSONObject {
        http.newCall(r).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(when (resp.code) { 401 -> R.string.unauthorized; 403 -> R.string.forbidden; else -> R.string.server_error }, resp.code)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** Role and visible switches; the only request needed for family tokens. */
    fun switches(): Pair<String, List<Switch>> {
        val o = get("/api/v1/switches")
        val arr = o.optJSONArray("switches") ?: JSONArray()
        return o.optString("role", "?") to (0 until arr.length()).map { parseSwitch(arr.getJSONObject(it)) }
    }
    fun setSwitch(name: String, on: Boolean, minutes: Int = 30): Switch? {
        val body = JSONObject().put("action", if (on) "on" else "off").apply { if (on) put("minutes", minutes) }
        val o = post("/api/v1/switch/$name", body)
        if (!o.optBoolean("ok")) throw ApiError(R.string.switch_rejected)
        return o.optJSONObject("switch")?.let { parseSwitch(it) }
    }
    fun status(): Status { val o = get("/api/v1/status"); return Status(o.optBoolean("unbound_up"), o.optBoolean("ftl_active"), o.optDoubleOrNull("dig_ms"), o.optLong("cache"), o.optLong("rrset"), o.optInt("failed"), o.optDoubleOrNull("uptime_h")) }
    fun stats(): Stats {
        val o = get("/api/v1/stats"); val ph = o.optJSONObject("pihole") ?: JSONObject()
        val sp = ph.optJSONObject("speed") ?: JSONObject()
        val speed = sp.keys().asSequence().map { it to sp.optDouble(it) }.toList()
        val ls = o.optJSONObject("lists") ?: JSONObject(); val lists = ls.keys().asSequence().associateWith { ls.optLong(it) }
        val h = o.optJSONArray("history") ?: JSONArray()
        val hist = (0 until h.length()).mapNotNull { i -> val e = h.optJSONObject(i) ?: return@mapNotNull null; e.optString("day", e.optString("date")) to e.optLong("domains", e.optLong("count")) }
        return Stats(ph.optLong("queries"), ph.optLong("blocked"), ph.optDouble("percent"), ph.optInt("clients"), speed, lists, o.optJSONObject("last_run")?.let { LastRun(it.optString("mode"), it.optString("time"), it.optLong("domains"), it.optDouble("duration_s", 0.0)) }, hist)
    }
    fun blocking(): Blocking {
        val o = get("/api/v1/blocking")
        fun rv(c: JSONObject): Review? = c.optJSONObject("review")?.let { r -> Review(r.optString("purpose"), r.optString("category"), r.optString("if_blocked"), r.optString("recommendation"), r.optDouble("confidence", 0.0), r.optString("reason"), r.optString("model"), r.optInt("revisions"), if (r.isNull("ts")) null else r.optLong("ts"), if (r.isNull("device_note")) "" else r.optString("device_note")) }
        fun list(k: String) = (o.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).map { i -> val c = a.getJSONObject(i); Candidate(c.getString("domain"), c.optString("kind", k), c.optInt("score"), c.optJSONArray("why")?.let { w -> (0 until w.length()).map(w::getString) } ?: emptyList(), c.optInt("clients"), c.optInt("queries"), rv(c), c.optJSONArray("devices")?.let { d -> (0 until d.length()).map { j -> d.getJSONObject(j).let { dv -> Asker(dv.optString("ip"), dv.optString("name"), dv.optInt("queries")) } } } ?: emptyList()) } }
        return Blocking(list("over"), list("under"), if (o.has("time") && !o.isNull("time")) o.optLong("time") else null, if (o.has("reviewed_at") && !o.isNull("reviewed_at")) o.optLong("reviewed_at") else null, o.optInt("pending"), o.optInt("dropped"))
    }
    fun daystats(): JSONObject = get("/api/v1/daystats")
    fun tokens(): List<Token> = parseTokens(get("/api/v1/tokens"))
    fun tokenAdd(role: String, label: String): Pair<String?, List<Token>> { val o = post("/api/v1/tokens", JSONObject().put("role", role).put("label", label)); return o.optString("token").ifBlank { null } to parseTokens(o) }
    fun tokenRevoke(id: String): List<Token> = parseTokens(post("/api/v1/tokens/$id/revoke", JSONObject()))
    private fun parseTokens(o: JSONObject): List<Token> = (o.optJSONArray("tokens") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> val t = a.getJSONObject(i); Token(t.getString("id"), t.optString("role"), t.optString("label"), (if (t.isNull("token")) "" else t.optString("token")).ifBlank { null }, if (t.isNull("created")) null else t.optLong("created"), if (t.isNull("last_seen")) null else t.optLong("last_seen")) } }
    fun decide(domain: String, decision: String): Boolean = post("/api/v1/blocking", JSONObject().put("domain", domain).put("decision", decision)).optBoolean("ok")

    private fun parseSwitch(s: JSONObject): Switch {
        val p = s.optJSONArray("presets")?.let { a -> (0 until a.length()).map(a::getInt) } ?: listOf(30, 120)
        val dv = s.optJSONArray("devices")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { d -> Device(d.optString("ip"), d.optString("name", d.optString("ip")), d.optString("icon", "device")) } } } ?: emptyList()
        return Switch(s.getString("name"), s.optString("label", s.getString("name")), s.optString("scope", ""), s.optString("kind", "full"), s.optBoolean("on"), if (s.isNull("until")) null else s.optLong("until"), p, dv)
    }
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
}
