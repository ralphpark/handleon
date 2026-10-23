# 핸들온 휴대폰 앱

운전자와 가족이 함께 쓰는 Flutter 앱입니다. 로그인한 역할에 따라 화면이 나뉩니다.

- 운전자: 차 연결, 지난 운전 기록 가져오기, 가족 초대와 공유 관리, 내 요약
- 가족: 초대 받기, 공유된 요약 보기, 알림

Flutter 3.47.7 로 고정합니다.

## 실행에 필요한 설정

서버 주소와 공개 키는 실행할 때 넣습니다:

```
flutter run --dart-define=SUPABASE_URL=… --dart-define=SUPABASE_ANON_KEY=…
```

안드로이드 알림(FCM)에는 Firebase 설정 파일이 필요합니다. 이 파일은 저장소에 넣지 않습니다:

1. Firebase 콘솔에서 패키지 `ai.handleon.phone` 로 안드로이드 앱을 등록합니다.
2. `google-services.json` 을 내려받아 `android/app/` 에 둡니다.
   - Firebase CLI 로 받으려면: `firebase apps:sdkconfig ANDROID <앱 ID> --project <프로젝트 ID> -o android/app/google-services.json`

iOS 는 Apple 개발자 계정 없이 시뮬레이터로만 시연합니다. 알림은 `xcrun simctl push` 로 재현합니다.

## 시험

```
flutter test
flutter analyze
```

핸들온은 의료기기가 아닙니다.
