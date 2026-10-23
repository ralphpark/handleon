package ai.handleon.car.sync

import android.content.Context
import android.content.SharedPreferences

class PreferenceTokenStore(context: Context, private val onSessionLost: () -> Unit = {}) : TokenStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("handleon_sync", Context.MODE_PRIVATE)
    override fun load(): Session? {
        val user = prefs.getString("user_id", null) ?: return null
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        return Session(user, access, refresh, prefs.getLong("expires_at", 0))
    }
    override fun save(session: Session?) {
        prefs.edit().apply {
            if (session == null) {
                remove("user_id"); remove("access_token"); remove("refresh_token"); remove("expires_at")
            } else {
                putString("user_id", session.userId); putString("access_token", session.accessToken)
                putString("refresh_token", session.refreshToken); putLong("expires_at", session.expiresAtSec)
            }
        }.apply()
        if (session == null) onSessionLost()
    }
}
