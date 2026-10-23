package ai.handleon.car

import android.app.Application
import android.content.Context
import androidx.room.Room
import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.car.store.Db
import ai.handleon.car.store.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandleOnApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** 수동 DI. 저장소는 처음 사용할 때 열며 실행기 하나를 공유한다. */
class AppGraph(val context: Context) {
    val db: Db by lazy { Room.databaseBuilder(context.applicationContext, Db::class.java, "handleon.db").build() }
    val tripRepository: TripRepository by lazy { TripRepository(db) }
    val engineRunner: EngineRunner by lazy { EngineRunner(tripRepository) }
    private val prefs by lazy { context.getSharedPreferences("handleon", Context.MODE_PRIVATE) }
    private val setupState = MutableStateFlow(firstRunComplete())
    val setupCompleted = setupState.asStateFlow()
    @Volatile var erasingData = false

    /** 첫 실행 화면(T8)에서 출생 연도로 고른 모드. 고르기 전에는 마음 운전. */
    fun mode(): Mode = prefs.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.MAEUM
    fun setMode(mode: Mode) = prefs.edit().putString("mode", mode.name).apply()

    fun firstRunComplete(): Boolean = prefs.getBoolean("first_run_complete", false)
    fun setFirstRunComplete(complete: Boolean) {
        prefs.edit().putBoolean("first_run_complete", complete).apply()
        setupState.value = complete
    }
    fun presentation(): Boolean = prefs.getBoolean("presentation", false)
    fun setPresentation(enabled: Boolean) = prefs.edit().putBoolean("presentation", enabled).apply()
    fun clearUiPreferences() {
        prefs.edit().remove("first_run_complete").remove("mode").remove("presentation").apply()
        setupState.value = false
    }
}
