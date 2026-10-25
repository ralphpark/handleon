# 핸들온 휴대폰 앱

운전자와 가족이 함께 쓰는 Flutter 앱입니다. 로그인한 역할에 따라 화면이 나뉩니다.

- 운전자: 차 연결, 지난 운전 기록 가져오기, 가족 초대와 공유 관리(받는 사람마다 범위·시점·운전 패턴 공유, 전체 일시중지, 공유 끊기), "가족이 보는 화면" 미리보기, 내 요약
- 가족: 초대 받기, 공유된 요약과 운전 패턴 보기, 운전자에게 한마디 보내기(차가 멈춘 뒤에만 차 화면에 보임), 알림

가족을 초대할 때 운전자는 공유 범위(단계만 / 추이 그래프까지 / 자세한 지표와 편지까지), 시점(바로 / 3·7·14일 뒤), 운전 패턴 공유 여부를 모두 골라야 하며, 기본값은 없습니다.

지난 운전 기록 가져오기는 구글 지도 타임라인 내보내기 JSON 을 휴대폰에서 읽어, 차로 이동한 구간(300 m 이상)의 출발·도착 시각과 위치(소수 셋째 자리로 반올림), 거리만 서버의 비공개 `imports` 폴더에 올립니다. 연결된 차가 받아 가면 서버에서 지웁니다.

Flutter 3.47.7 로 고정합니다.

## 실행에 필요한 설정

서버 주소와 공개 키는 실행할 때 넣습니다(기본값이 없어 넣지 않으면 '서버 설정이 없어요'가 나옵니다):

```
flutter run --dart-define=SUPABASE_URL=… --dart-define=SUPABASE_ANON_KEY=…
flutter build apk --release --target-platform android-arm64,android-x64 --dart-define=SUPABASE_URL=… --dart-define=SUPABASE_ANON_KEY=…
```

APK 를 빌드할 때도 꼭 넣습니다.

안드로이드는 Firebase 설정 파일이 있어야 빌드됩니다(`com.google.gms.google-services` 플러그인을 늘 적용합니다). 이 파일은 저장소에 넣지 않습니다:

1. Firebase 콘솔에서 패키지 `ai.handleon.phone` 로 안드로이드 앱을 등록합니다.
2. `google-services.json` 을 내려받아 `android/app/` 에 둡니다.
   - Firebase CLI 로 받으려면: `firebase apps:sdkconfig ANDROID <앱 ID> --project <프로젝트 ID> -o android/app/google-services.json`

release 서명은 `android/key.properties`(저장소에 없음, `storeFile`·`storePassword`·`keyAlias`·`keyPassword`)를 읽습니다. 없으면 디버그 키로 서명합니다.

iOS 는 Apple 개발자 계정 없이 시뮬레이터로만 시연합니다. FCM 토큰은 안드로이드에서만 등록하므로 iOS 는 서버가 보내는 알림을 받지 않습니다. 알림 모양은 `xcrun simctl push booted tools/sim_push.apns` 로 재현합니다.

## 시험

```
flutter test
flutter analyze
```

핸들온은 의료기기가 아닙니다.
