#!/bin/bash
# 에뮬레이터 주행 재현: Virtual Console(HTTP 8088)로 기어·페달·핸들을, adb emu geo fix 로 GPS 를 움직인다.
# 쓰는 법: tools/drive.sh [초=150] [방향지시등 쓰기: signal] [와이퍼: wiper]
#   예) tools/drive.sh 150        판교에서 동쪽으로 약 150초 주행 후 정차·P
# 필요: 에뮬레이터 켜짐(tools/run_emulator.sh 등), adb 가 PATH 에 있음.
set -u
SECS=${1:-150}
VC=http://127.0.0.1:8088
# 휴대폰 에뮬레이터나 실기기가 함께 연결돼 있어도 Pleos 에뮬레이터에만 보낸다(ANDROID_SERIAL 을 주면 그것을 쓴다).
if [ -z "${ANDROID_SERIAL:-}" ]; then
  for s in $(adb devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}'); do
    [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "Pleos_Connect_v2_1_0" ] && export ANDROID_SERIAL=$s
  done
fi
adb forward tcp:8088 tcp:8088 >/dev/null
# 콘솔은 브라우저가 3초마다 보내는 세션 신호가 있어야 차를 움직인다(없으면 emulator_inactive)
beat() { curl -s -o /dev/null -X POST "$VC/browser/session?client=drive-sh"; }
put() { beat; curl -s -o /dev/null -X PUT "$VC/$1"; }

LAT=37.4000; LON=127.1000            # 출발점(판교 근처, 가짜 좌표)
STEP=0.00011                          # 1초에 경도 약 10m (약 36km/h)
adb emu geo fix $LON $LAT >/dev/null
put "vehicle/gear?gear=Drive"
put "vehicle/controls?speed=36&accelerator=30&brake=0&steering=0"   # speed 를 같이 줘야 속도가 바뀐다(가속 페달만으로는 0)
for ((i = 1; i <= SECS; i++)); do
  LON=$(python3 -I -c "print(round($LON + $STEP, 6))")
  adb emu geo fix $LON $LAT >/dev/null
  beat
  sleep 1
done
put "vehicle/controls?speed=0&accelerator=0&brake=60&steering=0"
for ((i = 0; i < 8; i++)); do beat; sleep 1; done
put "vehicle/gear?gear=Park"
put "vehicle/controls?speed=0&accelerator=0&brake=0&steering=0"
curl -s -o /dev/null -X POST "$VC/browser/session?client=drive-sh&closing=1"
echo "주행 끝: ${SECS}초, 마지막 위치 $LAT, $LON"
