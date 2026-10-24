package ai.handleon.car.ui

import ai.handleon.car.AppGraph
import ai.handleon.car.centers.Centers
import ai.handleon.car.collect.CollectorService
import ai.handleon.car.engine.DailyWorker
import ai.handleon.car.engine.EngineState
import ai.handleon.car.engine.Mode
import ai.handleon.car.sync.SyncWorker
import ai.handleon.car.ui.components.PillButton
import ai.handleon.car.ui.components.PillKind
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.car.warmstart.BlueLinkMock
import ai.handleon.driveprint.LatLon
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

sealed class Screen(val key: String) {
    data object FirstRun : Screen("first")
    data object Home : Screen("home")
    data object Labels : Screen("labels")
    data object Settings : Screen("settings")
    data object Replay : Screen("replay")
    data object DevEngine : Screen("dev-engine")
    data object Center : Screen("center")
    data object Letter : Screen("letter")
    companion object {
        fun from(key: String): Screen = listOf(FirstRun, Home, Labels, Settings, Replay, DevEngine, Center, Letter).firstOrNull { it.key == key } ?: Home
    }
}

fun presentationScreen(screen: Screen, presentation: Boolean): Screen =
    if (screen == Screen.DevEngine && !presentation) Screen.Settings else screen

fun showDrivingScreen(driving: Boolean, presentation: Boolean, screen: Screen): Boolean =
    driving && !(presentation && screen == Screen.DevEngine)

@Composable
fun Nav(graph: AppGraph, engine: EngineState, replay: ReplayViewModel, driving: Boolean,
        onboarded: Boolean, collectionAllowed: Boolean, onStart: (Mode) -> Unit,
        onRequestPermissions: () -> Unit, onDataCleared: () -> Unit,
        alwaysLocation: Boolean = false, onAlwaysLocation: () -> Unit = {}) {
    var route by rememberSaveable { mutableStateOf(if (onboarded) Screen.Home.key else Screen.FirstRun.key) }
    var centerReturn by rememberSaveable { mutableStateOf(Screen.Home.key) }
    var modeName by rememberSaveable { mutableStateOf(graph.mode().name) }
    var birth by rememberSaveable { mutableStateOf("") }
    var firstMode by rememberSaveable { mutableStateOf(Mode.CONDITION.name) }
    var consent0 by rememberSaveable { mutableStateOf(false) }
    var consent1 by rememberSaveable { mutableStateOf(false) }
    var consent2 by rememberSaveable { mutableStateOf(false) }
    var starting by rememberSaveable { mutableStateOf(false) }
    var past by rememberSaveable { mutableStateOf(false) }
    var yesterday by rememberSaveable { mutableStateOf(false) }
    val presentation by graph.presentationEnabled.collectAsStateWithLifecycle()
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var home by remember { mutableStateOf<LatLon?>(null) }
    var progress by remember { mutableStateOf<CollectionProgress?>(null) }
    var labels by remember { mutableStateOf<Map<LocalDate, String>>(emptyMap()) }
    var resets by remember { mutableStateOf<List<Pair<LocalDate, String>>>(emptyList()) }
    var blueLink by remember { mutableStateOf(emptyList<ai.handleon.car.warmstart.BlueLinkMonth>()) }
    var centerUi by remember { mutableStateOf(CenterUi()) }
    var letter by remember { mutableStateOf<LetterUi?>(null) }
    val sync by graph.syncState.state.collectAsStateWithLifecycle()
    val pairingController = remember(graph) {
        PairingController(graph.supabase.configured, graph.pairing, graph.syncState, { SyncWorker.enqueue(graph.context) })
    }
    val pairing by pairingController.state.collectAsStateWithLifecycle()
    val centerNavigator = remember(graph) { CenterNavigator(graph.context) }
    val scope = rememberCoroutineScope()
    val mode = Mode.valueOf(modeName)
    val screen = if (!onboarded) Screen.FirstRun else presentationScreen(Screen.from(route), presentation)
    val drivingScreen = showDrivingScreen(driving, presentation, screen)
    val replayUi by replay.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val today = LocalDate.now(KST)

    suspend fun refreshLocal() {
        home = graph.tripRepository.home()
        progress = CollectionProgress.from(graph.tripRepository.trips())
        labels = graph.tripRepository.labels()
        resets = graph.tripRepository.resets()
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "처리하지 못했어요. 잠시 후 다시 시도해 주세요." }
            finally { busy = false }
        }
    }
    fun back() {
        if (busy) return
        error = null
        route = when (screen) {
            Screen.Center -> centerReturn
            Screen.Replay -> { replay.end(); Screen.Settings.key }
            Screen.DevEngine -> Screen.Settings.key
            else -> Screen.Home.key
        }
    }
    fun openCenter() { centerReturn = screen.key; route = Screen.Center.key }

    LaunchedEffect(route, presentation) {
        if (!presentation && Screen.from(route) == Screen.DevEngine) route = Screen.Settings.key
    }
    LaunchedEffect(onboarded) {
        if (!onboarded) route = Screen.FirstRun.key
        if (onboarded && route == Screen.FirstRun.key) { route = Screen.Home.key; modeName = graph.mode().name }
        starting = false
    }
    LaunchedEffect(engine.updatedMs, onboarded) {
        if (onboarded) try { refreshLocal() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "저장된 기록을 열지 못했어요." }
    }
    LaunchedEffect(sync, engine.updatedMs, onboarded) {
        letter = if (onboarded && sync.driverId != null && sync.lastSyncMs != null) {
            LetterUi.fromJson(graph.carSync.savedLetter())
        } else null
    }
    LaunchedEffect(screen, driving, lifecycleState) {
        if (screen == Screen.Settings && !driving && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            coroutineScope {
                launch { while (isActive) { pairingController.tick(); delay(1000) } }
                while (isActive) { pairingController.poll(); delay(3000) }
            }
        }
    }
    LaunchedEffect(past) {
        if (past) try { blueLink = withContext(Dispatchers.IO) { BlueLinkMock.load(graph.context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "모의 기록을 열지 못했어요." }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Replay) replay.start(graph.context, mode)
        if (screen == Screen.Center) {
            centerUi = CenterUi(loading = true)
            centerUi = withContext(Dispatchers.IO) {
                val all = try { graph.context.assets.open("centers.csv").bufferedReader().use { Centers.parse(it.readText()) } }
                catch (_: java.io.FileNotFoundException) { emptyList() }
                catch (_: java.io.IOException) { emptyList() }
                catch (_: IllegalArgumentException) { emptyList() }
                Centers.nearest(all, LatLon(37.3948, 127.1112)).let { CenterUi(it) }
            }
        }
    }
    DisposableEffect(screen, driving, lifecycleState) {
        replay.setVisible(screen == Screen.Replay && !driving && lifecycleState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { replay.setVisible(false) }
    }
    DisposableEffect(screen) {
        onDispose { centerNavigator.close() }
    }
    BackHandler(drivingScreen || screen !in listOf(Screen.Home, Screen.FirstRun)) { if (!drivingScreen) back() }

    if (drivingScreen) { DrivingScreen(); return }
    Column(Modifier.fillMaxSize().background(DarkTokens.surface)) {
        if (error != null) Text(error!!, Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        if (onboarded && !collectionAllowed && screen == Screen.Home) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("위치 권한을 허용하면 운전 기록을 모을 수 있어요", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            PillButton("권한 다시 확인", onRequestPermissions, kind = PillKind.SECONDARY)
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.FirstRun -> FirstRunScreen(FirstRunUi(birth, Mode.valueOf(firstMode), listOf(consent0, consent1, consent2), starting), today,
                    onChange = { birth = it.birthYear; firstMode = it.mode.name; consent0 = it.consents[0]; consent1 = it.consents[1]; consent2 = it.consents[2] },
                    onStart = { starting = true; onStart(Mode.valueOf(firstMode)) })
                Screen.Home -> if (mode == Mode.MAEUM) MaeumScreen(
                    MaeumUi.from(engine.long, home, progress, letter).copy(pastSelected = past, blueLink = blueLink),
                    onLabels = { route = Screen.Labels.key }, onSettings = { route = Screen.Settings.key }, onCenter = ::openCenter, onTab = { past = it },
                    onLetter = { route = Screen.Letter.key })
                else ConditionScreen(ConditionUi.from(engine.condition, labels.keys, resets),
                    onLabels = { route = Screen.Labels.key }, onSettings = { route = Screen.Settings.key })
                Screen.Labels -> LabelsScreen(LabelsUi(today, yesterday, labels[if (yesterday) today.minusDays(1) else today], busy),
                    onDay = { if (!busy) yesterday = it }, onLabel = { kind ->
                        val day = if (yesterday) today.minusDays(1) else today
                        action {
                            graph.tripRepository.setLabel(day, kind)
                            if (kind == "CAR_CHANGED") graph.tripRepository.addReset(day, "CAR_CHANGED")
                            graph.engineRunner.run(mode); refreshLocal()
                        }
                    }, onBack = ::back)
                Screen.Settings -> SettingsScreen(SettingsUi(mode, presentation, home != null, deleteDialog, busy,
                    pairing = pairing.copy(driverId = sync.driverId, lastSyncMs = sync.lastSyncMs, presentation = presentation),
                    alwaysLocation = alwaysLocation),
                    onMode = { selected -> action {
                        graph.setMode(selected); modeName = selected.name
                        DailyWorker.schedule(graph.context, selected)
                        graph.engineRunner.run(selected); refreshLocal()
                    } }, onReplay = { replay.start(graph.context, mode, restart = true); route = Screen.Replay.key },
                    onPresentation = graph::setPresentation, onDeleteDialog = { deleteDialog = it },
                    onAlwaysLocation = onAlwaysLocation,
                    onDelete = { action {
                        replay.end()
                        graph.erasingData = true
                        try {
                            eraseCollectedData(
                                stopAndAwait = { stopCollectionBeforeErase(graph.context) },
                                clearCheckpoint = { withContext(Dispatchers.IO) {
                                    val checkpoint = File(graph.context.filesDir, "segmenter.json")
                                    check(!checkpoint.exists() || checkpoint.delete())
                                } },
                                clearTables = { withContext(Dispatchers.IO) { graph.db.clearAllTables() } },
                                finish = { graph.clearUiPreferences() })
                        } finally { graph.erasingData = false }
                        birth = ""; firstMode = Mode.CONDITION.name; consent0 = false; consent1 = false; consent2 = false
                        home = null; progress = null; labels = emptyMap(); resets = emptyList(); past = false
                        deleteDialog = false; route = Screen.FirstRun.key
                        onDataCleared()
                    } }, onBack = ::back,
                    onNewCode = { scope.launch { pairingController.newCode() } },
                    onSync = { SyncWorker.enqueue(graph.context) },
                    onDevEngine = { if (presentation) route = Screen.DevEngine.key })
                Screen.Replay -> ReplayScreen(replayUi, replay::pause, { replay.end(); route = Screen.Settings.key }, ::openCenter)
                Screen.DevEngine -> {
                    val live by CollectorService.live.collectAsStateWithLifecycle()
                    DevEngineScreen(DevEngineUi.from(live), ::back)
                }
                Screen.Center -> CenterScreen(centerUi, { center ->
                    centerNavigator.request(center) { centerUi = centerUi.copy(routeMessage = it) }
                }, ::back)
                Screen.Letter -> LetterScreen(letter, ::back)
            }
        }
    }
}

/** 수집 서비스를 멈추고, 마지막 트립 저장까지 끝났다는 신호를 기다린다(10초 안에 안 오면 지우지 않는다). */
private suspend fun stopCollectionBeforeErase(context: Context) {
    context.stopService(android.content.Intent(context, CollectorService::class.java))
    check(withTimeoutOrNull(10_000) { CollectorService.isRunning.first { !it } } != null) { "collector_stop_timeout" }
}
