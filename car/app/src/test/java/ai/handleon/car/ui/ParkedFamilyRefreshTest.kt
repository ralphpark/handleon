package ai.handleon.car.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ParkedFamilyRefreshTest {
    private val sharing = SharingUi(false, listOf(SharingRecipient("큰딸", "trend", 0, true)))
    private val notes = listOf(CarNote(1, "큰딸", "천천히 오세요", "2026-10-25T00:00:00Z"))

    @Test fun drivingAndBackgroundPerformNoReads() = runTest {
        for ((driving, resumed) in listOf(true to true, true to false, false to false)) {
            val result = refreshParkedFamily({ !driving && resumed }, { true },
                { fail("Sharing RPC must not run") }, { fail("Notes RPC must not run") })
            assertEquals(FamilyRefresh(), result)
        }
    }

    @Test fun drivingStartingDuringSharingReadSkipsNotesAndDiscardsResult() = runTest {
        var parked = true
        val result = refreshParkedFamily({ parked }, { true },
            { parked = false; sharing }, { fail("No notes once driving starts") })
        assertEquals(FamilyRefresh(), result)
    }

    @Test fun backgroundingDuringNotesReadDoesNotPublishNotes() = runTest {
        var resumed = true
        val result = refreshParkedFamily({ resumed }, { true }, { sharing }, { resumed = false; notes })
        assertEquals(FamilyRefresh(), result)
    }

    @Test fun settingsReadsOnlySharingAndHomeReadsBoth() = runTest {
        assertEquals(FamilyRefresh(sharing = sharing), refreshParkedFamily({ true }, { false },
            { sharing }, { fail("Settings must not fetch notes") }))
        assertEquals(FamilyRefresh(sharing, notes), refreshParkedFamily({ true }, { true }, { sharing }, { notes }))
    }

    @Test fun failedSharingDoesNotHideAvailableNotesOrExposeServerError() = runTest {
        val result = refreshParkedFamily({ true }, { true }, { error("private server details") }, { notes })
        assertEquals(FamilyRefresh(notes = notes, error = "지금은 불러오지 못했어요"), result)
        val failedNotes = refreshParkedFamily({ true }, { true }, { sharing }, { error("private server details") })
        assertEquals(FamilyRefresh(sharing = sharing, error = LOAD_UNAVAILABLE), failedNotes)
    }

    @Test fun cancellationPropagatesInsteadOfTryingAnotherRpc() = runTest {
        assertFailsWith<CancellationException> {
            refreshParkedFamily({ true }, { true }, { throw CancellationException() }, { fail("Cancelled") })
        }
    }
}
