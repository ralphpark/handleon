// Claude runs these only after migration/deployment. The live test invokes paid generation.
import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  admin,
  hoursAgo,
  integrationTest,
  newUser,
  ok,
  row,
  seedMonth,
  sharedPair,
} from "./_client.ts";
import { createCoachStore, handler } from "../supabase/functions/conversation-coach/index.ts";
import { COACH_FALLBACK } from "../supabase/functions/_shared/advice.ts";
import { ruleSafe } from "../supabase/functions/_shared/guard.ts";

integrationTest("coach context only holds what the family can see", async () => {
  const { driver, family } = await sharedPair("stage", 0);
  await seedMonth(driver.id);
  const args = { p_family: family.id, p_driver: driver.id };
  let ctx = ok(await admin.rpc("coach_context_for", args));
  assertEquals(ctx.feed.stage, "stable");
  for (const key of ["patterns", "weekly", "habits", "drive_count_7d"]) assert(!(key in ctx));
  assert(!("domains" in ctx.feed));
  assert(!("top" in ctx.feed));
  ok(
    await admin.from("monthly_summaries").upsert(
      row(driver.id, {
        top: [{ feature: "M1", grade: "A", text: "PRIVATE_PLACE" }],
        letter: "PRIVATE_NAME",
      }),
    ),
  );
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "detail",
      p_delay_days: 0,
      p_share_pattern: false,
    }),
  );
  ctx = ok(await admin.rpc("coach_context_for", args));
  assertEquals(ctx.feed.top, [{ feature: "M1" }]);
  assert(!JSON.stringify(ctx).includes("PRIVATE"));
  assert(!JSON.stringify(ctx).includes("driver_name"));
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "detail",
      p_delay_days: 3,
      p_share_pattern: false,
    }),
  );
  assert(!("feed" in ok(await admin.rpc("coach_context_for", args))));
  ok(await driver.client.rpc("set_sharing_paused", { p_paused: true }));
  assertEquals(ok(await admin.rpc("coach_context_for", args)), null);
});

integrationTest("coach context reuses all five family visibility projections", async () => {
  const { driver, family } = await sharedPair("detail", 3, true);
  await seedMonth(driver.id, { createdAt: hoursAgo(100) });
  const week = ok(await admin.rpc("kst_week_start", { p_at: new Date().toISOString() }));
  ok(
    await admin.from("monthly_patterns").insert({
      driver_id: driver.id,
      month: "2026-10-01",
      partial: false,
      trips: 8,
      drive_minutes: 120,
      drive_days: 3,
      source: "car",
      created_at: hoursAgo(100),
    }),
  );
  ok(
    await admin.from("weekly_changes").insert({
      driver_id: driver.id,
      week_start: week,
      flagged: true,
      domains: ["MOBILITY"],
      top: [{ feature: "M1", direction: "down" }],
      source: "car",
      created_at: hoursAgo(100),
    }),
  );
  ok(
    await admin.from("weekly_habits").insert({
      driver_id: driver.id,
      week_start: week,
      trips: 3,
      minutes: 60,
      signal_kept: 2,
      signal_total: 3,
      hard_brakes: 0,
      hard_accels: 0,
      calm_trips: 3,
      long_stretches: 0,
      night_trips: 0,
      promises_kept: 0,
      promises_total: 0,
      story: "",
      source: "car",
      created_at: hoursAgo(100),
    }),
  );
  ok(
    await admin.from("drive_events").insert([
      {
        driver_id: driver.id,
        outing_key: "1234567890",
        ended_at: hoursAgo(1),
        minutes: 20,
        rested: false,
      },
      {
        driver_id: driver.id,
        outing_key: "1234567891",
        ended_at: hoursAgo(8 * 24),
        minutes: 20,
        rested: false,
      },
      {
        driver_id: driver.id,
        outing_key: "1234567892",
        ended_at: hoursAgo(2),
        minutes: 20,
        rested: false,
        retracted_at: hoursAgo(1),
      },
    ]),
  );
  ok(
    await driver.client.rpc("set_share_options", {
      p_family_id: family.id,
      p_drive_done: "each",
      p_weekly_change: true,
      p_habits: true,
    }),
  );
  const args = { p_family: family.id, p_driver: driver.id };
  const ctx = ok(await admin.rpc("coach_context_for", args));
  assertEquals(ctx.feed.stage, "stable");
  assertEquals(ctx.patterns[0].trips, 8);
  assertEquals(ctx.weekly[0].top, [{ feature: "M1", direction: "down" }]);
  assertEquals(ctx.habits[0].trips, 3);
  assertEquals(ctx.drive_count_7d, 1);
  ok(
    await admin.from("weekly_habits").update({ created_at: new Date().toISOString() }).eq(
      "driver_id",
      driver.id,
    ),
  );
  assert(!("habits" in ok(await admin.rpc("coach_context_for", args))));
  ok(
    await driver.client.rpc("set_share_options", {
      p_family_id: family.id,
      p_drive_done: null,
      p_weekly_change: false,
      p_habits: false,
    }),
  );
  const off = ok(await admin.rpc("coach_context_for", args));
  for (const key of ["weekly", "habits", "drive_count_7d"]) assert(!(key in off));
});

function localRequest(id: string) {
  return new Request("http://local/coach", {
    method: "POST",
    headers: { authorization: "Bearer fake" },
    body: JSON.stringify({ driver_id: id }),
  });
}
function localDeps(family: string) {
  return {
    ...createCoachStore(admin),
    authenticate: async () => ({ id: family, role: "family" }),
    generate: async () => COACH_FALLBACK,
    judge: async () => true,
  };
}
integrationTest("sixth coach request in a day is refused politely", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  for (let n = 1; n <= 6; n++) {
    ok(
      await admin.from("monthly_summaries").upsert(
        row(driver.id, { domains: { mobility: n / 10 } }),
      ),
    );
    const r = await handler(localRequest(driver.id), localDeps(family.id));
    assertEquals(r.status, n <= 5 ? 200 : 429);
    const body = await r.json();
    if (n === 6) assertEquals(body, { error: "daily_limit" });
  }
  const saved = ok(
    await admin.from("coach_requests").select("day,count").eq("family_id", family.id).single(),
  );
  assertEquals(saved.count, 5);
  assertEquals(saved.day, new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10));
});
integrationTest("same context reuses the stored advice", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id);
  const deps = localDeps(family.id);
  let n = 0;
  deps.generate = async () => {
    n++;
    return COACH_FALLBACK;
  };
  const first = await (await handler(localRequest(driver.id), deps)).json();
  const second = await (await handler(localRequest(driver.id), deps)).json();
  assertEquals(first, second);
  assertEquals(n, 1);
  assertEquals(
    ok(await admin.from("coach_requests").select("count").eq("family_id", family.id).single())
      .count,
    1,
  );
  ok(await driver.client.rpc("set_sharing_paused", { p_paused: true }));
  assertEquals((await handler(localRequest(driver.id), deps)).status, 404);
});
integrationTest("coach cache and RPCs are service-only", async () => {
  const { driver, family, car } = await sharedPair("stage", 0);
  for (const actor of [driver, family, car]) {
    assert(
      (await actor.client.rpc("coach_context_for", { p_family: family.id, p_driver: driver.id }))
        .error,
    );
    assert(
      (await actor.client.rpc("begin_coach_request", { p_family: family.id, p_driver: driver.id }))
        .error,
    );
    assert(
      (await actor.client.rpc("finish_coach_request", {
        p_family: family.id,
        p_driver: driver.id,
        p_day: "2026-10-27",
        p_input_hash: "0".repeat(64),
        p_result: {},
      })).error,
    );
    assert((await actor.client.from("coach_requests").select("*")).error);
  }
});
integrationTest(
  "concurrent identical coach requests reserve once and stale output is not saved",
  async () => {
    const { driver, family } = await sharedPair("detail", 0);
    await seedMonth(driver.id);
    const args = { p_family: family.id, p_driver: driver.id };
    const results = await Promise.all(
      Array.from({ length: 8 }, async () => ok(await admin.rpc("begin_coach_request", args))),
    );
    assertEquals(results.filter((r) => r.status === "generate").length, 1);
    assertEquals(results.filter((r) => r.status === "pending").length, 7);
    const reservation = results.find((r) => r.status === "generate")!;
    ok(await driver.client.rpc("set_sharing_paused", { p_paused: true }));
    assertEquals(
      ok(
        await admin.rpc("finish_coach_request", {
          ...args,
          p_day: reservation.day,
          p_input_hash: reservation.input_hash,
          p_result: { advice: COACH_FALLBACK, source: "ai" },
        }),
      ),
      "share_not_found",
    );
    assertEquals(
      ok(await admin.from("coach_requests").select("result").eq("family_id", family.id).single())
        .result,
      null,
    );
  },
);
integrationTest(
  "coach quota is shared across drivers and previous KST days do not count",
  async () => {
    const { driver, family } = await sharedPair("detail", 0);
    const other = await newUser("driver");
    const code = ok(
      await other.client.rpc("create_invite", {
        p_scope: "stage",
        p_delay_days: 0,
        p_share_pattern: false,
      }),
    );
    ok(await family.client.rpc("accept_invite", { p_code: code }));
    await seedMonth(other.id);
    const today = new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10);
    ok(
      await admin.from("coach_requests").insert({
        family_id: family.id,
        driver_id: driver.id,
        day: today,
        count: 5,
        input_hash: "0".repeat(64),
      }),
    );
    assertEquals((await handler(localRequest(other.id), localDeps(family.id))).status, 429);
    ok(
      await admin.from("coach_requests").update({
        day: new Date(Date.parse(hoursAgo(24)) + 9 * 3_600_000).toISOString().slice(0, 10),
      }).eq("family_id", family.id),
    );
    assertEquals((await handler(localRequest(other.id), localDeps(family.id))).status, 200);
  },
);
integrationTest("live family coach returns safe advice and then a cached response", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id);
  const first = ok(
    await family.client.functions.invoke("conversation-coach", { body: { driver_id: driver.id } }),
  );
  assertEquals(first.source, "ai", "실제 모델·판정 연결을 확인해야 합니다");
  assertEquals(first.advice.openers.length, 3);
  assertEquals(first.advice.avoid.length, 2);
  for (const t of [...first.advice.openers, ...first.advice.avoid, first.advice.topic]) {
    assert(ruleSafe(t));
  }
  const second = ok(
    await family.client.functions.invoke("conversation-coach", { body: { driver_id: driver.id } }),
  );
  assertEquals(second, first);
});
