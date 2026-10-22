"""표시 전용 아버지 이전 차 기록. 난수/현재 시각/외부 자료 없이 재생성한다."""
import json
from pathlib import Path

months = []
for index in range(46):
    # 1,140km에서 699km까지 완만히 감소. 재생 첫 달(2024-11, 약 687km)과 이어진다.
    distance = round(1140 - 8 * index - 0.04 * index * index, 1)
    months.append({
        "month": f"{2021 + index // 12:04d}-{index % 12 + 1:02d}",
        "distanceKm": distance,
        "drivingMinutes": round(distance * 2),
        "safeDrivingScore": None,
    })

destination = Path(__file__).resolve().parents[1] / "src/main/assets/bluelink_mock.json"
destination.write_text(json.dumps({"version": 1, "persona": "father", "months": months},
                                  ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
