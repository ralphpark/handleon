package ai.handleon.car.warmstart

import ai.handleon.driveprint.LatLon
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo

object InCarWarmStart {
    fun places(bookmarks: List<BookmarkInfo>, recents: List<RecentDestinationInfo>): Pair<LatLon?, List<LatLon>> {
        val homeIndex = bookmarks.indexOfFirst { it.category == BookmarkType.HOME }
        val home = bookmarks.getOrNull(homeIndex)?.let { LatLon(it.latitude, it.longitude) }
        val familiar = bookmarks.filterIndexed { index, _ -> index != homeIndex }
            .map { LatLon(it.latitude, it.longitude) } + recents.map { LatLon(it.latitude, it.longitude) }
        return home to familiar
    }
}
