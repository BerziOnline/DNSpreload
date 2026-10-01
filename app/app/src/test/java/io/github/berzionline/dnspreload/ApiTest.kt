package io.github.berzionline.dnspreload

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ApiTest {
    private fun server(vararg responses: MockResponse): MockWebServer = MockWebServer().apply { responses.forEach(::enqueue); start() }

    @Test fun switchesParsedWithRoleAndToken() {
        val s = server(MockResponse().setBody("""{"role":"family","switches":[{"name":"family-ads","label":"Werbeblocker aus","scope":"Handys","on":true,"until":1790430000,"presets":[30,120]}]}"""))
        val (role, sw) = Api(s.url("/").toString(), "tok123").switches()
        assertEquals("family", role); assertEquals(1, sw.size); assertTrue(sw[0].on); assertEquals(1790430000L, sw[0].until); assertEquals(listOf(30, 120), sw[0].presets)
        val req = s.takeRequest(); assertEquals("/api/v1/switches", req.path); assertEquals("Bearer tok123", req.getHeader("Authorization")); s.shutdown()
    }
    @Test fun noTokenSendsNoHeader() {
        val s = server(MockResponse().setBody("""{"role":"admin","switches":[]}"""))
        Api(s.url("/").toString(), null).switches(); assertNull(s.takeRequest().getHeader("Authorization")); s.shutdown()
    }
    @Test fun setSwitchPostsJsonAndReturnsState() {
        val s = server(MockResponse().setBody("""{"ok":true,"switch":{"name":"family-ads","label":"x","on":true,"until":1,"presets":[30]}}"""))
        val r = Api(s.url("/").toString(), "t").setSwitch("family-ads", true, 120)
        val req = s.takeRequest(); assertEquals("/api/v1/switch/family-ads", req.path); val b = org.json.JSONObject(req.body.readUtf8()); assertEquals("on", b.getString("action")); assertEquals(120, b.getInt("minutes")); assertTrue(r!!.on); s.shutdown()
    }
    @Test fun rejectedSwitchThrows() {
        val s = server(MockResponse().setResponseCode(400).setBody("""{"ok":false}"""))
        assertThrows(ApiError::class.java) { Api(s.url("/").toString(), "t").setSwitch("family-ads", true, 999) }; s.shutdown()
    }
    @Test fun unauthorizedGivesReadableMessage() {
        val s = server(MockResponse().setResponseCode(401).setBody("""{"error":"kein Zugang"}"""))
        val e = assertThrows(ApiError::class.java) { Api(s.url("/").toString(), "bad").switches() }
        assertEquals(401, e.code); assertEquals(R.string.unauthorized, e.messageRes); s.shutdown()
    }
    @Test fun forbiddenAndServerErrorsUseResources() {
        val s = server(MockResponse().setResponseCode(403), MockResponse().setResponseCode(503))
        try {
            val forbidden = assertThrows(ApiError::class.java) { Api(s.url("/").toString(), "t").switches() }
            assertEquals(R.string.forbidden, forbidden.messageRes)
            assertEquals(403, forbidden.code)
            val failed = assertThrows(ApiError::class.java) { Api(s.url("/").toString(), "t").switches() }
            assertEquals(R.string.server_error, failed.messageRes)
            assertEquals(503, failed.code)
        } finally { s.shutdown() }
    }
    @Test fun rejectedSwitchResponseUsesResource() {
        val s = server(MockResponse().setBody("""{"ok":false}"""))
        try {
            val e = assertThrows(ApiError::class.java) { Api(s.url("/").toString(), "t").setSwitch("x", true) }
            assertEquals(R.string.switch_rejected, e.messageRes)
        } finally { s.shutdown() }
    }
    @Test fun blockingParsesBothLists() {
        val s = server(MockResponse().setBody("""{"time":1,"over":[{"domain":"a.de","kind":"over","score":85,"why":["x","y"],"clients":2,"queries":9}],"under":[{"domain":"b.de","score":50,"why":["z"]}]}"""))
        val b = Api(s.url("/").toString(), "t").blocking()
        assertEquals(1, b.over.size); assertEquals(listOf("x", "y"), b.over[0].why); assertEquals("under", b.under[0].kind); assertNull(b.over[0].review); s.shutdown()
    }
    @Test fun statsLastRunIsTypedNotRawJson() {
        val s = server(MockResponse().setBody("""{"pihole":{"queries":10,"blocked":2,"percent":20.0,"clients":3},"unbound":{},"lists":{},"last_run":{"mode":"full","time":"2026-09-27 04:17","domains":71657,"duration_s":716.6},"history":[]}"""))
        val st = Api(s.url("/").toString(), "t").stats()
        assertEquals("full", st.lastRun!!.mode); assertEquals(71657L, st.lastRun!!.domains); assertEquals("2026-09-27 04:17", st.lastRun!!.time); s.shutdown()
    }
    @Test fun tokenNullIsHashOnly() {
        val s = server(MockResponse().setBody("""{"tokens":[{"id":"1","role":"admin","label":"x","token":null,"created":1,"last_seen":null}]}"""))
        assertEquals(null, Api(s.url("/").toString(), "t").tokens().single().token); s.shutdown()
    }

    @Test fun tokensParseAndAdd() {
        val s = server(MockResponse().setBody("""{"ok":true,"token":"NEU123","tokens":[{"id":"1","role":"family","label":"Test user","token":"abc","created":1,"last_seen":null},{"id":"2","role":"admin","label":"Test admin","token":"def","created":1,"last_seen":1790490000}]}"""))
        val (tok, list) = Api(s.url("/").toString(), "t").tokenAdd("family", "Test user")
        assertEquals("NEU123", tok); assertEquals(2, list.size); assertNull(list[0].lastSeen); assertEquals(1790490000L, list[1].lastSeen); assertEquals("admin", list[1].role)
        val req = s.takeRequest(); assertEquals("/api/v1/tokens", req.path); assertTrue(req.body.readUtf8().contains("\"label\":\"Test user\"")); s.shutdown()
    }
    @Test fun blockingParsesReviewAndCounters() {
        val s = server(MockResponse().setBody("""{"time":1,"reviewed_at":2,"pending":5,"dropped":3,"over":[{"domain":"a.de","score":85,"why":[],"review":{"purpose":"API von X","category":"api","if_blocked":"App lädt nicht","recommendation":"allow","confidence":0.8,"reason":"weil","model":"m"}}],"under":[]}"""))
        val b = Api(s.url("/").toString(), "t").blocking()
        assertEquals(5, b.pending); assertEquals(3, b.dropped); assertEquals("allow", b.over[0].review!!.recommendation); assertEquals("App lädt nicht", b.over[0].review!!.ifBlocked); s.shutdown()
    }
    @Test fun decidePostsDomainAndDecision() {
        val s = server(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(Api(s.url("/").toString(), "t").decide("a.de", "later"))
        val b = org.json.JSONObject(s.takeRequest().body.readUtf8()); assertEquals("a.de", b.getString("domain")); assertEquals("later", b.getString("decision")); s.shutdown()
    }
    @Test fun devicesParsedWithNameIpIcon() {
        val s = server(MockResponse().setBody("""{"role":"family","switches":[{"name":"family-ads","label":"Werbeblocker","scope":"Testgeräte","on":false,"until":null,"presets":[30,120],
            "devices":[{"ip":"192.0.2.20","name":"Test phone","icon":"phone"},{"ip":"192.0.2.110","name":"Test laptop","icon":"laptop"}]}]}"""))
        val sw = Api(s.url("/").toString(), "t").switches().second.single()
        assertEquals(listOf(Device("192.0.2.20", "Test phone", "phone"), Device("192.0.2.110", "Test laptop", "laptop")), sw.devices); s.shutdown()
    }
    @Test fun switchWithoutDevicesStaysEmpty() {
        val s = server(MockResponse().setBody("""{"role":"admin","switches":[{"name":"netz-ads","label":"Werbeblocker","on":false,"until":null}]}"""))
        assertTrue(Api(s.url("/").toString(), "t").switches().second.single().devices.isEmpty()); s.shutdown()
    }
    private fun germanQuickText(id: Int, args: Array<out Any>): String {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/values-de/strings.xml"))
        val name = when (id) {
            R.string.quick_off_until -> "quick_off_until"
            R.string.off_upper -> "off_upper"
            R.string.on_upper -> "on_upper"
            R.string.loading -> "loading"
            else -> error("Unexpected resource ID: $id")
        }
        val nodes = doc.getElementsByTagName("string")
        val raw = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == name }.textContent.removeSurrounding("\"")
        return String.format(java.util.Locale.GERMANY, raw, *args)
    }

    @Test fun quickStateTextDistinguishesOnOffAndError() {
        val on = Switch("x", "Werbeblocker", "", "full", true, 1790430000L, listOf(30))
        assertTrue(Quick.stateText(on, null, ::germanQuickText).startsWith("AUS · wieder an um"))
        assertEquals("AN", Quick.stateText(on.copy(on = false, until = null), null, ::germanQuickText))
        assertEquals("AUS", Quick.stateText(on.copy(until = null), null, ::germanQuickText))
        assertEquals("…", Quick.stateText(null, null, ::germanQuickText))
        assertEquals("Nicht erreichbar", Quick.stateText(null, "Nicht erreichbar", ::germanQuickText))
    }
}
