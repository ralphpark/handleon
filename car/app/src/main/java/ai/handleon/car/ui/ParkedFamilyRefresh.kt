package ai.handleon.car.ui

import kotlinx.coroutines.CancellationException

data class FamilyRefresh(val sharing: SharingUi? = null, val notes: List<CarNote>? = null, val error: String? = null)

/** Recheck live parking/lifecycle gates between RPCs, not just when the screen effect starts. */
suspend fun refreshParkedFamily(
    canRead: () -> Boolean,
    homeVisible: () -> Boolean,
    readSharing: suspend () -> SharingUi?,
    readNotes: suspend () -> List<CarNote>?,
): FamilyRefresh {
    if (!canRead()) return FamilyRefresh()
    var error: String? = null
    val sharing = try { readSharing() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { error = LOAD_UNAVAILABLE; null }
    if (!canRead()) return FamilyRefresh()
    val notes = if (homeVisible()) try { readNotes() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = LOAD_UNAVAILABLE; null }
    else null
    return if (canRead()) FamilyRefresh(sharing, notes, error) else FamilyRefresh()
}
