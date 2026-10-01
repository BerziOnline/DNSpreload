package io.github.berzionline.dnspreload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// Brand palette (brand/PALETTE.md): dark background, card, border; green = protection, blue = cache, amber = warning, red = error.
val Bg = Color(0xFF0B1220); val Card = Color(0xFF151D2E); val Line = Color(0xFF2A3548)
val Green = Color(0xFF22C55E); val GreenSoft = Color(0xFF4ADE80); val Blue = Color(0xFF38BDF8); val Yellow = Color(0xFFF59E0B); val Red = Color(0xFFEF4444); val Muted = Color(0xFF8B96A8); val Fg = Color(0xFFE6EDF3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(applicationContext)
        setContent {
            val vm: AppState = viewModel(factory = object : ViewModelProvider.Factory { override fun <T : androidx.lifecycle.ViewModel> create(c: Class<T>): T = AppState(settings, applicationContext) as T })
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Green, onBackground = Fg, onSurface = Fg)) { Root(vm) }
        }
    }
}

@Composable fun Logo(size: Int = 28) = Image(painterResource(R.drawable.logo_mark), stringResource(R.string.app_name), Modifier.size(size.dp))

@Composable
fun Root(vm: AppState) {
    LaunchedEffect(Unit) { vm.refresh() }
    if (vm.needsSetup) { SetupScreen(vm); return }
    val admin = vm.role == "admin"
    var tab by remember { mutableIntStateOf(0) }
    val tabs = if (admin) listOf(R.string.tab_status to Icons.Filled.MonitorHeart, R.string.tab_blocker to Icons.Filled.Shield, R.string.tab_stats to Icons.Filled.BarChart, R.string.tab_suggestions to Icons.Filled.Checklist) else listOf(R.string.tab_blocker to Icons.Filled.Shield)
    // Scaffold applies system-bar and gesture insets as padding.
    Scaffold(containerColor = Bg, contentWindowInsets = WindowInsets.safeDrawing, bottomBar = {
        if (tabs.size > 1) NavigationBar(containerColor = Card) { tabs.forEachIndexed { i, (t, ic) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, stringResource(t)) }, label = { Text(stringResource(t)) }) } }
    }, topBar = {
        Row(Modifier.fillMaxWidth().background(Card).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(30); Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Fg, modifier = Modifier.weight(1f))
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Green) else IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), tint = Muted) }
            IconButton(onClick = { vm.needsSetup = true }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings), tint = Muted) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            vm.error?.let { Text(it, color = Red, modifier = Modifier.fillMaxWidth().background(Color(0xFF3A1D1D)).padding(12.dp), textAlign = TextAlign.Center) }
            val key = tabs.getOrNull(tab)?.first ?: R.string.tab_blocker
            when (key) { R.string.tab_status -> StatusTab(vm); R.string.tab_blocker -> SwitchTab(vm); R.string.tab_stats -> StatsTab(vm); R.string.tab_suggestions -> BlockingTab(vm) }
        }
    }
}

@Composable
fun SetupScreen(vm: AppState) {
    val (u0, t0) = vm.current(); var url by remember { mutableStateOf(u0) }; var token by remember { mutableStateOf(t0) }
    Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) { Logo(56); Spacer(Modifier.width(14.dp)); Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Fg) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_intro), color = Muted); Spacer(Modifier.height(20.dp))
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.server_address)) }, placeholder = { Text(stringResource(R.string.server_address_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp))
        OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.token)) }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(20.dp))
        Button({ vm.save(url, token) }, Modifier.fillMaxWidth().height(52.dp), enabled = token.isNotBlank()) { Text(stringResource(R.string.sign_in), fontSize = 17.sp) }
        if (t0.isNotBlank()) { Spacer(Modifier.height(8.dp)); TextButton({ vm.logout() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_out), color = Muted) } }
        vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Red) }
    }
}

@Composable fun Section(title: String, tone: Color = Muted, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp, 6.dp).background(Card, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(tone, RoundedCornerShape(50))); Spacer(Modifier.width(8.dp)); Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Fg) }
        Spacer(Modifier.height(8.dp)); content()
        hint?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Muted, fontSize = 12.sp) }
    }
}
@Composable fun KV(k: String, v: String, tone: Color = Fg, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(k, color = Muted, fontSize = 14.sp); sub?.let { Text(it, color = Muted, fontSize = 11.sp) } }
        Text(v, color = tone, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}
@Composable fun Chapter(title: String, tone: Color, sub: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(4.dp).height(22.dp).background(tone, RoundedCornerShape(2.dp))); Spacer(Modifier.width(10.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tone) }
        Text(sub, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp))
    }
}
fun fmt(n: Long) = String.format(Locale.GERMANY, "%,d", n)
fun hhmm(epoch: Long) = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(epoch * 1000))
fun dmy(epoch: Long) = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(epoch * 1000))
fun JSONObject.list(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getJSONObject) } ?: emptyList()

@Composable
fun StatusTab(vm: AppState) {
    val s = vm.status; val st = vm.stats; val c = vm.day?.cache
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section(stringResource(R.string.services), if (s == null) Muted else if (s.unboundUp && s.ftlActive && s.failed == 0) Green else Red) {
                KV(stringResource(R.string.resolver), if (s?.unboundUp == true) stringResource(R.string.running) else stringResource(R.string.unhealthy), if (s?.unboundUp == true) Green else Red)
                KV(stringResource(R.string.pihole), if (s?.ftlActive == true) stringResource(R.string.running) else stringResource(R.string.unhealthy), if (s?.ftlActive == true) Green else Red)
                KV(stringResource(R.string.uptime), s?.uptimeH?.let { stringResource(R.string.uptime_hours, it) } ?: stringResource(R.string.unavailable))
            }
        }
        item {
            Section(stringResource(R.string.today), GreenSoft) {
                KV(stringResource(R.string.queries), st?.let { fmt(it.piholeQueries) } ?: stringResource(R.string.unavailable))
                KV(stringResource(R.string.queries_blocked), st?.let { fmt(it.piholeBlocked) } ?: stringResource(R.string.unavailable), Green)
                KV(stringResource(R.string.devices), st?.piholeClients?.toString() ?: stringResource(R.string.unavailable))
            }
        }
        item {
            Section(stringResource(R.string.preloaded), Blue, hint = stringResource(R.string.preloaded_hint)) {
                KV(stringResource(R.string.cached_answers), s?.let { fmt(it.cache) } ?: stringResource(R.string.unavailable), Blue)
                c?.list("days")?.lastOrNull()?.let { KV(stringResource(R.string.instant_today), stringResource(R.string.percentage, it.optDouble("hit_percent").toString()), Blue, sub = stringResource(R.string.count_of_total, fmt(it.optLong("instant")), fmt(it.optLong("total")))) }
                st?.lastRun?.let { lr -> KV(stringResource(R.string.last_preload), lr.time.substringAfter(" ").ifBlank { stringResource(R.string.unavailable) }, sub = listOfNotNull(lr.mode.takeIf { it.isNotBlank() }?.let { if (it == "full") stringResource(R.string.full_preload) else stringResource(R.string.network_list) }, lr.domains.takeIf { it > 0 }?.let { stringResource(R.string.domain_count, fmt(it)) }, lr.durationS.takeIf { it > 0 }?.let { stringResource(R.string.duration_seconds, it) }).joinToString(stringResource(R.string.list_separator))) }
            }
        }
        item { TokensCard(vm) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/** Admin access management: user, role, copyable token and last-seen time from the server. */
@Composable
fun TokensCard(vm: AppState) {
    val clip = LocalClipboardManager.current
    var adding by remember { mutableStateOf(false) }; var label by remember { mutableStateOf("") }; var role by remember { mutableStateOf("family") }
    var confirm by remember { mutableStateOf<Token?>(null) }
    Section(stringResource(R.string.access), Muted, hint = stringResource(R.string.access_hint)) {
        vm.tokens.sortedByDescending { it.lastSeen ?: 0L }.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row { Text(t.label, color = Fg, fontWeight = FontWeight.Medium); Spacer(Modifier.width(8.dp)); Text(stringResource(if (t.role == "admin") R.string.admin else R.string.user), color = if (t.role == "admin") Green else Blue, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                    Text(t.token ?: stringResource(R.string.hash_only), color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.clickable(enabled = t.token != null) { clip.setText(AnnotatedString(t.token!!)) })
                    Text(stringResource(R.string.last_seen, t.lastSeen?.let { dmy(it) } ?: stringResource(R.string.never)), color = Muted, fontSize = 11.sp)
                }
                if (t.token != null) IconButton({ clip.setText(AnnotatedString(t.token)) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), tint = Muted) }
                IconButton({ confirm = t }) { Icon(Icons.Filled.DeleteOutline, stringResource(R.string.remove_description), tint = Red) }
            }
        }
        vm.newToken?.let { nt ->
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B2A1F), RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text(stringResource(R.string.new_token_hint), color = Green, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(nt, color = Fg, fontSize = 14.sp, modifier = Modifier.weight(1f)); IconButton({ clip.setText(AnnotatedString(nt)) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), tint = Green) } }
                TextButton({ vm.newToken = null }) { Text(stringResource(R.string.hide), color = Muted) }
            }
        }
        if (!adding) OutlinedButton({ adding = true }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_access)) }
        else Column {
            OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.access_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(role == "family", { role = "family" }, { Text(stringResource(R.string.user_blocking_only)) }); FilterChip(role == "admin", { role = "admin" }, { Text(stringResource(R.string.admin)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.tokenAdd(role, label.trim()); adding = false; label = "" }, Modifier.weight(1f), enabled = label.trim().isNotEmpty()) { Text(stringResource(R.string.create)) }
                OutlinedButton({ adding = false }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    confirm?.let { t ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.remove_access_title)) }, text = { Text(stringResource(R.string.remove_access_message, t.label, stringResource(if (t.role == "admin") R.string.admin else R.string.user))) },
            confirmButton = { TextButton({ vm.tokenRevoke(t.id); confirm = null }) { Text(stringResource(R.string.remove), color = Red) } }, dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun SwitchTab(vm: AppState) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (vm.switches.isEmpty()) item { Section(stringResource(R.string.no_blocker), Muted) { Text(stringResource(R.string.no_access_for_token), color = Muted) } }
        items(vm.switches.sortedBy { it.kind != "full" }, key = { it.name }) { sw -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SwitchCard(sw, vm) } }
    }
}

/** Both blocking cards use green shields when enabled, amber crossed shields when paused.
 * Partial blocking (Google Ads only) uses the same card at 85% size. */
@Composable
fun SwitchCard(sw: Switch, vm: AppState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(sw.on) { while (sw.on) { delay(1000); now = System.currentTimeMillis() / 1000; if (sw.until != null && now >= sw.until) { vm.refresh(); break } } }
    val rest = if (sw.on && sw.until != null) maxOf(0, sw.until - now) else 0
    val k = if (sw.kind == "partial") 0.85f else 1f
    val tone = if (sw.on) Yellow else Green
    Column(Modifier.fillMaxWidth(k).padding(12.dp, 6.dp).background(Card, RoundedCornerShape(12.dp)).padding((14 * k).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (sw.on) Icons.Filled.RemoveModerator else Icons.Filled.Shield, null, tint = tone, modifier = Modifier.size((24 * k).dp)); Spacer(Modifier.width(8.dp))
            Column { Text(sw.label, fontWeight = FontWeight.SemiBold, fontSize = (16 * k).sp, color = Fg); if (sw.devices.isEmpty()) Text(sw.scope, color = Muted, fontSize = (11 * k).sp) }
        }
        if (sw.devices.isNotEmpty()) { Spacer(Modifier.height(6.dp)); sw.devices.forEach { d -> DeviceRow(d) } }
        Spacer(Modifier.height((10 * k).dp))
        Text(if (sw.on) stringResource(R.string.off) else stringResource(R.string.on), fontSize = (34 * k).sp, fontWeight = FontWeight.Bold, color = tone)
        if (sw.on && sw.until != null) Text(stringResource(R.string.pause_countdown, rest / 60, rest % 60, hhmm(sw.until)), color = Muted, fontSize = (14 * k).sp)
        Spacer(Modifier.height((10 * k).dp))
        if (sw.on) Button({ vm.toggle(sw, false) }, Modifier.fillMaxWidth().height((50 * k).dp), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) { Text(stringResource(R.string.turn_on), fontSize = (16 * k).sp) }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { sw.presets.forEach { mn -> OutlinedButton({ vm.toggle(sw, true, mn) }, Modifier.weight(1f).height((50 * k).dp), border = androidx.compose.foundation.BorderStroke(1.dp, Yellow), colors = ButtonDefaults.outlinedButtonColors(contentColor = Yellow)) { Text(if (mn < 60) stringResource(R.string.off_minutes, mn) else stringResource(R.string.off_hours, mn / 60), fontSize = (16 * k).sp) } } }
    }
}

/** Device alias and IP address, with an icon based on the device type. */
@Composable
fun DeviceRow(d: Device) {
    val ic = when (d.icon) { "phone" -> Icons.Filled.Smartphone; "laptop", "laptop-wifi" -> Icons.Filled.Laptop; else -> Icons.Filled.Devices }
    val badge = when (d.icon) { "laptop" -> Icons.Filled.Cable; "laptop-wifi" -> Icons.Filled.Wifi; else -> null }
    Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Muted, modifier = Modifier.size(14.dp))
        if (badge != null) { Spacer(Modifier.width(2.dp)); Icon(badge, null, tint = Muted, modifier = Modifier.size(10.dp)) }
        Spacer(Modifier.width(6.dp))
        Text(d.name, color = Fg, fontSize = 13.sp); Spacer(Modifier.width(6.dp)); Text(d.ip, color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun Bars(values: List<Float>, tone: Color, last: Color = tone) {
    val mx = (values.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
    Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v -> Box(Modifier.weight(1f).fillMaxHeight((v / mx).coerceIn(0.04f, 1f)).background(if (i == values.lastIndex) last else tone, RoundedCornerShape(3.dp))) }
    }
}

@Composable
fun StatsTab(vm: AppState) {
    val d = vm.day; val c = d?.cache
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        // ───── Blocking ─────
        item { Chapter(stringResource(R.string.blocking), Green, stringResource(R.string.blocking_subtitle)) }
        item {
            Section(stringResource(R.string.streak), Green, hint = stringResource(R.string.streak_hint)) {
                Row(verticalAlignment = Alignment.Bottom) { Text((d?.streak ?: 0).toString(), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Green); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.streak_days), color = Muted, modifier = Modifier.padding(bottom = 10.dp)) }
                d?.record?.let { KV(stringResource(R.string.record_day), stringResource(R.string.blocked_count, fmt(it.optLong("blocked"))), Fg, sub = it.optString("day")) }
                d?.since?.let { KV(stringResource(R.string.since_start), stringResource(R.string.blocked_count, fmt(it.optLong("blocked"))), Green, sub = stringResource(R.string.of_queries, fmt(it.optLong("queries")))) }
            }
        }
        item {
            Section(stringResource(R.string.last_14_days), Green) {
                val days = d?.days?.takeLast(14) ?: emptyList()
                Bars(days.map { it.optLong("blocked").toFloat() }, Green.copy(alpha = 0.55f), Green)
                days.lastOrNull()?.let { KV(stringResource(R.string.today), stringResource(R.string.blocked_percentage, fmt(it.optLong("blocked")), it.optDouble("percent").toString()), Green) }
                days.dropLast(1).lastOrNull()?.let { KV(stringResource(R.string.yesterday), stringResource(R.string.blocked_percentage, fmt(it.optLong("blocked")), it.optDouble("percent").toString())) }
            }
        }
        item {
            Section(stringResource(R.string.ranking_today), Green, hint = stringResource(R.string.ranking_hint)) {
                d?.todayClients?.take(8)?.forEachIndexed { i, cl ->
                    val ip = cl.optString("ip"); val pc = d.perClient.optJSONObject(ip)
                    Column(Modifier.fillMaxWidth().clickable { open = if (open == ip) null else ip }) {
                        KV(stringResource(R.string.ranked_device, i + 1, cl.optString("name")), fmt(cl.optLong("blocked")), if (i == 0) Yellow else Fg, sub = stringResource(if (open == ip) R.string.query_count else R.string.query_count_collapsed, fmt(cl.optLong("queries"))))
                        if (open == ip && pc != null) Column(Modifier.padding(start = 12.dp, bottom = 6.dp)) {
                            Text(stringResource(R.string.blocked), color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); pc.list("top_blocked").forEach { Text(stringResource(R.string.domain_hits, it.optString("domain"), fmt(it.optLong("n"))), color = Fg, fontSize = 13.sp) }
                            Spacer(Modifier.height(4.dp)); Text(stringResource(R.string.used), color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); pc.list("top_used").forEach { Text(stringResource(R.string.domain_hits, it.optString("domain"), fmt(it.optLong("n"))), color = Fg, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
        item { Section(stringResource(R.string.top_blocked_today), Green) { d?.topBlocked?.forEach { KV(it.optString("domain"), fmt(it.optLong("n"))) } } }

        // ───── Caching ─────
        item { Chapter(stringResource(R.string.caching), Blue, stringResource(R.string.caching_subtitle)) }
        item {
            val today = c?.list("days")?.lastOrNull()
            Section(stringResource(R.string.instant_answers), Blue, hint = stringResource(R.string.instant_hint)) {
                Row(verticalAlignment = Alignment.Bottom) { Text(today?.optDouble("hit_percent")?.let { String.format(Locale.GERMANY, "%.1f", it) } ?: stringResource(R.string.unavailable), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Blue); Text(stringResource(R.string.percent_unit), color = Muted, modifier = Modifier.padding(bottom = 10.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.today_lower), color = Muted, modifier = Modifier.padding(bottom = 10.dp)) }
                today?.let { KV(stringResource(R.string.from_internet), fmt(it.optLong("recursive")), Fg, sub = it.optDouble("avg_recursive_ms").takeIf { v -> !v.isNaN() }?.let { v -> stringResource(R.string.average_ms, v.toString()) }) }
                c?.optJSONObject("record_day")?.let { KV(stringResource(R.string.record_day), stringResource(R.string.percentage, it.optDouble("hit_percent").toString()), Blue, sub = it.optString("day")) }
            }
        }
        item {
            Section(stringResource(R.string.history_14_days), Blue, hint = stringResource(R.string.history_hint)) {
                val days = c?.list("days")?.takeLast(14) ?: emptyList()
                Bars(days.map { it.optDouble("hit_percent").toFloat() }, Blue.copy(alpha = 0.55f), Blue)
                days.dropLast(1).lastOrNull()?.let { KV(stringResource(R.string.yesterday), stringResource(R.string.percentage, it.optDouble("hit_percent").toString())) }
            }
        }
        item {
            val b = c?.optJSONObject("today_bands")
            Section(stringResource(R.string.response_times_today), Blue) {
                listOf("<1ms" to stringResource(R.string.under_1_ms), "1-5ms" to stringResource(R.string.band_1_5_ms), "5-50ms" to stringResource(R.string.band_5_50_ms), "50-200ms" to stringResource(R.string.band_50_200_ms), ">200ms" to stringResource(R.string.over_200_ms)).forEach { (k, l) -> KV(l, b?.optLong(k)?.let(::fmt) ?: stringResource(R.string.unavailable), if (k.startsWith("<") || k.startsWith("1")) Blue else Fg) }
            }
        }
        item { Section(stringResource(R.string.top_cached_today), Blue) { c?.list("top_cached")?.forEach { KV(it.optString("domain"), fmt(it.optLong("n"))) } } }
        item { Section(stringResource(R.string.not_cached), Yellow, hint = stringResource(R.string.not_cached_hint)) { c?.list("top_missed")?.forEach { KV(it.optString("domain"), stringResource(R.string.times_count, it.optLong("n")), Fg, sub = stringResource(R.string.milliseconds, it.optDouble("avg_ms").toString())) } } }
        item { Section(stringResource(R.string.slowest_today), Yellow) { c?.list("slowest")?.forEach { KV(it.optString("domain"), stringResource(R.string.milliseconds, it.optDouble("ms").toString()), Fg, sub = it.optString("time").substringAfter(" ")) } } }
    }
}

@Composable
fun BlockingTab(vm: AppState) {
    val b = vm.blocking
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(stringResource(R.string.review_intro, b?.reviewedAt?.let { stringResource(R.string.review_as_of, dmy(it)) } ?: "", b?.pending ?: 0, b?.dropped ?: 0), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp)) }
        item { Chapter(stringResource(R.string.over_blocked), Green, stringResource(R.string.suggestion_count, b?.over?.size ?: 0)) }
        items(b?.over ?: emptyList(), key = { "o" + it.domain }) { CandidateCard(it, vm, "allow", stringResource(R.string.allow), Green) }
        item { Chapter(stringResource(R.string.under_blocked), Red, stringResource(R.string.suggestion_count, b?.under?.size ?: 0)) }
        items(b?.under ?: emptyList(), key = { "u" + it.domain }) { CandidateCard(it, vm, "deny", stringResource(R.string.deny), Red) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
fun CandidateCard(c: Candidate, vm: AppState, action: String, actionLabel: String, tone: Color) {
    val r = c.review
    Section(c.domain, tone) {
        r?.let {
            Text(it.purpose, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.if_blocked, it.ifBlocked), color = Fg, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            val rec = when (it.recommendation) { "allow" -> stringResource(R.string.allow_lower) to Green; "deny" -> stringResource(R.string.deny_lower) to Red; else -> stringResource(R.string.leave_as_is) to Blue }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recommendation, rec.first), color = rec.second, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.review_confidence, it.category, (it.confidence * 100).toInt()) + (if (it.revisions > 0) stringResource(R.string.review_revisions, it.revisions) else ""), color = Muted, fontSize = 12.sp)
            }
            Text(it.reason, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(stringResource(R.string.candidate_counts, c.clients, c.queries, c.why.joinToString(stringResource(R.string.list_separator))), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ vm.decide(c, action) }, Modifier.weight(1.2f), contentPadding = PaddingValues(4.dp, 10.dp), colors = ButtonDefaults.buttonColors(containerColor = tone, contentColor = Bg)) { Text(actionLabel, maxLines = 1) }
            OutlinedButton({ vm.decide(c, "dismiss") }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp, 10.dp)) { Text(stringResource(R.string.dismiss), maxLines = 1, fontSize = 13.sp) }
            OutlinedButton({ vm.decide(c, "later") }, Modifier.weight(0.8f), contentPadding = PaddingValues(4.dp, 10.dp)) { Text(stringResource(R.string.later), maxLines = 1, fontSize = 13.sp) }
        }
    }
}
