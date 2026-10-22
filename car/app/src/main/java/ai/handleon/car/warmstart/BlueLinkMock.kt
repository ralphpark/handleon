package ai.handleon.car.warmstart

import android.content.Context
import ai.handleon.driveprint.YearMonthSerializer
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BlueLinkMonth(
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val distanceKm: Double,
    val drivingMinutes: Int,
    val safeDrivingScore: Int? = null,
)

/** 이전 차의 표시용 모의 기록. TripFeatures로 변환하거나 엔진에 전달하지 않는다. */
object BlueLinkMock {
    const val NOTICE = "모의 데이터 · 블루링크 연동은 현대차그룹 제휴·승인 후 제공"

    @Serializable
    private data class Document(val version: Int, val persona: String, val months: List<BlueLinkMonth>)

    fun parse(json: String): List<BlueLinkMonth> {
        val document = Json.decodeFromString<Document>(json)
        require(document.version == 1) { "지원하지 않는 모의 기록 형식입니다" }
        return document.months
    }

    fun load(context: Context): List<BlueLinkMonth> =
        context.assets.open("bluelink_mock.json").bufferedReader().use { parse(it.readText()) }
}
