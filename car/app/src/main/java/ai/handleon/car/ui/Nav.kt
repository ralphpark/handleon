package ai.handleon.car.ui

import ai.handleon.car.AppGraph
import ai.handleon.car.centers.Centers
import ai.handleon.car.collect.CollectorService
import ai.handleon.car.collect.clearCollectorCheckpoints
import ai.handleon.car.engine.DailyWorker
import ai.handleon.car.engine.EngineState
import ai.handleon.car.engine.Mode
import ai.handleon.car.sync.SyncWorker
import ai.handleon.car.ui.components.PillButton
import ai.handleon.car.ui.components.PillKind
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.car.warmstart.BlueLinkMock
import ai.handleon.driveprint.*
import ai.handleon.car.collect.DriverFlow
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
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
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

sealed class Screen(val key: String) {
    data object FirstRun : Screen("first")
    data object Home : Screen("home")
    data object Resume : Screen("resume")
    data object Promises : Screen("promises")
    data object Labels : Screen("labels")
    data object Settings : Screen("settings")
    data object Replay : Screen("replay")
    data object DevEngine : Screen("dev-engine")
    data object Center : Screen("center")
    data object Letter : Screen("letter")
    companion object {
        fun from(key: String): Screen = listOf(FirstRun, Home, Labels, Settings, Replay, DevEngine, Center, Letter, Resume, Promises).firstOrNull { it.key == key } ?: Home
    }
}

fun presentationScreen(screen: Screen, presentation: Boolean): Screen =
    if (screen == Screen.DevEngine && !presentation) Screen.Settings else screen

// 발표 모드의 엔진 화면만 주행 중에도 남긴다(시연용, 조작 없는 읽기 화면). 나머지는 모두 주행 화면.
fun showDrivingScreen(driving: Boolean, presentation: Boolean, screen: Screen): Boolean =
    driving && !(presentation && screen == Screen.DevEngine)

@Composable
fun Nav(graph: AppGraph, engine: EngineState, replay: ReplayViewModel, driving: Boolean,
        onboarded: Boolean, collectionAllowed: Boolean, onStart: (Mode) -> Unit,
        onRequestPermissions: () -> Unit, onDataCleared: () -> Unit,
        alwaysLocation: Boolean = false, onAlwaysLocation: () -> Unit = {},
        isDriving: () -> Boolean = { driving }) {
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
    val fed by graph.fedClient.state.collectAsStateWithLifecycle()
    var fedJob by remember { mutableStateOf<Job?>(null) }
    var sharing by remember(sync.driverId) { mutableStateOf<SharingUi?>(null) }
    var notes by remember(sync.driverId) { mutableStateOf<List<CarNote>>(emptyList()) }
    var familyError by remember(sync.driverId) { mutableStateOf<String?>(null) }
    var familyBusy by remember { mutableStateOf(false) }
    var revokeDialog by rememberSaveable { mutableStateOf(false) }
    var ownerTrips by remember { mutableStateOf<List<TripFeatures>>(emptyList()) }
    var pendingTrips by remember { mutableStateOf<List<TripFeatures>>(emptyList()) }
    var selectedPromises by remember { mutableStateOf(graph.promises()) }
    var dismissedWeek by remember { mutableStateOf(graph.dismissedWeek()) }
    var dismissedTrip by rememberSaveable { mutableStateOf<Long?>(null) }
    var dismissedLostWay by rememberSaveable { mutableStateOf<Long?>(null) }
    var lostWayMessage by remember { mutableStateOf<String?>(null) }
    var nextIsOther by remember { mutableStateOf(graph.nextIsOther()) }
    var stopNoticesDialog by rememberSaveable { mutableStateOf(false) }
    val pendingConfirm by graph.pendingConfirm.collectAsStateWithLifecycle()
    var todayTripDay by remember { mutableStateOf<LocalDate?>(null) }
    var dismissedLine by remember { mutableStateOf(graph.dismissedTodayLine()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var restEndMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var restFinished by rememberSaveable { mutableStateOf(false) }
    var restDismissedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val notifications = remember(graph) { RestNotifications(graph.context) }
    val session by CollectorService.driveSession.collectAsStateWithLifecycle()
    // 발표에서는 2분 주행으로 쉬어 가기를 시연할 수 있도록 줄인다. 평소에는 2시간이다.
    val restAfterMs = if (graph.presentation()) 2 * 60_000L else 2 * 3_600_000L
    val familyMutex = remember { Mutex() }
    var familyJob by remember { mutableStateOf<Job?>(null) }
    // The Activity getter sees a UX restriction change even before the next composition.
    val drivingNow by rememberUpdatedState(isDriving)
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
    // Do not suspend this observer at STOPPED: polling/timer effects must see leaving RESUMED.
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val today = Instant.ofEpochMilli(nowMs).atZone(KST).toLocalDate()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val restUi = if (!driving && (restEndMs != null || restFinished || restEligible(session, nowMs, restAfterMs, restDismissedStart)))
        RestUi(restEndMs?.let { (it - nowMs).coerceAtLeast(0) }, restFinished) else null
    val now = Instant.ofEpochMilli(nowMs).atZone(KST)
    val carTrips = remember(ownerTrips, nowMs / 60_000) { carTripsThrough(ownerTrips, nowMs) }
    val lastToday = carTrips.lastOrNull { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() == today }
    val recentCorrection = carTrips.lastOrNull { nowMs - it.startMs in 0 until DriverFlow.ANSWER_WINDOW_MS }
    val resumeUi = remember(ownerTrips, selectedPromises, blueLink, presentation, replayUi.adapt, nowMs / 60_000) {
        val resume = buildResume(ownerTrips, selectedPromises, now)
        resume.copy(adapt = if (presentation) replayUi.adapt else emptyList(),
            blueLink = blueLinkCompare(blueLink.filter { it.month < java.time.YearMonth.from(now) }, resume.month.minutes.takeIf { resume.month.trips > 0 }))
    }
    val latestCarTrip = carTrips.maxByOrNull { it.endMs }
    val lostWay = lostWayCard(latestCarTrip, home, nowMs, dismissedLostWay)
    val weekStory = remember(carTrips, weekCardKey(now)) {
        val from = LocalDate.parse(weekCardKey(now))
        Habits.summary(carTrips, Outings.group(carTrips, nowMs), from, from.plusWeeks(1), KST).story
    }
    val check = pendingTrips.firstOrNull { nowMs - it.startMs in 0 until DriverFlow.ANSWER_WINDOW_MS }
        ?.let { HomeCard.DriverCheck(it.startMs, Habits.of(it).minutes) }
    val card = topCard(HomeInputs(rest = restUi, driverCheck = check, confirm = pendingConfirm, lostWay = lostWay,
        note = notes.firstOrNull(), trip = lastToday?.takeIf { it.startMs != dismissedTrip }?.let { HomeCard.Trip(Habits.line(Habits.of(it))) },
        week = weekStory?.takeIf { weekCardVisible(now, dismissedWeek) },
        line = visibleTodayLine(today, mode, todayTripDay == today, dismissedLine)))

    suspend fun refreshLocal() {
        home = graph.tripRepository.home()
        val trips = graph.tripRepository.trips()
        ownerTrips = trips
        pendingTrips = graph.tripRepository.pendingTrips()
        nextIsOther = graph.nextIsOther()
        progress = CollectionProgress.from(trips)
        todayTripDay = today.takeIf { hasTripToday(trips, today) }
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
    fun answerDriver(start: Long, owner: Boolean) {
        if (!(!drivingNow() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) return
        action {
            try { graph.carSync.answerDriver(start, owner) }
            finally { SyncWorker.enqueue(graph.context); refreshLocal() }
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

    fun parkedAndResumed() = !drivingNow() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun fedAction(block: suspend () -> Unit) {
        if (!parkedAndResumed() || Screen.from(route) != Screen.Settings || busy) return
        fedJob?.cancel()
        fedJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* FedClient exposes only a fixed message. */ }
            try { if (!graph.erasingData && graph.firstRunComplete()) graph.engineRunner.run(mode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "저장된 기록을 열지 못했어요." }
        }
    }
    fun familyAvailable() = parkedAndResumed() && graph.syncState.driverId != null &&
        Screen.from(route) in listOf(Screen.Home, Screen.Settings)

    fun familyAction(block: suspend () -> Unit) {
        if (familyBusy || !familyAvailable()) return
        val driver = graph.syncState.driverId
        familyBusy = true
        familyJob = scope.launch {
            try {
                familyMutex.withLock {
                    ensureActive()
                    if (familyAvailable() && graph.syncState.driverId == driver) { familyError = null; block() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { familyError = LOAD_UNAVAILABLE }
            finally { familyBusy = false }
        }
    }
    fun toggleSharing() {
        val value = sharing ?: return
        familyAction {
            graph.carSync.setSharingPaused(!value.paused)
            sharing = value.copy(paused = !value.paused)
            if (familyAvailable()) sharing = graph.carSync.sharingState()
        }
    }

    LaunchedEffect(driving, resumed, sync.driverId) {
        if (driving || !resumed || sync.driverId == null) familyJob?.cancel()
        if (driving) {
            restEndMs = null; restFinished = false; revokeDialog = false; stopNoticesDialog = false
            notifications.cancel()
        }
    }
    LaunchedEffect(sync.driverId) { familyJob?.cancel(); revokeDialog = false }
    LaunchedEffect(screen, resumed, driving, sync.driverId) {
        fedJob?.cancel() // A changed linked driver also invalidates work queued from this screen.
        if (screen == Screen.Settings && resumed && !driving) {
            try { graph.fedClient.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Fixed FedClient state remains visible. */ }
        }
    }
    LaunchedEffect(resumed, driving) {
        if (resumed && !driving) while (isActive) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(restEndMs, resumed, driving) {
        // ponytail: 화면 밖 알림(AlarmManager 등)은 후속 범위. 화면이 RESUMED일 때만 완료한다.
        if (!resumed && !driving) return@LaunchedEffect
        while (isActive) {
            val tick = restTimerTick(restEndMs, System.currentTimeMillis(), drivingNow(),
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            restEndMs = tick.endMs
            if (drivingNow()) restFinished = false
            if (tick.finished) restFinished = true
            if (tick.notify && parkedAndResumed()) notifications.finished(drivingNow())
            if (tick.endMs == null) break
            delay(1000)
        }
    }
    LaunchedEffect(screen, driving, resumed, sync.driverId) {
        if (driving || !resumed || sync.driverId == null || screen !in listOf(Screen.Home, Screen.Settings)) return@LaunchedEffect
        val pollingDriver = sync.driverId
        do {
            familyMutex.withLock {
                val update = refreshParkedFamily(
                    canRead = { familyAvailable() && graph.syncState.driverId == pollingDriver },
                    homeVisible = { Screen.from(route) == Screen.Home },
                    readSharing = graph.carSync::sharingState, readNotes = graph.carSync::notes)
                update.sharing?.let { sharing = it }
                update.notes?.let { notes = it }
                familyError = update.error
            }
            if (screen != Screen.Home) break
            delay(60_000)
        } while (isActive)
    }
    LaunchedEffect(screen, driving, resumed, today) {
        if (onboarded && screen == Screen.Home && !driving && resumed) {
            while (isActive) {
                try { refreshLocal() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { todayTripDay = null }
                delay(60_000)
            }
        }
    }

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
    LaunchedEffect(past, screen) {
        if (past || screen == Screen.Resume) try { blueLink = withContext(Dispatchers.IO) { BlueLinkMock.load(graph.context) } }
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
    DisposableEffect(screen, driving, lifecycleState) {
        onDispose { centerNavigator.close() }
    }
    LaunchedEffect(lostWay) { lostWayMessage = null }
    BackHandler(drivingScreen || screen !in listOf(Screen.Home, Screen.FirstRun)) { if (!drivingScreen) back() }

    if (drivingScreen) {
        DrivingScreen(session?.takeIf { it.continuousMs >= restAfterMs }?.let { continuousDriveText(it.continuousMs) })
        return
    }
    val sharingHeader: @Composable RowScope.() -> Unit = {
        sharing?.takeIf { sync.driverId != null && it.showHome }?.let { SharingBar(it, familyBusy, ::toggleSharing) }
    }
    val homeCard: @Composable () -> Unit = {
        card?.let { HomeCardPanel(it, familyBusy || busy,
            onStartTimer = { if (parkedAndResumed()) { restEndMs = System.currentTimeMillis() + REST_TIMER_MS; restFinished = false; notifications.cancel() } },
            onStopTimer = { restEndMs = null; restFinished = false; notifications.cancel() },
            onDismissRest = { restDismissedStart = session?.startMs; restEndMs = null; restFinished = false; notifications.cancel() },
            onNoteSeen = { id -> familyAction { graph.carSync.markNoteSeen(id); notes = notesAfterSeen(notes, id) } },
            onDismissLine = { graph.dismissTodayLine(today); dismissedLine = today.toString() }, onCenter = ::openCenter,
            onDriverAnswer = ::answerDriver,
            onResume = { if (parkedAndResumed()) route = Screen.Resume.key },
            onDismissTrip = { dismissedTrip = lastToday?.startMs },
            onDismissWeek = { graph.dismissWeek(weekCardKey(now)); dismissedWeek = weekCardKey(now) },
            lostWayMessage = lostWayMessage,
            onGuideHome = {
                val destination = home
                val trip = latestCarTrip
                if (parkedAndResumed() && destination != null && lostWay != null) {
                    centerNavigator.request(destination, canRequest = {
                        parkedAndResumed() && Screen.from(route) == Screen.Home &&
                            lostWayCard(trip, destination, System.currentTimeMillis(), dismissedLostWay) != null
                    }) { lostWayMessage = it }
                }
            },
            onDismissLostWay = { if (parkedAndResumed()) { dismissedLostWay = lostWay; lostWayMessage = null; centerNavigator.close() } },
            onCancelConfirm = { if (parkedAndResumed()) graph.pendingConfirm.value = null },
            onConfirm = {
                if (parkedAndResumed()) action {
                    val confirmation = graph.pendingConfirm.value
                    try {
                        runConfirmed(confirmation, {
                            val request = confirmation as? PendingConfirm.MarkOtherTrip
                            if (request != null) graph.carSync.answerDriver(request.startMs, false, canAnswer = {
                                val latest = graph.tripRepository.latestCarForCorrection()
                                ai.handleon.car.gleo.driverCorrectionAllowed(request, latest, System.currentTimeMillis(), parkedAndResumed())
                                    .also { if (!it) error = "확인할 운전 기록이 바뀌었어요. 다시 요청해 주세요." }
                            })
                        }, { graph.carSync.setSharingPaused(true); sharing = graph.carSync.sharingState() },
                            consume = { graph.pendingConfirm.value = null })
                    } finally { SyncWorker.enqueue(graph.context); refreshLocal() }
                }
            }) }
    }
    Column(Modifier.fillMaxSize().background(DarkTokens.surface)) {
        if (error != null) Text(error!!, Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        if (onboarded && !collectionAllowed && screen == Screen.Home) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("위치 권한을 허용하면 운전 기록을 모을 수 있어요", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            PillButton("권한 다시 확인", onRequestPermissions, kind = PillKind.SECONDARY)
        }
        if (screen == Screen.Home && familyError != null) Text(LOAD_UNAVAILABLE, Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.FirstRun -> FirstRunScreen(FirstRunUi(birth, Mode.valueOf(firstMode), listOf(consent0, consent1, consent2), starting), today,
                    onChange = { birth = it.birthYear; firstMode = it.mode.name; consent0 = it.consents[0]; consent1 = it.consents[1]; consent2 = it.consents[2] },
                    onStart = { starting = true; onStart(Mode.valueOf(firstMode)) })
                Screen.Home -> if (mode == Mode.MAEUM) MaeumScreen(
                    MaeumUi.from(engine.long, home, progress, letter, engine.weekly, engine.readiness).copy(pastSelected = past, blueLink = blueLink),
                    onLabels = { route = Screen.Labels.key }, onSettings = { route = Screen.Settings.key }, onCenter = ::openCenter, onTab = { past = it },
                    onLetter = { route = Screen.Letter.key }, headerExtra = sharingHeader, homeCard = homeCard, compactHome = card != null)
                else ConditionScreen(ConditionUi.from(engine.condition, labels.keys, resets),
                    onLabels = { route = Screen.Labels.key }, onSettings = { route = Screen.Settings.key },
                    headerExtra = sharingHeader, homeCard = homeCard, compactHome = card != null)
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
                    alwaysLocation = alwaysLocation, sharing = sharing, sharingBusy = familyBusy,
                    sharingError = familyError, confirmRevoke = revokeDialog, fed = fed),
                    onMode = { selected -> action {
                        graph.setMode(selected); modeName = selected.name
                        DailyWorker.schedule(graph.context, selected)
                        graph.engineRunner.run(selected); refreshLocal()
                    } }, onReplay = { replay.start(graph.context, mode, restart = true); route = Screen.Replay.key },
                    onPresentation = graph::setPresentation, onDeleteDialog = { deleteDialog = it },
                    onAlwaysLocation = onAlwaysLocation,
                    onSharingToggle = ::toggleSharing, onRevokeDialog = { revokeDialog = it },
                    onRevoke = { familyAction {
                        graph.carSync.revokeAllShares(); revokeDialog = false
                        notes = emptyList(); sharing = sharing?.copy(recipients = emptyList())
                        if (familyAvailable()) sharing = graph.carSync.sharingState()
                    } },
                    onDelete = { action {
                        replay.end()
                        graph.erasingData = true
                        try {
                            graph.fedClient.withErasure { eraseCollectedData(
                                stopAndAwait = { stopCollectionBeforeErase(graph.context) },
                                clearCheckpoint = { withContext(Dispatchers.IO) {
                                    clearCollectorCheckpoints(graph.context.filesDir)
                                } },
                                clearTables = { withContext(Dispatchers.IO) { graph.db.clearAllTables() } },
                                finish = { graph.clearUiPreferences() }) }
                        } finally { graph.erasingData = false }
                        birth = ""; firstMode = Mode.CONDITION.name; consent0 = false; consent1 = false; consent2 = false
                        home = null; progress = null; labels = emptyMap(); resets = emptyList(); past = false
                        todayTripDay = null; dismissedLine = null; restEndMs = null; restFinished = false
                        ownerTrips = emptyList(); pendingTrips = emptyList(); selectedPromises = emptySet()
                        dismissedWeek = null; dismissedTrip = null; nextIsOther = false; stopNoticesDialog = false
                        dismissedLostWay = null; lostWayMessage = null
                        deleteDialog = false; route = Screen.FirstRun.key
                        onDataCleared()
                    } }, onBack = ::back,
                    onNewCode = { scope.launch { pairingController.newCode() } },
                    onSync = { SyncWorker.enqueue(graph.context) },
                    onDevEngine = { if (presentation) route = Screen.DevEngine.key },
                    onResume = { if (parkedAndResumed()) route = Screen.Resume.key },
                    onPromises = { if (parkedAndResumed()) route = Screen.Promises.key },
                    onFedToggle = { value -> fedAction { graph.fedClient.setOptIn(value) } },
                    onFedRound = { if (presentation) fedAction { graph.fedClient.round(System.currentTimeMillis()) } },
                    onFedRefresh = { if (presentation) fedAction { graph.fedClient.refreshModel() } })
                Screen.Resume -> ResumeScreen(resumeUi, ::back, { if (parkedAndResumed()) route = Screen.Promises.key })
                Screen.Promises -> PromiseSettings(selectedPromises, { values ->
                    if (parkedAndResumed()) {
                        graph.setPromises(values); selectedPromises = values; SyncWorker.enqueue(graph.context)
                    }
                }, ::back)
                Screen.Replay -> ReplayScreen(replayUi, replay::pause, { replay.end(); route = Screen.Settings.key }, ::openCenter)
                Screen.DevEngine -> {
                    val live by CollectorService.live.collectAsStateWithLifecycle()
                    DevEngineScreen(DevEngineUi.from(live), ::back, gleoPanel = {
                        if (ai.handleon.car.gleo.gleoMockVisible(presentation, driving)) {
                            ai.handleon.car.gleo.GleoMockPanel { if (parkedAndResumed()) route = Screen.Home.key }
                        }
                    })
                }
                Screen.Center -> CenterScreen(centerUi, { center ->
                    centerNavigator.request(center) { centerUi = centerUi.copy(routeMessage = it) }
                }, ::back)
                Screen.Letter -> LetterScreen(letter, ::back)
            }
        }
        if (screen == Screen.Home && !driving) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                sunsetLine(selectedPromises, home, today)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(if (nextIsOther) "다음 운전 다른 분 · 취소" else "다음 운전은 다른 분이 해요", {
                        if (parkedAndResumed()) { graph.setNextIsOther(!nextIsOther); nextIsOther = graph.nextIsOther() }
                    }, kind = PillKind.SECONDARY, enabled = !busy)
                    recentCorrection?.let { trip ->
                        PillButton("이번엔 다른 분이 운전했어요", { answerDriver(trip.startMs, false) }, kind = PillKind.SECONDARY, enabled = !busy)
                    }
                }
                sharingBanner(if (sync.driverId == null) 0 else sharing?.driveNoticeRecipients ?: 0)?.let { line ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = DarkTokens.ink)
                        PillButton("끄기", { if (parkedAndResumed()) stopNoticesDialog = true }, kind = PillKind.SECONDARY, enabled = !familyBusy)
                    }
                }
            }
        }
    }
    if (screen == Screen.Home && !driving && stopNoticesDialog) AlertDialog(
        onDismissRequest = { stopNoticesDialog = false },
        title = { Text("운전 마침 소식을 끌까요?") },
        text = { Text("가족에게 보내는 운전 마침 소식을 멈춰요.") },
        confirmButton = { PillButton("끄기", { familyAction {
            graph.carSync.stopDriveNotices(); stopNoticesDialog = false
            sharing = sharing?.copy(recipients = sharing!!.recipients.map { it.copy(driveDone = null) })
            sharing = graph.carSync.sharingState()
        } }, enabled = !familyBusy) },
        dismissButton = { PillButton("취소", { stopNoticesDialog = false }, kind = PillKind.SECONDARY) })
}

/** 수집 서비스를 멈추고, 마지막 트립 저장까지 끝났다는 신호를 기다린다(10초 안에 안 오면 지우지 않는다). */
private suspend fun stopCollectionBeforeErase(context: Context) {
    context.stopService(android.content.Intent(context, CollectorService::class.java))
    check(withTimeoutOrNull(10_000) { CollectorService.isRunning.first { !it } } != null) { "collector_stop_timeout" }
}
