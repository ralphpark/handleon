package ai.handleon.car.signals

import ai.handleon.driveprint.Geo
import ai.handleon.driveprint.LatLon
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/** Android GPS 1초. GPS bearing 은 0 으로만 와서(실측) 직전 위치에서 10m 넘게 움직였을 때 두 점의 방위각을 헤딩으로 쓴다. */
class LocationSignals(context: Context, private val sink: LatestSignals) : LocationListener {
    private val lm = context.applicationContext.getSystemService(LocationManager::class.java)
    private var anchor: LatLon? = null
    @Volatile var lastFixMs = 0L
        private set
    var failed: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
            .onFailure { failed = "위치"; Log.w(VehicleSignals.TAG, "GPS 구독 실패: $it") }
    }

    fun stop() = runCatching { lm.removeUpdates(this) }.let { }

    override fun onLocationChanged(l: Location) {
        val p = LatLon(l.latitude, l.longitude)
        lastFixMs = sink.now()
        sink.set(Key.POS, p)
        val a = anchor
        if (a == null) anchor = p
        else if (Geo.haversineKm(a, p) * 1000 >= 10) {
            sink.set(Key.HEADING, bearingDeg(a, p))
            anchor = p
        }
    }
}
