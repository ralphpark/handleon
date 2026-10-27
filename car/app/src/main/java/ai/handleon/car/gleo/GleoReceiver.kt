package ai.handleon.car.gleo

import ai.handleon.car.HandleOnApp
import ai.handleon.car.ui.KST
import ai.handleon.car.ui.PendingConfirm
import ai.handleon.driveprint.Reset
import ai.handleon.driveprint.ResetKind
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

suspend fun dispatchGleo(function: String, params: String, load: suspend () -> GleoContext,
                         ask: suspend (PendingConfirm) -> GleoAskResult): GleoCallback {
    val (initial, confirmation) = GleoActions.respond(function, params, GleoActions.EMPTY)
    if (initial.status == "fail") return initial
    return try {
        if (confirmation == null) GleoActions.respond(function, params, load()).first
        else when (ask(confirmation)) {
            GleoAskResult.QUEUED -> initial
            GleoAskResult.BUSY -> GleoActions.busy(function)
            GleoAskResult.UNAVAILABLE -> GleoActions.unavailable(function)
        }
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { GleoActions.error(function) }
}

object GleoWire {
    private val wireJson = Json { encodeDefaults = true }
    const val PARAM = "ACTION_PARAM"
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    fun extras(callback: GleoCallback, packageName: String): Map<String, String> = mapOf(
        "result" to callback.status, "packageName" to packageName, "response" to wireJson.encodeToString(callback))

    // Callback broadcasts are public too. Only show bounded, allowlisted output from our declared package.
    fun mockResponse(ownPackage: String, packageName: String?, response: String?): String? {
        if (packageName != ownPackage || response == null || response.length > 16384) return null
        return runCatching {
            val callback = Json.decodeFromString<GleoCallback>(response)
            require(callback.functionName in GleoActions.FUNCTIONS + "UNKNOWN")
            require(callback.status in setOf("success", "asking", "fail", "error") && callback.message in GleoActions.MESSAGES)
            val allowed = setOf("trips", "minutes", "signal_kept", "signal_total", "hard_brakes", "hard_accels", "night_trips",
                "no_night_kept", "no_night_total", "rest_2h_kept", "rest_2h_total", "no_rain_kept", "no_rain_total",
                "status", "weeks_left", "months_left")
            val data = Json.parseToJsonElement(callback.data ?: "[]").jsonArray
            require(data.size <= 3)
            data.forEach { row -> row.jsonObject.forEach { (key, value) ->
                require(key in allowed)
                if (key == "status") require(value.jsonPrimitive.content in setOf("building", "ok", "flagged"))
                else require(value.jsonPrimitive.longOrNull?.let { it in 0..100000 } == true && !value.jsonPrimitive.isString)
            } }
            response
        }.getOrNull()
    }
}

/** No documented sender identity/permission exists in the local Gleo guide. Never trust input identity extras. */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        val params = runCatching { if (intent.hasExtra(GleoWire.PARAM)) intent.getStringExtra(GleoWire.PARAM) ?: "null" else "{}" }
            .getOrDefault("null")
        val pending = goAsync()
        val app = context.applicationContext as HandleOnApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val graph = app.graph
                fun ready() = graph.firstRunComplete() && !graph.erasingData
                val callback = if (!ready()) GleoActions.error(function) else try {
                    withTimeout(7000) {
                        dispatchGleo(function, params, {
                            val repo = graph.tripRepository
                            val resets = repo.resets().mapNotNull { (day, kind) ->
                                ResetKind.entries.firstOrNull { it.name == kind }?.let { Reset(day, it) }
                            }.toSet()
                            buildGleoContext(repo.trips(), graph.promises(), repo.labels().keys, resets, ZonedDateTime.now(KST), graph.driving, repo.report("FED_MODEL"))
                        }, { action -> enqueueGleoConfirmation(action, graph.pendingConfirm,
                            graph.tripRepository::latestCarForCorrection, System::currentTimeMillis, ::ready) })
                    }
                } catch (_: Exception) { GleoActions.error(function) }
                val safe = if (ready()) callback else GleoActions.error(function)
                runCatching {
                    app.sendBroadcast(Intent(GleoWire.RESPONSE_ACTION).apply {
                        GleoWire.extras(safe, app.packageName).forEach { (key, value) -> putExtra(key, value) }
                    })
                }
            } finally { pending.finish() }
        }
    }
}
