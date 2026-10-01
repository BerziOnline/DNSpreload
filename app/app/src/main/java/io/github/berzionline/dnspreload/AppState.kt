package io.github.berzionline.dnspreload

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Server URL and login token stored locally in SharedPreferences. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("dnspreload", Context.MODE_PRIVATE)
    var baseUrl: String get() = sp.getString("url", BuildConfig.DEFAULT_SERVER_URL) ?: ""; set(v) { sp.edit().putString("url", v.trim()).apply() }
    var token: String get() = sp.getString("token", "") ?: ""; set(v) { sp.edit().putString("token", v.trim()).apply() }
    fun clear() { sp.edit().remove("token").apply() }
}

data class DayStats(val days: List<JSONObject>, val streak: Int, val record: JSONObject?, val since: JSONObject?, val todayClients: List<JSONObject>,
                    val topBlocked: List<JSONObject>, val perClient: JSONObject, val cache: JSONObject?)

class AppState(private val settings: Settings, private val appCtx: Context) : ViewModel() {
    var role by mutableStateOf<String?>(null); private set
    var switches by mutableStateOf<List<Switch>>(emptyList()); private set
    var status by mutableStateOf<Status?>(null); private set
    var stats by mutableStateOf<Stats?>(null); private set
    var day by mutableStateOf<DayStats?>(null); private set
    var blocking by mutableStateOf<Blocking?>(null); private set
    var tokens by mutableStateOf<List<Token>>(emptyList()); private set
    var newToken by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var needsSetup by mutableStateOf(settings.token.isBlank())

    private fun api() = Api(settings.baseUrl, settings.token)
    private fun errorText(e: Exception): String? = if (e is ApiError) {
        if (e.messageRes == R.string.server_error) appCtx.getString(e.messageRes, e.code) else appCtx.getString(e.messageRes)
    } else e.message

    fun save(url: String, token: String) { settings.baseUrl = url; settings.token = token; needsSetup = token.isBlank(); if (!needsSetup) refresh() }
    fun logout() { settings.clear(); role = null; switches = emptyList(); needsSetup = true }
    fun current(): Pair<String, String> = settings.baseUrl to settings.token

    fun refresh() = viewModelScope.launch {
        if (settings.token.isBlank()) { needsSetup = true; return@launch }
        busy = true; error = null
        try {
            val (r, sw) = withContext(Dispatchers.IO) { api().switches() }
            role = r; switches = sw
            if (r == "admin") withContext(Dispatchers.IO) {
                runCatching { api().status() }.onSuccess { status = it }
                runCatching { api().stats() }.onSuccess { stats = it }
                runCatching { parseDay(api().daystats()) }.onSuccess { day = it }
                runCatching { api().blocking() }.onSuccess { blocking = it }
                runCatching { api().tokens() }.onSuccess { tokens = it }
            }
        } catch (e: ApiError) { error = errorText(e); if (e.code == 401) { role = null; needsSetup = true } }
        catch (e: Exception) { error = appCtx.getString(R.string.server_unreachable) }
        busy = false
    }

    fun toggle(sw: Switch, on: Boolean, minutes: Int = 30) = viewModelScope.launch {
        busy = true; error = null
        try {
            val upd = withContext(Dispatchers.IO) { api().setSwitch(sw.name, on, minutes) }
            QuickWidget.render(appCtx, upd, null)
            switches = switches.map { if (it.name == sw.name && upd != null) upd else it }
        } catch (e: Exception) { error = errorText(e) }
        busy = false
    }

    fun decide(c: Candidate, decision: String) = viewModelScope.launch {
        try {
            val ok = withContext(Dispatchers.IO) { api().decide(c.domain, decision) }
            if (ok) blocking = blocking?.let { b -> b.copy(over = b.over.filter { it.domain != c.domain }, under = b.under.filter { it.domain != c.domain }) }
            else error = appCtx.getString(R.string.decision_rejected)
        } catch (e: Exception) { error = errorText(e) }
    }

    fun tokenAdd(role: String, label: String) = viewModelScope.launch {
        try { val (t, list) = withContext(Dispatchers.IO) { api().tokenAdd(role, label) }; tokens = list; newToken = t; if (t == null) error = appCtx.getString(R.string.access_not_created) }
        catch (e: Exception) { error = errorText(e) }
    }
    fun tokenRevoke(id: String) = viewModelScope.launch {
        try { tokens = withContext(Dispatchers.IO) { api().tokenRevoke(id) } } catch (e: Exception) { error = errorText(e) }
    }

    private fun parseDay(o: JSONObject): DayStats {
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getJSONObject) } ?: emptyList()
        return DayStats(arr("days"), o.optInt("streak_days"), o.optJSONObject("record"), o.optJSONObject("since"), arr("today_clients"), arr("today_top_blocked"), o.optJSONObject("per_client") ?: JSONObject(), o.optJSONObject("cache"))
    }
}
