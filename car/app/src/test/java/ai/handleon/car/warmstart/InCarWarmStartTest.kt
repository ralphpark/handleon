package ai.handleon.car.warmstart

import ai.handleon.driveprint.LatLon
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.BookmarkType.HOME
import ai.pleos.playground.navi.constants.BookmarkType.OFFICE
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InCarWarmStartTest {
    @Test fun homeComesFromHomeBookmark() {
        val (home, familiar) = InCarWarmStart.places(
            listOf(bm(HOME, 37.41, 127.09), bm(OFFICE, 37.39, 127.11)), emptyList())
        assertEquals(LatLon(37.41, 127.09), home)
        assertEquals(listOf(LatLon(37.39, 127.11)), familiar)
    }

    @Test fun noHomeBookmarkGivesNullHome() {
        assertNull(InCarWarmStart.places(listOf(bm(OFFICE, 37.39, 127.11)), emptyList()).first)
    }

    @Test fun recentsBecomeFamiliar() {
        assertEquals(listOf(LatLon(37.3, 127.1), LatLon(37.5, 127.0)),
            InCarWarmStart.places(emptyList(), listOf(rd(37.3, 127.1), rd(37.5, 127.0))).second)
    }

    @Test fun firstHomeWinsAndRemainingBookmarksStayFamiliar() {
        val (home, familiar) = InCarWarmStart.places(
            listOf(bm(HOME, 37.41, 127.09), bm(HOME, 37.5, 127.0)), listOf(rd(37.3, 127.1)))
        assertEquals(LatLon(37.41, 127.09), home)
        assertEquals(listOf(LatLon(37.5, 127.0), LatLon(37.3, 127.1)), familiar)
    }

    private fun bm(category: BookmarkType, lat: Double, lon: Double) = BookmarkInfo(
        latitude = lat, longitude = lon, name = "즐겨찾기", poiId = "", poiSubId = null,
        address = "", category = category)

    private fun rd(lat: Double, lon: Double) = RecentDestinationInfo(
        latitude = lat, longitude = lon, name = "최근 목적지", address = "", poiId = "", poiSubId = null)
}
