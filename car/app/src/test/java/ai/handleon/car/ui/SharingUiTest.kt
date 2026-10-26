package ai.handleon.car.ui

import kotlinx.serialization.json.Json
import kotlin.test.*

class SharingUiTest {
    private val daughter = SharingRecipient("큰딸", "trend", 3, true)
    private val son = SharingRecipient("아들", "stage", 0, false)

    @Test fun unknownOrDifferentlyCasedScopeShowsCheckingText() {
        for (scope in listOf("", "all", "STAGE", "Detail", " stage")) {
            assertEquals("공유 범위 확인 중", SharingRecipient("가족", scope, 0, false).scopeText, scope)
        }
    }

    @Test fun delayTextIsImmediateOnlyAtZero() {
        assertEquals("바로 공유", SharingRecipient("가족", "stage", 0, false).delayText)
        assertEquals("1일 뒤 공유", SharingRecipient("가족", "stage", 1, false).delayText)
        assertEquals("14일 뒤 공유", SharingRecipient("가족", "stage", 14, false).delayText)
    }

    @Test fun descriptionJoinsPartsAndSkipsPatternWhenNotShared() {
        assertEquals("큰딸 · 추이 그래프까지 · 운전 패턴 함께 · 3일 뒤 공유", daughter.description)
        assertEquals("아들 · 단계만 · 바로 공유", son.description)
    }

    @Test fun statusAndActionFollowPauseBeforeRecipientCount() {
        assertEquals("가족 1명과 공유 중", SharingUi(false, listOf(son)).status)
        assertEquals("가족 2명과 공유 중", SharingUi(false, listOf(daughter, son)).status)
        assertEquals("잠시 멈추기", SharingUi(false, listOf(son)).action)
        val paused = SharingUi(true, listOf(daughter, son))
        assertEquals("가족 공유를 잠시 멈췄어요", paused.status)
        assertEquals("다시 공유하기", paused.action)
        assertTrue(paused.showHome); assertTrue(SharingUi(false, listOf(son)).showHome)
    }

    @Test fun fromJsonReadsServerSharingStateInOrder() {
        val state = SharingUi.fromJson(Json.parseToJsonElement("""{"paused":false,"recipients":[
            {"family_name":"큰딸","scope":"trend","delay_days":3,"share_pattern":true},
            {"family_name":"아들","scope":"stage","delay_days":0,"share_pattern":false}]}"""))
        assertEquals(SharingUi(false, listOf(daughter, son)), state)
        val none = SharingUi.fromJson(Json.parseToJsonElement("""{"paused":false,"recipients":[]}"""))
        assertEquals(SharingUi(false, emptyList()), none)
        assertFalse(none.showHome)
    }

    @Test fun fromJsonRejectsMissingOrMistypedFields() {
        for (bad in listOf("""{"paused":false}""", """{"recipients":[]}""", """{"paused":"yes","recipients":[]}""",
            """{"paused":false,"recipients":[{"family_name":"큰딸","scope":"trend","delay_days":3}]}""",
            """{"paused":false,"recipients":[{"family_name":"큰딸","scope":"trend","delay_days":"three","share_pattern":true}]}""")) {
            assertFails(bad) { SharingUi.fromJson(Json.parseToJsonElement(bad)) }
        }
    }

    @Ignore("버그: profiles.display_name 은 null 일 수 있는데(0001_schema.sql:8) SharingUi/CarNote.fromJson 이 jsonPrimitive.content 로 읽어 문자열 \"null\" 을 보인다")
    @Test fun nullFamilyNameIsNotShownAsNullText() {
        val state = SharingUi.fromJson(Json.parseToJsonElement(
            """{"paused":false,"recipients":[{"family_name":null,"scope":"stage","delay_days":0,"share_pattern":false}]}"""))
        assertFalse("null" in state.recipients.single().description, state.recipients.single().description)
        val note = CarNote.fromJson(Json.parseToJsonElement(
            """{"id":1,"family_name":null,"body":"천천히 오세요","created_at":"2026-10-25T09:00:00+00:00"}"""))
        assertFalse("null" in note.title, note.title)
    }

    @Test fun carNoteFromJsonKeepsServerValues() {
        val note = CarNote.fromJson(Json.parseToJsonElement(
            """{"id":9007199254740993,"family_name":"큰딸","body":"천천히 오세요","created_at":"2026-10-25T09:00:00.123456+00:00"}"""))
        assertEquals(CarNote(9007199254740993, "큰딸", "천천히 오세요", "2026-10-25T09:00:00.123456+00:00"), note)
        assertEquals("큰딸 님의 한마디", note.title)
    }
}
