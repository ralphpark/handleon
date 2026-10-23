// 배포된 dispatch-notifications 를 실제로 부른다: 대기열 → 링크 재확인 → FCM(진짜 인증) 호출.
// 기기 토큰이 가짜라 FCM 이 400 으로 거절하고, 알림은 보내지 않은 채 다음 시도로 미뤄져야 한다.
import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, integrationTest, ok, row, sharedPair } from "./_client.ts";

integrationTest("talk summary reaches FCM with real credentials and a bad token is retried later", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await family.client.rpc("register_push_token", { p_token: "not-a-real-device-token", p_platform: "android" }));
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id, { stage: "talk" })));
  const before = ok(await admin.from("notification_queue").select("id,sent_at").eq("family_id", family.id));
  assertEquals(before.length, 1);

  const response = await fetch(`${Deno.env.get("SUPABASE_URL")}/functions/v1/dispatch-notifications`, {
    method: "POST",
    headers: { Authorization: `Bearer ${Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const result = await response.json();
  assertEquals(response.status, 503, JSON.stringify(result));
  assert(result.failed >= 1);

  const [after] = ok(await admin.from("notification_queue").select("sent_at,next_attempt_at,claim_token").eq("family_id", family.id));
  assertEquals(after.sent_at, null);
  assertEquals(after.claim_token, null);
  assert(new Date(after.next_attempt_at).getTime() > Date.now(), "다음 시도가 미래로 미뤄져야 한다");
});
