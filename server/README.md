# 핸들온 서버

차량의 월·일 집계를 저장하고 운전자가 선택한 범위와 시점으로 가족에게 공유하는 Supabase 서버입니다.
원본 위치·트립은 요약 테이블에 저장하지 않습니다. 예외는 `imports` 버킷의 과거 기록 파일(휴대폰이 구글
타임라인 내보내기에서 뽑은 지난 운전의 출발·도착 시각과 위치, 거리)로, 연결된 차량이 받은 직후
삭제합니다.

## 상태

`0001`~`0008` 마이그레이션과 두 Edge Function 을 개발용 Supabase 프로젝트에 적용해 차량 앱·휴대폰 앱과 함께
실서버로 확인했습니다(차 연결, 월 요약, 실제 Claude 월간 편지, 가족 공유·피드, FCM 푸시, 과거 기록 가져오기,
공유 일시중지·모두 끊기, 운전 패턴 공유, 가족이 보는 화면 미리보기, 가족 한마디).

## 구성

- `supabase/migrations/0001_schema.sql`: 데이터 계약, JSON 형식 검사, RLS, 서버 수신 시각.
- `0002_rpc.sql`: 익명 차량 페어링, 가족 초대·공유 변경·철회·피드, 푸시 토큰, 편지 저장 RPC.
- `0003_notifications.sql`: 단계 변경 알림 큐(트리거)와 발송 cron(처음에는 10분마다).
- `0004_storage.sql`: 비공개 JSON 5 MB `imports` 버킷과 폴더별 정책.
- `0005_hardening.sql`: 보안 점검 반영(`valid_summary_json` 은 invoker 로, `save_monthly_letter` 는 서비스
  롤 전용으로).
- `0006_phone_screens.sql`: 휴대폰 화면용 RPC(내 공유 목록 `get_my_shares`, 초대 미리보기
  `preview_invite`, 공유 범위를 함께 돌려주는 `get_family_feed`).
- `0007_dispatch_every_minute.sql`: 발송 cron 을 1분마다(“바로 공유”가 1분 안에 도착).
- `0008_share_controls_patterns_notes.sql`: 공유 통제와 새 공유 항목.
  - 받는 사람별 `share_pattern`(운전 패턴 공유 여부). 초대·수정 RPC 가 값을 반드시 받습니다(기본값 없음).
  - 운전자 전체 일시중지(`set_sharing_paused`)와 모두 끊기(`revoke_all_shares`). 휴대폰의 운전자와
    연결된 차 어디서나 부릅니다. 멈춘 동안 가족 피드·패턴이 비고, 대기 알림은 취소되며 새 알림도 만들지 않습니다.
  - `monthly_patterns`: 월별 운전 횟수·시간·운전한 날·밤/장거리 비율, 생활반경·가는 곳 가짓수는 본인 기준
    대비 비율로만. 지명·좌표·km 값 열이 없고, 범위 검사로 값을 묶습니다.
  - 가족 화면 행은 `family_feed_for`·`family_patterns_for` 두 내부 함수만 만듭니다. 가족 피드와 운전자의
    `preview_family_view`(가족이 보는 화면)가 같은 함수를 써서 결과가 같습니다.
  - `family_notes`: 가족 → 운전자 한마디(120자, 읽지 않은 것 가족당 3개까지, 90일 뒤 cron 으로 삭제).
- `0009_revoke_all_ends_invites.sql`: 모두 끊기(`revoke_all_shares`)가 아직 쓰지 않은 초대 코드도 끝냅니다(다시
  나누려면 새로 초대).
- `0010_expire_stale_notifications.sql`: 7일이 지나도 못 보낸 알림을 매일 취소하는 cron.
- `0011_share_options_drive_events.sql`: 받는 사람별 새 공유 선택(기본 꺼짐), 위치 없는 나들이 종료 집계,
  종류별 알림 큐. `drive_done`은 마지막 종료 30분 뒤, `drive_evening`은 KST 20시에 보냅니다.
- `0012_weekly_changes_habits.sql`: 주간 변화·습관 집계, 가족용 최근 12주 조회와 `weekly_change` 알림.
- `0013_weekly_quiet_weeks_stop_drive.sql`: 평소와 같은 주는 영역 없이 올릴 수 있음(표시된 주는 영역 1개 이상), 차가 모든 가족의 운전 마침 소식을 한 번에 끄는 `stop_drive_notices()`.
  두 마이그레이션은 파일만 작성했으며 원격 적용·통합 검증은 아직 하지 않았습니다.
- `supabase/functions/monthly-letter/index.ts`: 차량 인증 → 편지 생성 → 해당 운전자의 월 요약 저장.
- `supabase/functions/dispatch-notifications/index.ts`: 서비스 롤 인증 → 큐 조회 → 현재 동의 재확인
  → FCM.
- `_shared/letter.ts`, `_shared/fcm.ts`, `_shared/dispatch.ts`, `_shared/auth.ts`: 가짜 fetch·데이터로
  시험하는 로직(`auth.ts` 는 발송 함수 호출자가 서비스 롤인지 확인).
- `_shared/dispatch-store.ts`: 발송 로직과 실서버 시험이 공유하는 DB 어댑터.
- `tests/_client.ts`: 사용자 생성, 익명 차량, 페어링·공유·월 요약 시드, 시험 후 사용자 정리.

## 오프라인 검증

의존성은 `jsr:@std/assert@1`, `npm:@supabase/supabase-js@2`, `npm:@anthropic-ai/sdk@1`뿐이고
`deno.lock` 으로 버전을 고정합니다. Deno 캐시는 저장소에 들어 있지 않으므로 처음 한 번은 의존성을
내려받아야 합니다(jsr.io, npm 레지스트리). 그 뒤로는 `deno test` 에 `--cached-only` 를 붙여 네트워크 없이
돌릴 수 있습니다.

```sh
cd server
deno test -A --frozen tests/letter.test.ts tests/fcm.test.ts tests/auth.test.ts
deno check --frozen supabase/functions/_shared/*.ts supabase/functions/monthly-letter/index.ts supabase/functions/dispatch-notifications/index.ts tests/*.ts
```

순수 시험은 실제 키나 네트워크 서비스가 필요하지 않습니다. RSA 키는 메모리에서 생성하고 외부 요청은 가짜
fetch로 대체합니다. 편지 제한 시간 시험은 실제로 약 20초 걸립니다. 통합 시험은 필수 환경변수 세 개 중 하나라도
없으면 클라이언트를 생성하지 않고 `ignored`로 건너뜁니다. 타입 검사는 모듈을 실행하지 않으므로 비밀값 없이 가능합니다.

Deno 2.7.12 의 `check` 에는 `--cached-only` 옵션이 없습니다. 캐시만으로 타입 검사를 하려면 위 `check`
명령에 `--deny-import` 를 더해 원격 import 를 막습니다.

초기 오프라인 결과(Deno 2.7.12): **26개 통과, 0개 실패**(편지 11개, FCM·dispatch 13개, 발송 호출자 검사
2개), 모든 함수·실서버 시험을 포함한 **18개 TypeScript 파일의 `deno check` 오류 0개**.

S1·S2 최종 캐시 전용 전체 시험: **95개 통과, 0개 실패, 통합 66개 건너뜀**. 새 시험 이름과 결정·권한은
[S1·S2 구현 보고](S1_S2_REPORT.md)에 있습니다. 원격 통합 시험의 통과를 뜻하지 않습니다.

## S1·S2 데이터 계약과 RPC

| RPC | 호출자 / 동작 |
| --- | --- |
| `set_share_options(uuid,text,boolean,boolean)` | 본인 운전자·연결된 차. `p_drive_done=null`은 끄기, `each`/`evening`은 직접 선택. 두 boolean의 null은 `choice_required`. |
| `report_drive_end(text,timestamptz,integer,boolean)` | 본인 운전자·연결된 차. 나들이 숫자 키, 종료 시각, 1~1440분, 쉼 여부만 저장. 5분 넘는 미래는 거절. |
| `retract_drive(text)` | 본인 운전자·연결된 차. 카드와 대기 알림을 거둡니다. 재전송해도 철회는 유지됩니다. |
| `get_family_drive_events()` | 가족만. 켜진 공유의 최근 14일 기록, 유예 적용 없음. |
| `get_family_weekly()` / `get_family_habits()` | 가족만. 해당 항목 동의·링크·일시중지·유예를 검사한 최근 12주 집계. |
| 기존 `get_sharing_state()` / `preview_family_view(uuid)` | 본인 운전자·연결된 차. 공유 선택과 실제 가족 화면을 반환. |

위 공개 RPC는 `authenticated`에만 실행 권한을 주며 함수 안에서 역할·소유자를 확인합니다.
`family_drive_events_for`, `family_weekly_for`, `family_habits_for`와 enqueue 트리거는 앱 호출 권한이 없습니다.
순수 검사 `valid_weekly_json(jsonb,jsonb)`만 authenticated/service_role에 실행을 허용합니다.
`drive_evening_date(timestamptz)`·`kst_week_start(timestamptz)`는 서비스 롤 및 내부 함수용입니다. 새 definer 함수의 `search_path`는 빈 값입니다.
`share_options`·`drive_events`는 RLS 정책 없이 RPC로만 접근합니다. 주간 표는 운전자 본인·연결된 차만
SELECT/INSERT/UPDATE할 수 있고 가족은 위 RPC로만 읽습니다.

- 알림 종류: 기존 `stage`, `drive_done`, `drive_evening`, `weekly_change`. 실제 FCM 전송은
  모두 `data.kind`와 해당 한국어 문구를 포함하며 전송 직전과 기기 사이에 동의를 다시 읽습니다.
- `drive_done`은 6시간 넘은 기록을 보내지 않습니다. 오래된 업로드는 더 최근 나들이의 큐를 밀어내지
  않고, 같은 내용 재전송은 큐를 추가하지 않습니다. 새 초대를 수락하면 새 공유 선택은 다시 꺼짐입니다.
- 저녁 모음은 **전날 20:00 이상~당일 20:00 미만(KST)**의 철회되지 않은 기록을 셉니다.
  정확히 20:00부터 다음 날 모음입니다. 의도적으로 다음 날 보내는 저녁 모음에는 6시간 제한을 적용하지
  않습니다. 대신 **예정 20시부터 6시간**이 지나면 발송을 거부하고, 이미 지난 버킷의 늦은 업로드도
  큐를 만들지 않습니다. 큐의 `month`도 KST 월이며, 기존 매분 cron·7일 만료 작업을 재사용합니다.
- 월간 요약 FK는 생성 열 `stage_month`에 걸어 기존 stage 삭제 연쇄를 유지합니다. 다른 알림은 월간
  요약 없이도 저장할 수 있습니다. 월간 트리거와 공유 유예 수정은 종류별로만 큐를 갱신합니다.
- 주간 `week_start`는 KST 월요일 날짜이며 현재 주를 포함한 12주를 반환합니다. 유예는 수신 시각부터
  정확한 24시간 단위입니다. 내용 변경은 서버 시각으로 유예를 다시 시작하고 동일 재전송은 보존합니다.
- `weekly_changes.domains`: `MOBILITY`/`WAYFINDING`/`OPERATION` 배열(1~3개).
  `top`: 최대 3개의 `{ "feature": "M1", "direction": "down" }` 객체입니다.
  feature는 기존 지표 ID 허용 목록, direction은 `up`/`down`만 받습니다. 자유문장·추가 키는 거절합니다.
- `weekly_habits.story`: 60자 이하. 좌표 및 장소 이름이 들어갈 자유 입력을 막기 위해 빈 문자열 또는
  `이번 주 운전 기록을 함께 돌아보세요`, `이번 주에도 쉬어 가며 운전했어요`, `방향지시등을 모두 켰어요`,
  `운전 약속을 모두 지켰어요`, `이번 주 N번 운전했어요`(N은 1~3자리 숫자)만 허용합니다.
- 미리보기의 `drive_events`, `weekly`, `habits`는 데이터 배열입니다. S1의 boolean `habits`와 이름이
  겹치므로 선택 값은 `options.{drive_done,weekly_change,habits}`로 모두 제공합니다. 수신자 객체의
  `habits`는 기존 계획대로 boolean입니다.

원격 적용 시 0011 → 0012 순서로 적용한 뒤 `dispatch-notifications`를 다시 배포해야 합니다.
`drive_events`·`weekly`·기존 `controls`/`notify` 통합 시험, RLS/ACL, 조건부 FK, 수신 시각 트리거,
KST 20시·월요일 경계, 실제 기기의 `data.kind` 화면 이동을 확인합니다.

```sh
DENO_DIR=../.cache/deno deno test -A --frozen --cached-only tests/
```

## 새 프로젝트에 적용하는 순서

1. 승인된 개발용 Supabase 프로젝트에서 익명 로그인 활성화, 이메일 로그인 설정 확인. `0001`부터
   `0013`까지 순서대로 적용합니다. **아래 SQL 전제와 RLS를 실서버에서 먼저 확인합니다.**
2. 로컬 `server/.env.test`에 `.env.test.example`의 세 이름에 대응하는 값을 직접 설정합니다. 이
   파일은 저장소 루트 `.gitignore`에서 이미 제외됩니다. 파일·토큰 내용을 로그에 출력하지 않습니다.
3. Vault `vault.decrypted_secrets`에 `service_role_key`, `project_url` 이름의 비밀값이 각각 하나씩
   있어야 합니다. `service_role_key`는 Edge 환경의 `SUPABASE_SERVICE_ROLE_KEY`와 같은 서비스 롤
   JWT여야 합니다. 값이 없으면 cron은 HTTP 요청을 만들지 않습니다.
4. Edge Secrets에 `ANTHROPIC_API_KEY`, `FCM_SERVICE_ACCOUNT_JSON`을 설정합니다. Supabase의
   `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`도 필요합니다. Firebase HTTP v1 활성화와 서비스 계정의
   FCM 발송 권한을 확인합니다. 키·서비스 계정 JSON은 저장소에 넣지 않습니다.
5. 두 Edge Function을 배포합니다. `monthly-letter`는 내부에서 반드시 `auth.getUser(jwt)`로
   검증합니다. `dispatch-notifications`는 서비스 롤 Bearer만 허용합니다. 게이트웨이 JWT 검증 설정을
   실제 토큰 형식에 맞게 확인하고, 일반 authenticated JWT가 dispatch를 호출하면 401인지 검증합니다.
6. 아래 통합 시험을 **개발 프로젝트에서만** 실행합니다. 사용자/익명 계정 생성과 삭제, Storage
   업로드와 삭제가 발생합니다. `notify.test.ts` 는 실제 DB와 가짜 전송 함수를 사용하므로 FCM 기기로
   보내지 않습니다. 실행 도중 종료하면 시험 계정이나 파일이 남을 수 있으므로 개발 프로젝트에서 정리합니다.

```sh
deno test -A --frozen --env-file=.env.test tests/smoke.test.ts tests/rls.test.ts tests/rpc.test.ts tests/notify.test.ts tests/storage.test.ts tests/controls.test.ts tests/drive_events.test.ts tests/weekly.test.ts
```

   배포된 함수를 실제로 부르는 시험은 파일마다 따로 돌립니다(같은 `--env-file=.env.test` 필요).
   `tests/letter_live.test.ts` 는 연결된 차로 `monthly-letter` 를 불러 Anthropic API 를 실제로
   호출하고(소액 비용), 연결이 끊긴 차가 403 으로 거부되는지 봅니다. `tests/dispatch_live.test.ts` 는
   `dispatch-notifications` 를 불러 실제 FCM 인증까지 거친 뒤, 가짜 기기 토큰이 거절되면 알림을
   보낸 것으로 기록하지 않고 다음 시도로 미루는지 봅니다.

   익명 가입은 Supabase 기본 제한(IP 당 시간당 횟수)이 있어, 전체를 한 번에 돌리면 뒤쪽 시험이 `Request rate limit reached`
   로 실패할 수 있습니다. 그때는 한 시간 뒤 남은 파일만 다시 돌립니다.

7. Supabase security advisor, SQL 함수 ACL·`security definer`·고정 `search_path`, 모든 테이블 RLS 및
   `storage.objects` RLS 활성화를 검사합니다. `pgcrypto`의 `gen_random_bytes`가 `extensions`
   스키마에 있어야 합니다. `pg_cron`, `pg_net`, Vault 사용 가능 여부와 cron job/HTTP 응답 기록도
   확인합니다. 이 작업은 정적 타입 검사로 증명되지 않습니다.
8. `letter_live.test.ts` 가 다루지 않는 운전자·가족 계정의 편지 호출 거부와 다른 운전자 월의 미변경은
   별도의 승인된 실호출로 확인합니다. 실제 FCM/APNs 수신은 기기로 확인합니다.

## 계획에서 보완한 결정

- `monthly_summaries`와 `daily_conditions` SELECT에 본인 운전자 외에 **연결된 차량**을 포함했습니다.
  PostgreSQL `ON CONFLICT DO UPDATE`의 RLS에 SELECT 가시성이 필요합니다. 가족과 다른 차량에는 보이지
  않습니다.
- 익명 계정은 `profiles`를 만들 수 없습니다. 차가 스스로 driver로 등록해 역할 검사를 우회하지 못하게
  합니다. 실제 RPC는 호출자 역할을 확인하고 코드 행을 잠가 재사용과 동시 수락을 막습니다.
- 인증된 차량이 보낸 `created_at`을 신뢰하지 않습니다. 최초 업로드·집계 내용 변경은 서버 수신 시각을
  사용하고, 같은 내용 재전송과 편지 저장은 시각을 보존합니다. 내용 변경은 편지를 지워 재생성하게
  하고 유예도 다시 시작합니다. 서비스 롤 시드는 경계 시험을 위해 시각을 지정할 수 있습니다.
- 유예는 1일 = 정확히 24시간으로 계산합니다. 한국 날짜/월 경계는 차량에서 Asia/Seoul 기준으로
  정하고, 서버에는 월 1일 date와 UTC timestamptz를 저장합니다.
- 큐에 `month`, `created_at`, `claim_token`, `claimed_at`, `next_attempt_at`을 추가했습니다. 현재
  요약과 공유 시점을 재검사하고, 공유 시점 변경은 대기 큐도 재계산합니다. 5분 claim은 겹친 cron의
  중복 실행을 줄이며 실패 시 해제됩니다. 실패·토큰 없음은 10분 뒤로 미루고 오래 시도하지 않은 큐부터
  고르므로 앞선 실패 20건이 뒤 알림을 계속 막지 않습니다.
- `save_monthly_letter` 내부 RPC는 서비스 롤만 허용합니다. 차량 연결 행을 잠그고 현재 운전자와 요약
  버전을 확인한 같은 트랜잭션에서 편지를 저장하므로, 생성 중 재페어링이 완료되면 저장을 거부합니다.
- 새 초대 수락은 이전 동의의 미발송 큐를 취소합니다. 철회 시 미발송 큐는 취소하며 새 초대가 이를
  부활시키지 않습니다. 직접 테이블 쓰기는 차단합니다.
- SDK의 재시도별 timeout 외에 **전체 호출 20초 제한**을 두었습니다. 금지어 8개, 거절·빈
  응답·예외·시간 초과는 안전한 고정 편지로 바꿉니다. 위치 필드와 자유 입력 지표는 요청 검사 및
  명시적 프롬프트 필드 선택으로 제외합니다.
- 테스트 편의를 위해 실서버 시험이 공유하는 dispatch DB 어댑터를 추가했습니다. 실제 SQL 런타임
  확인을 대신하는 모의 SQL 시험은 만들지 않았습니다.

## 실서버에서 확인한 것

개발용 Supabase 프로젝트에서 확인한 내용입니다.

- `0001`~`0005` 적용 후 RLS·트리거·RPC·알림 큐 통합 시험(`smoke`·`rls`·`rpc`·`notify`)을 실제
  PostgreSQL 에서 통과했습니다. `0006` 적용 후 `rpc.test.ts` 를 다시 통과했습니다.
- `letter_live.test.ts`: 연결된 차의 모델 편지 저장과 연결이 끊긴 차의 거부.
- `dispatch_live.test.ts`: 대화 권유 요약 → 알림 큐 → 실제 FCM 인증 → 가짜 기기 토큰 거절 시 재시도 예약.
- `0007` 적용 후 차의 요약 업로드에서 가족 휴대폰 푸시 수신까지 1분 안에 도착했습니다(pg_cron·pg_net·Vault
  경로 포함).
- Storage 정책은 휴대폰 업로드 → 차 다운로드·삭제(과거 기록 가져오기)로 확인했습니다. `storage.test.ts` 는
  실서버에서 익명 가입 속도 제한 때문에 돌리지 못했습니다.

## 남은 한계

- 철회가 마지막 DB 확인보다 먼저 완료되면 전송은 0회입니다. 마지막 확인과 외부 FCM 요청 사이의 동시
  철회, 이미 FCM에 인계된 메시지는 PostgreSQL 트랜잭션만으로 회수할 수 없습니다. 실제 동시성
  시험으로 경계를 확인해야 합니다.
- FCM 수락 후 `sent_at` 기록 전에 프로세스가 죽거나, 일시적 오류(5xx 등)로 여러 토큰 중 일부만 실패하면
  재시도에서 중복 알림이 생길 수 있습니다. 정확히 한 번 발송을 보장하지 않습니다. FCM 이 등록 해제(404
  UNREGISTERED)라고 답한 토큰은 그 자리에서 지우고 다른 기기로 이어 보내므로, 꺼진 기기 때문에 같은
  알림이 반복되지는 않습니다. 보낼 기기가 없거나 계속 실패해 7일이 지난 알림은 매일 cron 으로 취소합니다(`0010`).
- 버킷 `on conflict do nothing`은 기존 버킷 설정을 변경하지 않습니다. 이미 `imports`가 있다면
  비공개·5 MB·JSON 제한을 직접 확인합니다. `imports` 파일은 연결된 차량이 가져갈 때 지웁니다. 서버에는
  남은 파일을 시간이 지나면 지우는 작업이 따로 없습니다.
