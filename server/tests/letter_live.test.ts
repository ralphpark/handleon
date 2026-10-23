// 실제 배포된 monthly-letter 함수를 연결된 차로 부른다(Anthropic 호출, 실비용 소액). 파일별로 따로 돌린다.
import { assert, assertEquals } from "jsr:@std/assert@1";
import { isSafe } from "../supabase/functions/_shared/letter.ts";
import { admin, integrationTest, linkedPair, ok, seedMonth } from "./_client.ts";

integrationTest("linked car gets a model-written safe letter saved to its month", async () => {
  const { driver, car } = await linkedPair();
  await seedMonth(driver.id, { stage: "talk" });
  const body = {
    month: "2026-10-01",
    mode: "maeum",
    stage: "talk",
    top: [
      { feature: "M5", direction: "down", grade: "A" },
      { feature: "W3", direction: "up", grade: "C" },
      { feature: "O8", direction: "up", grade: "C" },
    ],
  };
  const { data, error } = await car.client.functions.invoke("monthly-letter", { body });
  assertEquals(error, null);
  assertEquals(data.fallback, false, "모델 호출이 실패해 고정 편지로 바뀌었다");
  assert(isSafe(data.letter));
  assert(data.letter.trim().endsWith("핸들온 드림"));
  const saved = ok(
    await admin.from("monthly_summaries").select("letter").eq("driver_id", driver.id).eq("month", "2026-10-01").single(),
  );
  assertEquals(saved.letter, data.letter);
  console.log("편지:\n" + data.letter);
});

integrationTest("unlinked car is refused", async () => {
  const { car } = await linkedPair();
  ok(await admin.from("car_links").delete().eq("car_id", car.id));
  const { error } = await car.client.functions.invoke("monthly-letter", {
    body: { month: "2026-10-01", mode: "maeum", stage: "talk", top: [] },
  });
  assert(error);
  // 오류 응답 본문을 읽어 닫는다(Deno 자원 누수 검사)
  const response = (error as { context?: Response }).context;
  assertEquals(response?.status, 403);
  await response?.text();
});
