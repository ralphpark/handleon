import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, integrationTest, newCar, newUser, ok, row } from "./_client.ts";
import { loadClinicContext } from "../supabase/functions/clinic-summary/index.ts";
import { ruleSafe } from "../supabase/functions/_shared/guard.ts";

integrationTest(
  "clinic reads only the owner and the six month eight week twelve week windows",
  async () => {
    const driver = await newUser("driver");
    const other = await newUser("driver");
    for (const month of ["2026-04-01", "2026-05-01", "2026-10-01", "2026-11-01"]) {
      ok(
        await admin.from("monthly_summaries").upsert(
          row(driver.id, { month, top: [{ feature: "W3", grade: "C", text: "PRIVATE_PLACE" }] }),
        ),
      );
    }
    ok(await admin.from("monthly_summaries").upsert(row(other.id, { stage: "talk" })));
    for (
      const week of [
        "2026-08-03",
        "2026-08-10",
        "2026-08-31",
        "2026-09-07",
        "2026-10-26",
        "2026-11-02",
      ]
    ) {
      ok(
        await admin.from("weekly_changes").insert({
          driver_id: driver.id,
          week_start: week,
          flagged: true,
          domains: ["MOBILITY"],
          top: [{ feature: "M1", direction: "down" }],
          source: "car",
        }),
      );
      ok(
        await admin.from("weekly_habits").insert({
          driver_id: driver.id,
          week_start: week,
          trips: 2,
          minutes: 30,
          signal_kept: 2,
          signal_total: 3,
          hard_brakes: 0,
          hard_accels: 0,
          calm_trips: 2,
          long_stretches: 0,
          night_trips: 0,
          promises_kept: 0,
          promises_total: 0,
          story: "",
          source: "car",
        }),
      );
    }
    const context = await loadClinicContext(admin, driver.id, new Date("2026-10-27T00:00:00Z")) as {
      monthly: { month: string; stage: string }[];
      habits: { week_start: string }[];
      weekly: { week_start: string }[];
    };
    assertEquals(context.monthly.map((r) => r.month), ["2026-10-01", "2026-05-01"]);
    assertEquals(context.habits.map((r) => r.week_start), ["2026-10-26", "2026-09-07"]);
    assertEquals(context.weekly.map((r) => r.week_start), [
      "2026-10-26",
      "2026-09-07",
      "2026-08-31",
      "2026-08-10",
    ]);
    assert(context.monthly.every((r) => r.stage === "stable"));
    assert(!JSON.stringify(context).includes("PRIVATE"));
  },
);
integrationTest("live clinic refuses family and car callers", async () => {
  for (const actor of [await newUser("family"), await newCar()]) {
    const { error } = await actor.client.functions.invoke("clinic-summary", { body: {} });
    assert(error);
    const response = (error as { context?: Response }).context;
    assertEquals(response?.status, 403);
    await response?.text();
  }
});
integrationTest("live driver clinic returns a safe one-page summary", async () => {
  const driver = await newUser("driver");
  const value = ok(await driver.client.functions.invoke("clinic-summary", { body: {} }));
  assertEquals(value.source, "ai", "실제 모델·판정 연결 확인");
  assert(value.summary.length <= 600);
  assertEquals(value.questions.length, 3);
  assert([value.summary, ...value.questions].every(ruleSafe));
});
