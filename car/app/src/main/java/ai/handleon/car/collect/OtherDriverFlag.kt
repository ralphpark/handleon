package ai.handleon.car.collect

import android.content.SharedPreferences
import java.util.UUID

/** A restored capture can acknowledge its old request without consuming a newer request. */
internal class OtherDriverFlag(private val prefs: SharedPreferences) {
    @Synchronized fun requestId(): String? = prefs.getString("next_other_request", null)
        ?: "legacy".takeIf { prefs.getBoolean("next_is_other", false) }

    @Synchronized fun set(value: Boolean) {
        val edit = prefs.edit().putBoolean("next_is_other", value)
        if (value) edit.putString("next_other_request", UUID.randomUUID().toString()) else edit.remove("next_other_request")
        check(edit.commit()) { "Driver request could not be saved" }
    }

    @Synchronized fun consume(request: String) {
        if (requestId() == request) {
            check(prefs.edit().remove("next_is_other").remove("next_other_request").commit()) { "Driver request could not be acknowledged" }
        }
    }
}
