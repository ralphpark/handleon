"""공공데이터포털 '전국치매센터표준데이터'(publicDataPk 15021138)를 받아 차량 앱의 assets/centers.csv 를 만든다.

포털의 CSV 내려받기 버튼이 브라우저에서 부르는 두 JSON 주소를 그대로 쓴다.
실행: python3 -I tools/centers_csv.py car/app/src/main/assets/centers.csv
"""
import csv
import json
import math
import sys
import urllib.parse
import urllib.request

PK = "15021138"
BASE = "https://www.data.go.kr/download"
REFERER = f"https://www.data.go.kr/data/{PK}/standard.do"


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": REFERER})
    with urllib.request.urlopen(req, timeout=60) as res:
        return json.load(res)


def km(a, b):
    (la1, lo1), (la2, lo2) = a, b
    p1, p2 = math.radians(la1), math.radians(la2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lo2 - lo1) / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


def main(out_path):
    header = get_json(f"{BASE}/columList.json?pk={PK}&ext=CSV")
    table = header["tableVO"]
    query = [("publicDataPk", PK), *[("colNmList", c) for c in table["colNmList"]],
             ("totalCount", header["totalCount"]), ("svcTableNm", table["svcTableNm"]),
             ("perPage", 10000), ("page", 1)]
    rows = get_json(f"{BASE}/standard.json?" + urllib.parse.urlencode(query))
    assert len(rows) == header["totalCount"], (len(rows), header["totalCount"])

    # 같은 센터를 도와 시군이 따로 올린 경우: 전화가 같고 1km 안이면 기준일이 최신인 줄만 남긴다.
    kept = []
    for r in sorted(rows, key=lambda r: r["REFERENCE_DATE"], reverse=True):
        pos = (float(r["LATITUDE"]), float(r["LONGITUDE"]))
        phone = r["OPER_PHONE_NUMBER"].strip()
        if any(k[2] == phone and km(k[3], pos) <= 1 for k in kept):
            continue
        address = (r["RDNMADR"] or r["LNMADR"]).strip()
        kept.append((r["CNTER_NM"].strip(), address, phone, pos))

    kept.sort(key=lambda k: (k[1], k[0]))
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["name", "address", "phone", "lat", "lon"])
        for name, address, phone, (lat, lon) in kept:
            w.writerow([" ".join(name.split()), " ".join(address.split()), phone, lat, lon])
    dates = sorted(r["REFERENCE_DATE"] for r in rows)
    print(f"{len(rows)}행 → {len(kept)}곳, 데이터기준일 {dates[0]} ~ {dates[-1]}")


if __name__ == "__main__":
    main(sys.argv[1])
