package ai.handleon.car.ui

import kotlinx.serialization.json.*

data class SharingRecipient(val familyName: String, val scope: String, val delayDays: Int, val sharePattern: Boolean) {
    val scopeText: String get() = when (scope) {
        "stage" -> "단계만"
        "trend" -> "추이 그래프까지"
        "detail" -> "자세한 지표와 편지까지"
        else -> "공유 범위 확인 중"
    }
    val delayText: String get() = if (delayDays == 0) "바로 공유" else "${delayDays}일 뒤 공유"
    val description: String get() = listOfNotNull(familyName, scopeText,
        "운전 패턴 함께".takeIf { sharePattern }, delayText).joinToString(" · ")
}

data class SharingUi(val paused: Boolean, val recipients: List<SharingRecipient>) {
    val showHome: Boolean get() = paused || recipients.isNotEmpty()
    val status: String get() = if (paused) "가족 공유를 잠시 멈췄어요" else "가족 ${recipients.size}명과 공유 중"
    val action: String get() = if (paused) "다시 공유하기" else "잠시 멈추기"
    companion object {
        fun fromJson(value: JsonElement): SharingUi {
            val obj = value.jsonObject
            return SharingUi(obj.getValue("paused").jsonPrimitive.boolean,
                obj.getValue("recipients").jsonArray.map { item ->
                    val r = item.jsonObject
                    SharingRecipient(r.getValue("family_name").jsonPrimitive.content,
                        r.getValue("scope").jsonPrimitive.content, r.getValue("delay_days").jsonPrimitive.int,
                        r.getValue("share_pattern").jsonPrimitive.boolean)
                })
        }
    }
}

data class CarNote(val id: Long, val familyName: String, val body: String, val createdAt: String) {
    val title: String get() = "$familyName 님의 한마디"
    companion object {
        fun fromJson(value: JsonElement): CarNote = value.jsonObject.let {
            CarNote(it.getValue("id").jsonPrimitive.long, it.getValue("family_name").jsonPrimitive.content,
                it.getValue("body").jsonPrimitive.content, it.getValue("created_at").jsonPrimitive.content)
        }
    }
}

fun notesAfterSeen(notes: List<CarNote>, id: Long): List<CarNote> = notes.filterNot { it.id == id }

const val SHARING_PROMISE = "핸들온은 운전 기록을 보험사·면허 기관·광고·데이터 판매에 넘기지 않아요."
const val SHARING_PREVIEW = "가족에게 무엇이 보이는지는 휴대폰 앱의 '가족이 보는 화면'에서 그대로 볼 수 있어요."
const val LOAD_UNAVAILABLE = "지금은 불러오지 못했어요"
