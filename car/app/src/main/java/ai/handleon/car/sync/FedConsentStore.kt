package ai.handleon.car.sync

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class FedChoice(val enabled: Boolean = false, val pendingOff: Boolean = false)

/** Private preferences, separate from collected records. Call writes on IO. No trip or session data. */
class FedConsentStore(private val prefs: SharedPreferences) {
    @Synchronized fun read(driver: String): FedChoice = decode(prefs.getString("choice:$driver", null))

    @Synchronized fun write(driver: String, choice: FedChoice) {
        check(prefs.edit().putString("choice:$driver", Json.encodeToString(choice)).commit()) { "fed_consent_write_failed" }
    }

    /** Local erasure forgets participation, but cannot cancel a withdrawal already requested by the user. */
    @Synchronized fun clearKeepingWithdrawals() {
        val edit = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("choice:")) {
                if (decode(value as? String).pendingOff) edit.putString(key, Json.encodeToString(FedChoice(pendingOff = true)))
                else edit.remove(key)
            }
        }
        check(edit.commit()) { "fed_consent_write_failed" }
    }

    private fun decode(raw: String?): FedChoice = try {
        raw?.let { Json.decodeFromString<FedChoice>(it) } ?: FedChoice()
    } catch (_: Exception) { FedChoice() }
}
