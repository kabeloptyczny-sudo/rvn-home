package pl.frutiger.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings

/** Wejścia telewizora (HDMI itd.) z TvInputManager + uruchamianie przez passthrough URI. */
object TvInputs {

    class Item(val id: String, val label: String, val type: Int)

    fun list(ctx: Context): List<Item> {
        val m = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            m.tvInputList
                .filter { it.isPassthroughInput }
                .map { Item(it.id, labelOf(ctx, it), it.type) }
                .sortedWith(compareBy<Item>({ it.type != TvInputInfo.TYPE_HDMI }, { it.label.lowercase() }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun labelOf(ctx: Context, i: TvInputInfo): String {
        val custom = try { i.loadCustomLabel(ctx)?.toString() } catch (e: Exception) { null }
        if (!custom.isNullOrBlank()) return custom
        return try { i.loadLabel(ctx).toString() } catch (e: Exception) { i.id }
    }

    fun intentFor(item: Item): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(item.id))

    /** Fallback: ekran ustawień wejść; gdy brak, ogólne ustawienia. */
    fun openSettings(a: Activity, start: (Intent) -> Boolean) {
        val tries = listOf(
            Intent("android.media.tv.action.SETUP_INPUTS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) if (start(i)) return
    }
}
