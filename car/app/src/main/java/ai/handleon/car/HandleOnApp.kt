package ai.handleon.car

import android.app.Application
import android.content.Context
import androidx.room.Room
import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.car.store.Db
import ai.handleon.car.store.TripRepository
import ai.handleon.car.sync.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
    val db: Db by lazy { Room.databaseBuilder(context.applicationContext, Db::class.java, "handleon.db").addMigrations(ai.handleon.car.store.MIGRATION_1_2).build() }
    val tripRepository: TripRepository by lazy { TripRepository(db) }
    val engineRunner: EngineRunner by lazy { EngineRunner(tripRepository, onSaved = { SyncWorker.enqueue(context) }) }
    val syncState by lazy { SyncState(context.getSharedPreferences("handleon_sync", Context.MODE_PRIVATE)) }
    val supabase by lazy {
        SupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY,
            // 편지 함수는 모델 호출에 최대 20초를 쓴다. OkHttp 기본 읽기 대기 10초로는 응답 직전에 끊긴다.
            OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
            PreferenceTokenStore(context) { syncState.setDriver(null) })
    }
    val pairing by lazy { Pairing(supabase) }
    val carSync by lazy { CarSync(supabase, syncState, tripRepository, engineRunner, ::mode, ::presentation, selectedPromises = ::promises) }
    val fedClient by lazy { FedClient(supabase, tripRepository, ::presentation,
        FedConsentStore(context.getSharedPreferences("handleon_fed_consent", Context.MODE_PRIVATE)), { syncState.driverId }, {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.assets.open("fed_virtual_weeks.json").bufferedReader().use { parseFedVirtuals(it.readText()) }
        }
    }, { firstRunComplete() && !erasingData }, { syncState.connectionGeneration }) }
    private val prefs by lazy { context.getSharedPreferences("handleon", Context.MODE_PRIVATE) }
    private val otherDriverFlag by lazy { ai.handleon.car.collect.OtherDriverFlag(prefs) }
    private val setupState = MutableStateFlow(firstRunComplete())
    val setupCompleted = setupState.asStateFlow()
    private val presentationState = MutableStateFlow(presentation())
    val presentationEnabled = presentationState.asStateFlow()
    @Volatile var erasingData = false
    @Volatile var driving = true // Unknown while the activity's UX connection is absent.

    /** 첫 실행 화면(T8)에서 출생 연도로 고른 모드. 고르기 전에는 마음 운전. */
    fun mode(): Mode = prefs.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.MAEUM
    fun setMode(mode: Mode) = prefs.edit().putString("mode", mode.name).apply()

    val pendingConfirm = MutableStateFlow<ai.handleon.car.ui.PendingConfirm?>(null)
    fun promises(): Set<ai.handleon.driveprint.Promise> = prefs.getStringSet("promises", emptySet()).orEmpty()
        .mapNotNull { name -> ai.handleon.driveprint.Promise.entries.firstOrNull { it.name == name } }.toSet()
    fun setPromises(values: Set<ai.handleon.driveprint.Promise>) = prefs.edit().putStringSet("promises", values.map { it.name }.toSet()).apply()
    fun dismissedWeek(): String? = prefs.getString("dismissed_week", null)
    fun dismissWeek(key: String) = prefs.edit().putString("dismissed_week", key).apply()
    fun nextIsOther(): Boolean = otherDriverRequest() != null
    fun otherDriverRequest(): String? = otherDriverFlag.requestId()
    fun setNextIsOther(value: Boolean) = otherDriverFlag.set(value)
    fun consumeNextIsOther(request: String) = otherDriverFlag.consume(request)

    fun firstRunComplete(): Boolean = prefs.getBoolean("first_run_complete", false)
    fun setFirstRunComplete(complete: Boolean) {
        prefs.edit().putBoolean("first_run_complete", complete).apply()
        setupState.value = complete
    }
    fun presentation(): Boolean = prefs.getBoolean("presentation", false)
    fun dismissedTodayLine(): String? = prefs.getString("today_line_dismissed", null)
    fun dismissTodayLine(day: java.time.LocalDate) = prefs.edit().putString("today_line_dismissed", day.toString()).apply()
    fun setPresentation(enabled: Boolean) {
        prefs.edit().putBoolean("presentation", enabled).apply()
        presentationState.value = enabled
    }
    fun clearUiPreferences() {
        prefs.edit().remove("first_run_complete").remove("mode").remove("presentation").remove("today_line_dismissed").remove("next_is_other").remove("next_other_request").remove("promises").remove("dismissed_week").apply()
        pendingConfirm.value = null
        setupState.value = false
        presentationState.value = false
    }
}
