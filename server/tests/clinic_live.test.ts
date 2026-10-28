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
// 2026-10-28: the "아버지" replay (talk month, eight habit weeks) always fell back.
integrationTest("live clinic writes an AI summary for a talk month", async () => {
  const driver = await newUser("driver");
  ok(
    await admin.from("monthly_summaries").upsert(row(driver.id, {
      stage: "talk",
      domains: { mobility: 1.132, operation: -0.022, wayfinding: 1.612 },
      top: [
        {
          feature: "M3",
          grade: "A",
          text: "가까운 동네를 벗어나 다녀오시는 일이 평소보다 줄었어요",
        },
        { feature: "M1", grade: "A", text: "차로 외출하시는 횟수가 평소보다 줄었어요" },
        { feature: "M7", grade: "A", text: "집에서 다녀오시는 거리가 평소보다 가까워졌어요" },
      ],
    })),
  );
  const habits: [string, number, number, number, number, number, number][] = [
    ["2026-09-07", 10, 216, 7, 9, 5, 5],
    ["2026-09-14", 9, 157, 8, 11, 3, 6],
    ["2026-09-21", 6, 358, 3, 5, 7, 3],
    ["2026-09-28", 4, 55, 4, 4, 0, 4],
    ["2026-10-05", 10, 190, 8, 11, 4, 6],
    ["2026-10-12", 6, 88, 5, 8, 0, 6],
    ["2026-10-19", 9, 191, 9, 11, 2, 7],
    ["2026-10-26", 6, 135, 4, 6, 5, 1],
  ];
  for (
    const [week_start, trips, minutes, signal_kept, signal_total, hard_brakes, calm_trips] of habits
  ) {
    ok(
      await admin.from("weekly_habits").insert({
        driver_id: driver.id,
        week_start,
        trips,
        minutes,
        signal_kept,
        signal_total,
        hard_brakes,
        hard_accels: 0,
        calm_trips,
        long_stretches: 0,
        night_trips: 0,
        promises_kept: 0,
        promises_total: 0,
        story: "",
        source: "car",
      }),
    );
  }
  for (
    const [week_start, top] of [
      ["2026-09-28", []],
      ["2026-10-12", []],
      ["2026-10-19", [{ feature: "M4", direction: "down" }, { feature: "M3", direction: "down" }, {
        feature: "W4",
        direction: "up",
      }]],
      ["2026-10-26", [{ feature: "M1", direction: "down" }, { feature: "M7", direction: "down" }]],
    ] as const
  ) {
    ok(
      await admin.from("weekly_changes").insert({
        driver_id: driver.id,
        week_start,
        flagged: false,
        domains: [],
        top,
        source: "car",
      }),
    );
  }
  const sources: string[] = [];
  for (let i = 0; i < 3; i++) {
    const started = Date.now();
    const value = ok(await driver.client.functions.invoke("clinic-summary", { body: {} }));
    sources.push(value.source);
    console.log(`try ${i + 1}: ${value.source} ${Date.now() - started}ms`);
    if (value.source === "ai") console.log(JSON.stringify(value, null, 1));
    assert([value.summary, ...value.questions].every(ruleSafe));
  }
  assertEquals(sources, ["ai", "ai", "ai"]);
});
