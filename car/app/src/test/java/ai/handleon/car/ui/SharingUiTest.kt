package ai.handleon.car.ui

import kotlinx.serialization.json.Json
import kotlin.test.*

class SharingUiTest {
    @Test fun missingDisplayNameFallsBackToFamily() {
        // profiles.display_name may be null; the phone shows '가족' for it too.
        val state = SharingUi.fromJson(Json.parseToJsonElement(
            """{"paused":false,"recipients":[{"family_name":null,"scope":"stage","delay_days":0,"share_pattern":false}]}"""))
        assertEquals("가족 · 단계만 · 바로 공유", state.recipients.single().description)
        val note = CarNote.fromJson(Json.parseToJsonElement(
            """{"id":1,"family_name":null,"body":"천천히 오세요","created_at":"2026-10-25T00:00:00+00:00"}"""))
        assertEquals("가족 님의 한마디", note.title)
    }
}
