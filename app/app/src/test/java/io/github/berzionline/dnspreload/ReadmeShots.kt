package io.github.berzionline.dnspreload

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Renders the README screenshots from made-up demo data against a local mock server.
// Record: ./gradlew testDebugUnitTest --tests "*ReadmeShots*" -Proborazzi.test.record=true
// Output: app/build/readme-shots/<name>.png
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1060dp-xxhdpi")
class ReadmeShots {
    @get:Rule val rule = createComposeRule()
    private val server = MockWebServer()
    private val now = System.currentTimeMillis() / 1000

    private fun arr(vararg o: JSONObject) = JSONArray().apply { o.forEach { put(it) } }
    private fun obj(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun dev(ip: String, name: String, icon: String) = obj("ip" to ip, "name" to name, "icon" to icon)
    private fun dev2(ip: String, name: String, q: Int) = obj("ip" to ip, "name" to name, "queries" to q)

    private fun body(path: String): String = when {
        path.startsWith("/api/v1/switches") -> obj("role" to "admin", "switches" to arr(
            obj("name" to "network-ads", "label" to "Ad blocker", "scope" to "whole network", "kind" to "full", "on" to false, "until" to null, "presets" to JSONArray(listOf(30, 120))),
            obj("name" to "kids-tablets", "label" to "Ad blocker – kids' tablets", "scope" to "2 devices", "kind" to "full", "on" to false, "until" to null, "presets" to JSONArray(listOf(30, 120)),
                "devices" to arr(dev("192.168.1.41", "Tablet 1", "phone"), dev("192.168.1.42", "Tablet 2", "phone"))),
            obj("name" to "google-ads", "label" to "Google Ads only", "scope" to "admin devices", "kind" to "partial", "on" to false, "until" to null, "presets" to JSONArray(listOf(30, 120)))))
        path.startsWith("/api/v1/status") -> obj("unbound_up" to true, "ftl_active" to true, "dig_ms" to 0.4, "cache" to 61234, "rrset" to 88410, "failed" to 0, "uptime_h" to 412.5)
        path.startsWith("/api/v1/stats") -> obj("pihole" to obj("queries" to 48213, "blocked" to 9126, "percent" to 18.9, "clients" to 17, "speed" to JSONObject()),
            "lists" to JSONObject(), "history" to JSONArray(),
            "last_run" to obj("mode" to "slice", "time" to "2026-10-01 14:31", "domains" to 2671, "duration_s" to 534.0))
        path.startsWith("/api/v1/daystats") -> {
            val blocked = listOf(8120, 9431, 7702, 8820, 10234, 11502, 9876, 8433, 9120, 8710, 9944, 10120, 8890, 9126)
            val hits = listOf(93.1, 94.0, 94.8, 95.2, 95.0, 95.9, 96.3, 96.1, 96.8, 97.0, 97.4, 97.2, 97.6, 97.9)
            val days = JSONArray(); val cdays = JSONArray()
            blocked.forEachIndexed { i, b -> val d = "2026-09-%02d".format(18 + i)
                days.put(obj("day" to d, "blocked" to b, "queries" to b * 5, "percent" to Math.round((18.4 + i * 0.05) * 10) / 10.0))
                cdays.put(obj("day" to d, "hit_percent" to hits[i], "instant" to Math.round(39087 * hits[i] / 100), "total" to 39087, "recursive" to 39087 - Math.round(39087 * hits[i] / 100), "avg_recursive_ms" to 61.0)) }
            fun dn(d: String, n: Int) = obj("domain" to d, "n" to n)
            obj("days" to days, "streak_days" to 41, "record" to obj("day" to "2026-09-23", "blocked" to 11502),
                "since" to obj("from" to 1754006400, "blocked" to 1284512, "queries" to 6802331),
                "today_clients" to arr(obj("ip" to "192.168.1.30", "name" to "Living-room TV", "queries" to 9120, "blocked" to 3411),
                    obj("ip" to "192.168.1.41", "name" to "Tablet 1", "queries" to 6311, "blocked" to 1820),
                    obj("ip" to "192.168.1.20", "name" to "Laptop", "queries" to 11240, "blocked" to 1302),
                    obj("ip" to "192.168.1.25", "name" to "Phone", "queries" to 7710, "blocked" to 988)),
                "today_top_blocked" to arr(dn("ads.example-tv.com", 1912), dn("app-measurement.com", 811), dn("doubleclick.net", 640), dn("graph.facebook.com", 402)),
                "per_client" to JSONObject(),
                "cache" to obj("days" to cdays,
                    "today_bands" to obj("<1ms" to 37820, "1-5ms" to 446, "5-50ms" to 404, "50-200ms" to 331, ">200ms" to 86),
                    "top_cached" to arr(dn("www.google.com", 2210), dn("i.ytimg.com", 1530), dn("api.spotify.com", 940)),
                    "top_missed" to arr(obj("domain" to "cdn.shop-example.de", "n" to 14, "avg_ms" to 88.0)),
                    "slowest" to arr(obj("domain" to "rare-site.example.org", "ms" to 412.0, "time" to "2026-10-01 09:12")),
                    "record_day" to obj("day" to "2026-10-01", "hit_percent" to 97.9)))
        }
        path.startsWith("/api/v1/blocking") -> obj("time" to now - 3600, "reviewed_at" to null, "pending" to 0, "dropped" to 0,
            "over" to arr(obj("domain" to "cdn.weather-example.com", "kind" to "over", "score" to 7, "why" to JSONArray(listOf("newly blocked", "retried 40×")), "clients" to 3, "queries" to 212, "devices" to arr(dev2("192.168.1.30", "Living-room TV", 150), dev2("192.168.1.41", "Tablet 1", 40), dev2("192.168.1.22", "Phone", 22)))),
            "under" to arr(obj("domain" to "telemetry.tv-example.net", "kind" to "under", "score" to 6, "why" to JSONArray(listOf("telemetry name", "every 30 s")), "clients" to 1, "queries" to 2880, "devices" to arr(dev2("192.168.1.30", "Living-room TV", 2880)))))
        path.startsWith("/api/v1/tokens") -> obj("tokens" to arr(
            obj("id" to "a1", "role" to "admin", "label" to "My phone", "token" to "k7Qm2vXb9TzR4wLp", "created" to now - 86400 * 30, "last_seen" to now - 60),
            obj("id" to "f1", "role" to "family", "label" to "Kids' tablet", "token" to null, "created" to now - 86400 * 9, "last_seen" to now - 7200)))
        else -> "{}"
    }.toString()

    private lateinit var vm: AppState

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body(request.path ?: "")) }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val s = Settings(ctx); s.baseUrl = server.url("/").toString(); s.token = "demo"
        vm = AppState(s, ctx)
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Green, onBackground = Fg, onSurface = Fg)) { Root(vm) } }
        rule.waitUntil(15000) { vm.tokens.isNotEmpty() && vm.blocking != null && vm.day != null }
        rule.waitForIdle()
    }
    @After fun tearDown() = server.shutdown()

    private fun shot(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/readme-shots/$name.png") }

    @Test fun shots() {
        shot("1-status")
        rule.onNodeWithText("Blocking").performClick(); shot("2-blocking")
        rule.onNodeWithText("Statistics").performClick(); shot("3-statistics")
        rule.onNode(hasScrollAction()).performScrollToIndex(5); shot("5-caching")
        rule.onNodeWithText("Suggestions").performClick(); shot("4-suggestions")
    }
}
