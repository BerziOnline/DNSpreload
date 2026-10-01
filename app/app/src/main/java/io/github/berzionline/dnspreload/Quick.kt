package io.github.berzionline.dnspreload

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** Shared widget/tile logic using the first switch visible to the token. */
object Quick {
    const val ACT_ON = "io.github.berzionline.dnspreload.QUICK_ON"
    const val ACT_OFF = "io.github.berzionline.dnspreload.QUICK_OFF"
    const val ACT_REFRESH = "io.github.berzionline.dnspreload.QUICK_REFRESH"
    const val EXTRA_MIN = "minutes"

    /** Background network request returning (switch, error text). */
    fun run(ctx: Context, action: String, minutes: Int, done: (Switch?, String?) -> Unit) = thread {
        val s = Settings(ctx)
        if (s.token.isBlank()) { done(null, ctx.getString(R.string.quick_no_access)); return@thread }
        try {
            val api = Api(s.baseUrl, s.token)
            val sw = api.switches().second.firstOrNull() ?: run { done(null, ctx.getString(R.string.no_switch)); return@thread }
            val upd = when (action) { ACT_ON -> api.setSwitch(sw.name, true, minutes); ACT_OFF -> api.setSwitch(sw.name, false); else -> null }
            done(upd ?: sw, null)
        } catch (e: Exception) { done(null, ctx.getString(R.string.unreachable)) }
    }
    fun stateText(ctx: Context, sw: Switch?, err: String?): String =
        stateText(sw, err) { id, args -> ctx.getString(id, *args) }

    // Inject only the resource lookup so the state branches remain JVM-testable.
    internal fun stateText(sw: Switch?, err: String?, text: (Int, Array<out Any>) -> String): String = when {
        err != null -> err
        sw == null -> text(R.string.loading, emptyArray())
        sw.on && sw.until != null -> text(R.string.quick_off_until, arrayOf(hhmm(sw.until)))
        sw.on -> text(R.string.off_upper, emptyArray())
        else -> text(R.string.on_upper, emptyArray())
    }
}

/** Home-screen widget: state plus pause or resume controls. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { render(ctx, null, null); Quick.run(ctx, Quick.ACT_REFRESH, 0) { sw, e -> render(ctx, sw, e) } }
    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action in setOf(Quick.ACT_ON, Quick.ACT_OFF, Quick.ACT_REFRESH)) {
            val pr = goAsync()
            Quick.run(ctx, intent.action!!, intent.getIntExtra(Quick.EXTRA_MIN, 30)) { sw, e -> render(ctx, sw, e); pr.finish() }
        }
    }
    companion object {
        fun render(ctx: Context, sw: Switch?, err: String?) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_quick)
            rv.setTextViewText(R.id.w_title, sw?.label ?: ctx.getString(R.string.ad_blocker))
            rv.setTextViewText(R.id.w_state, Quick.stateText(ctx, sw, err))
            rv.setTextColor(R.id.w_state, if (sw?.on == true) Color.parseColor("#F59E0B") else Color.parseColor("#22C55E"))
            val on = sw?.on == true
            rv.setViewVisibility(R.id.w_btn30, if (on) android.view.View.GONE else android.view.View.VISIBLE)
            rv.setViewVisibility(R.id.w_btn120, if (on) android.view.View.GONE else android.view.View.VISIBLE)
            rv.setViewVisibility(R.id.w_btnon, if (on) android.view.View.VISIBLE else android.view.View.GONE)
            rv.setOnClickPendingIntent(R.id.w_btn30, pi(ctx, Quick.ACT_ON, 30, 1))
            rv.setOnClickPendingIntent(R.id.w_btn120, pi(ctx, Quick.ACT_ON, 120, 2))
            rv.setOnClickPendingIntent(R.id.w_btnon, pi(ctx, Quick.ACT_OFF, 0, 3))
            rv.setOnClickPendingIntent(R.id.w_state, pi(ctx, Quick.ACT_REFRESH, 0, 4))
            rv.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.updateAppWidget(ComponentName(ctx, QuickWidget::class.java), rv)
        }
        private fun pi(ctx: Context, act: String, min: Int, rc: Int) = PendingIntent.getBroadcast(ctx, rc,
            Intent(ctx, QuickWidget::class.java).setAction(act).putExtra(Quick.EXTRA_MIN, min), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Quick-settings tile: tap to pause for 30 minutes, tap again to resume; long-press opens the app. */
class QuickTile : TileService() {
    override fun onStartListening() { Quick.run(this, Quick.ACT_REFRESH, 0) { sw, e -> show(sw, e) } }
    override fun onClick() {
        val t = qsTile ?: return
        val turningOff = t.state != Tile.STATE_ACTIVE   // ACTIVE = blocking is currently paused
        t.state = Tile.STATE_UNAVAILABLE; t.subtitle = getString(R.string.loading); t.updateTile()
        Quick.run(this, if (turningOff) Quick.ACT_ON else Quick.ACT_OFF, 30) { sw, e -> show(sw, e); QuickWidget.render(this, sw, e) }
    }
    private fun show(sw: Switch?, err: String?) {
        val t = qsTile ?: return
        t.label = sw?.label ?: getString(R.string.ad_blocker)
        t.state = when { err != null -> Tile.STATE_INACTIVE; sw?.on == true -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
        t.subtitle = when { err != null -> err; sw?.on == true && sw.until != null -> getString(R.string.tile_off_until, hhmm(sw.until)); sw?.on == true -> getString(R.string.off); else -> getString(R.string.tile_on_hint) }
        t.updateTile()
    }
}
