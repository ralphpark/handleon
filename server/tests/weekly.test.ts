import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, hoursAgo, integrationTest, newUser, ok, sharedPair } from "./_client.ts";
import { canSend, type QueueItem } from "../supabase/functions/_shared/dispatch.ts";
import { createDispatchStore } from "../supabase/functions/_shared/dispatch-store.ts";

function monday() {
  const kst = new Date(Date.now() + 9 * 3_600_000);
  kst.setUTCDate(kst.getUTCDate() - (kst.getUTCDay() + 6) % 7);
  return kst.toISOString().slice(0, 10);
}
function change(driver: string, extra = {}) {
  return {
    driver_id: driver,
    week_start: monday(),
    flagged: true,
    domains: ["MOBILITY"],
    top: [{ feature: "M1", direction: "down" }],
    source: "car",
    ...extra,
  };
}
function habits(driver: string, extra = {}) {
  return {
    driver_id: driver,
    week_start: monday(),
    trips: 5,
    minutes: 180,
    signal_kept: 9,
    signal_total: 10,
    hard_brakes: 1,
    hard_accels: 0,
    calm_trips: 4,
    long_stretches: 0,
    night_trips: 1,
    promises_kept: 3,
    promises_total: 3,
    story: "이번 주 운전 기록을 함께 돌아보세요",
    source: "car",
    ...extra,
  };
}
const choice = (family: string) => ({
  p_family_id: family,
  p_drive_done: null,
  p_weekly_change: true,
  p_habits: true,
});
async function pending(driver: string) {
  return ok(
    await admin.from("notification_queue").select("*").eq("driver_id", driver)
      .eq("kind", "weekly_change").is("sent_at", null).is("cancelled_at", null),
  ) as QueueItem[];
}

integrationTest("weekly change reaches only families who turned it on", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  const other = await newUser("family");
  const invite = ok(
    await driver.client.rpc("create_invite", {
      p_scope: "detail",
      p_delay_days: 0,
      p_share_pattern: false,
    }),
  );
  ok(await other.client.rpc("accept_invite", { p_code: invite }));
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  ok(await car.client.from("weekly_changes").upsert(change(driver.id)));
  ok(await car.client.from("weekly_habits").upsert(habits(driver.id)));
  assertEquals((await pending(driver.id)).map((q) => q.family_id), [family.id]);
  const weekly = ok(await family.client.rpc("get_family_weekly"));
  const summary = ok(await family.client.rpc("get_family_habits"));
  assertEquals(weekly.length, 1);
  assertEquals(summary.length, 1);
  assertEquals(ok(await other.client.rpc("get_family_weekly")), []);
  assertEquals(ok(await other.client.rpc("get_family_habits")), []);
  for (const table of ["weekly_changes", "weekly_habits"]) {
    assertEquals(ok(await family.client.from(table).select("*")), []);
  }
  assert((await family.client.from("weekly_changes").upsert(change(driver.id))).error);
  assert((await other.client.from("weekly_habits").upsert(habits(driver.id))).error);
  const preview = ok(await driver.client.rpc("preview_family_view", { p_family_id: family.id }));
  assertEquals(preview.weekly, weekly);
  assertEquals(preview.habits, summary);
  assertEquals(preview.options.habits, true);
  ok(await car.client.rpc("set_sharing_paused", { p_paused: true }));
  assertEquals(ok(await family.client.rpc("get_family_weekly")), []);
  assertEquals(ok(await family.client.rpc("get_family_habits")), []);
  assertEquals(await pending(driver.id), []);
});

integrationTest("weekly change waits for the delay days", async () => {
  const { driver, car, family } = await sharedPair("stage", 3);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  // Authenticated callers cannot backdate their receipt time.
  ok(
    await car.client.from("weekly_changes").upsert(
      change(driver.id, { created_at: hoursAgo(100) }),
    ),
  );
  ok(
    await driver.client.from("weekly_habits").upsert(
      habits(driver.id, { created_at: hoursAgo(100) }),
    ),
  );
  assertEquals(ok(await family.client.rpc("get_family_weekly")), []);
  assertEquals(ok(await family.client.rpc("get_family_habits")), []);
  const [q] = await pending(driver.id);
  const stamped = ok(
    await admin.from("weekly_changes").select("created_at").eq("driver_id", driver.id).single(),
  );
  assertEquals(Date.parse(q.due_at) - Date.parse(stamped.created_at), 72 * 3_600_000);
  for (const table of ["weekly_changes", "weekly_habits"]) {
    ok(await admin.from(table).update({ created_at: hoursAgo(73) }).eq("driver_id", driver.id));
  }
  assertEquals(ok(await family.client.rpc("get_family_weekly")).length, 1);
  assertEquals(ok(await family.client.rpc("get_family_habits")).length, 1);
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "stage",
      p_delay_days: 14,
      p_share_pattern: false,
    }),
  );
  assertEquals(ok(await family.client.rpc("get_family_weekly")), []);
  assertEquals(ok(await family.client.rpc("get_family_habits")), []);
  const [later] = await pending(driver.id);
  assert(Date.parse(later.due_at) > Date.now());
});

integrationTest("habits rows reject impossible numbers", async () => {
  const { driver, car } = await sharedPair("stage", 0);
  const tuesday = new Date(Date.parse(monday()) + 86_400_000).toISOString().slice(0, 10);
  for (
    const invalid of [
      { signal_kept: 11 },
      { story: "가".repeat(61) },
      { week_start: tuesday },
      { trips: 201 },
      { minutes: 10081 },
      { hard_brakes: -1 },
      { promises_kept: 4 },
      { night_trips: 6 },
    ]
  ) {
    const { error } = await car.client.from("weekly_habits").upsert(habits(driver.id, invalid));
    assertEquals(error?.code, "23514", JSON.stringify(invalid));
  }
  for (
    const invalid of [{ week_start: tuesday }, { domains: [] }, { domains: ["OTHER"] }, {
      top: [{ latitude: 37.123 }],
    }, { top: [{ feature: "M1", direction: "down", text: "서울역" }] }]
  ) {
    assertEquals(
      (await car.client.from("weekly_changes").upsert(change(driver.id, invalid))).error?.code,
      "23514",
    );
  }
  ok(await car.client.from("weekly_habits").upsert(habits(driver.id)));
});

integrationTest("habit story never carries coordinates", async () => {
  const { driver, car } = await sharedPair("stage", 0);
  for (const story of ["37.12345, 127.12345", "위도 37.5 경도 127.2", "서울역에 다녀왔어요"]) {
    assertEquals(
      (await car.client.from("weekly_habits").upsert(habits(driver.id, { story }))).error?.code,
      "23514",
    );
  }
});

integrationTest("unflagging before due cancels the push", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  ok(await car.client.from("weekly_changes").upsert(change(driver.id)));
  const [q] = await pending(driver.id);
  const store = createDispatchStore(admin, () => Promise.resolve());
  assert(await store.claim(q));
  try {
    assert(canSend(await store.state(q), new Date()));
    ok(await car.client.from("weekly_changes").upsert(change(driver.id, { flagged: false })));
    assertEquals(await pending(driver.id), []);
    assertEquals(canSend(await store.state(q), new Date()), false);
  } finally {
    await store.release(q);
  }
});

integrationTest(
  "weekly retries are idempotent and the KST twelve-week window is bounded",
  async () => {
    const { driver, car, family } = await sharedPair("stage", 0);
    ok(await car.client.rpc("set_share_options", choice(family.id)));
    ok(await car.client.from("weekly_changes").upsert(change(driver.id)));
    const [first] = await pending(driver.id);
    ok(await car.client.from("weekly_changes").upsert(change(driver.id)));
    assertEquals((await pending(driver.id)).map((q) => q.id), [first.id]);
    const day = Date.parse(monday());
    const oldest = new Date(day - 11 * 7 * 86_400_000).toISOString().slice(0, 10);
    const outside = new Date(day - 12 * 7 * 86_400_000).toISOString().slice(0, 10);
    const future = new Date(day + 7 * 86_400_000).toISOString().slice(0, 10);
    for (const week_start of [oldest, outside, future]) {
      ok(await admin.from("weekly_habits").upsert(habits(driver.id, { week_start })));
    }
    assertEquals(
      ok(await family.client.rpc("get_family_habits")).map((r: { week_start: string }) =>
        r.week_start
      ),
      [oldest],
    );
  },
);

integrationTest("the weekly window advances exactly at Monday midnight KST", async () => {
  for (
    const [p_at, expected] of [
      ["2026-10-25T14:59:59.999Z", "2026-10-19"],
      ["2026-10-25T15:00:00Z", "2026-10-26"],
      ["2026-12-31T15:00:00Z", "2026-12-28"],
      ["2027-01-03T15:00:00Z", "2027-01-04"],
    ]
  ) {
    assertEquals(ok(await admin.rpc("kst_week_start", { p_at })), expected);
  }
});
