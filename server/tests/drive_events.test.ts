import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, integrationTest, newCar, ok, row, sharedPair } from "./_client.ts";

const key = "1793000000000";
const ended = () => new Date(Date.now() - 40 * 60_000).toISOString();
const args = (time = ended(), outing = key) => ({
  p_outing_key: outing,
  p_ended_at: time,
  p_minutes: 42,
  p_rested: true,
});
const choice = (family: string, mode: string | null = "each") => ({
  p_family_id: family,
  p_drive_done: mode,
  p_weekly_change: false,
  p_habits: false,
});
async function pending(driver: string) {
  return ok(
    await admin.from("notification_queue").select("*").eq("driver_id", driver)
      .is("cancelled_at", null).is("sent_at", null),
  );
}

integrationTest("options start off and need an explicit choice", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  const [recipient] = ok(await car.client.rpc("get_sharing_state")).recipients;
  assertEquals([recipient.drive_done, recipient.weekly_change, recipient.habits], [
    null,
    false,
    false,
  ]);
  for (const field of ["p_weekly_change", "p_habits"]) {
    assertEquals(
      (await driver.client.rpc("set_share_options", { ...choice(family.id), [field]: null }))
        .error?.message,
      "choice_required",
    );
  }
  ok(await car.client.rpc("set_share_options", choice(family.id, null)));
  assert((await driver.client.rpc("set_share_options", choice(family.id, "automatic"))).error);
  assert((await family.client.rpc("set_share_options", choice(family.id))).error);
  assert((await family.client.from("share_options").select("*")).error);
  assert((await car.client.from("drive_events").insert({ driver_id: driver.id })).error);
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  assertEquals(
    (await car.client.rpc("set_share_options", choice(family.id))).error?.message,
    "share_not_found",
  );
});

integrationTest("each mode queues one push 30 minutes after the outing", async () => {
  const { driver, car, family } = await sharedPair("stage", 14);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  const time = ended();
  ok(await car.client.rpc("report_drive_end", args(time)));
  ok(await car.client.rpc("report_drive_end", args(time)));
  const rows = await pending(driver.id);
  assertEquals(rows.length, 1);
  assertEquals([rows[0].kind, rows[0].ref], ["drive_done", key]);
  assertEquals(Date.parse(rows[0].due_at), Date.parse(time) + 30 * 60_000);
  const events = ok(await family.client.rpc("get_family_drive_events"));
  assertEquals(events.length, 1); // drive news bypasses the 14-day delay
  assertEquals([events[0].minutes, events[0].rested], [42, true]);
  assertEquals(
    ok(await driver.client.rpc("preview_family_view", { p_family_id: family.id })).drive_events,
    events,
  );
});

integrationTest("a later trip of the same outing moves the push", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await car.client.rpc("set_share_options", choice(family.id)));
  const time = ended();
  const later = new Date(Date.parse(time) + 20 * 60_000).toISOString();
  ok(await car.client.rpc("report_drive_end", args(time)));
  ok(await car.client.rpc("report_drive_end", args(later)));
  const rows = await pending(driver.id);
  assertEquals(rows.length, 1);
  assertEquals(Date.parse(rows[0].due_at), Date.parse(later) + 30 * 60_000);
  // A delayed retry cannot move the end time or pending delivery backwards.
  ok(await car.client.rpc("report_drive_end", args(time)));
  assertEquals((await pending(driver.id))[0].id, rows[0].id);
});

integrationTest("drive after 20h joins next evening", async () => {
  const { driver, car, family } = await sharedPair("stage", 14);
  ok(await driver.client.rpc("set_share_options", choice(family.id, "evening")));
  const day = new Date(Date.now() + 9 * 3_600_000 - 86_400_000).toISOString().slice(0, 10);
  const time = `${day}T20:01:00+09:00`;
  ok(await car.client.rpc("report_drive_end", args(time)));
  const [q] = await pending(driver.id);
  const nextDay = new Date(Date.parse(`${day}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10);
  assertEquals([q.kind, q.ref], ["drive_evening", nextDay]);
  assertEquals(Date.parse(q.due_at), Date.parse(`${nextDay}T20:00:00+09:00`));
  // A second outing in the same evening bucket does not add another push.
  ok(await car.client.rpc("report_drive_end", args(`${day}T21:00:00+09:00`, "1793000000001")));
  assertEquals((await pending(driver.id)).length, 1);
});

integrationTest("pause cancels drive pushes and hides events", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  ok(await car.client.rpc("report_drive_end", args()));
  ok(await car.client.rpc("set_sharing_paused", { p_paused: true }));
  assertEquals(await pending(driver.id), []);
  assertEquals(ok(await family.client.rpc("get_family_drive_events")), []);
  ok(await car.client.rpc("report_drive_end", args(ended(), "1793000000001")));
  assertEquals(await pending(driver.id), []);
  ok(await car.client.rpc("set_sharing_paused", { p_paused: false }));
  assertEquals(await pending(driver.id), []);
});

integrationTest("retract cancels the pending push and hides the card", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  const input = args();
  ok(await car.client.rpc("report_drive_end", input));
  ok(await car.client.rpc("retract_drive", { p_outing_key: key }));
  ok(await car.client.rpc("report_drive_end", input)); // no resurrection on a retry
  assertEquals(await pending(driver.id), []);
  assertEquals(ok(await family.client.rpc("get_family_drive_events")), []);
});

integrationTest("a stranger car cannot report drives", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  const stranger = await newCar();
  assertEquals(
    (await stranger.client.rpc("report_drive_end", args())).error?.message,
    "driver_or_car_required",
  );
  assert((await family.client.rpc("report_drive_end", args())).error);
  assert((await stranger.client.rpc("retract_drive", { p_outing_key: key })).error);
  assertEquals(
    (await car.client.rpc(
      "report_drive_end",
      args(new Date(Date.now() + 6 * 60_000).toISOString()),
    ))
      .error?.message,
    "invalid_time",
  );
  for (
    const patch of [{ p_minutes: 0 }, { p_minutes: 1441 }, { p_rested: null }, {
      p_outing_key: "37.123,127.123",
    }]
  ) {
    assert((await car.client.rpc("report_drive_end", { ...args(), ...patch })).error);
  }
  assertEquals(ok(await admin.from("drive_events").select("*").eq("driver_id", driver.id)), []);
});

integrationTest("family without drive_done sees no events", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await car.client.rpc("report_drive_end", args()));
  assertEquals(ok(await family.client.rpc("get_family_drive_events")), []);
  assertEquals(await pending(driver.id), []);
  assert((await family.client.from("drive_events").select("*")).error);
  assert((await car.client.rpc("get_family_drive_events")).error);
});

integrationTest("late older uploads do not replace a newer notification", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  ok(await car.client.rpc("report_drive_end", args()));
  const [before] = await pending(driver.id);
  ok(
    await car.client.rpc(
      "report_drive_end",
      args(new Date(Date.now() - 2 * 3_600_000).toISOString(), "1792999999999"),
    ),
  );
  assertEquals((await pending(driver.id)).map((q) => q.id), [before.id]);
});

integrationTest("monthly uploads and delay changes leave drive timing alone", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  const time = ended();
  ok(await car.client.rpc("report_drive_end", args(time)));
  const [before] = await pending(driver.id);
  ok(
    await car.client.from("monthly_summaries").upsert(
      row(driver.id, { month: before.month, stage: "stable" }),
    ),
  );
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "detail",
      p_delay_days: 14,
      p_share_pattern: false,
    }),
  );
  assertEquals((await pending(driver.id))[0].due_at, before.due_at);
  // Monthly cascade deletion only deletes stage notifications.
  ok(await admin.from("monthly_summaries").delete().eq("driver_id", driver.id));
  assertEquals((await pending(driver.id))[0].id, before.id);
});

integrationTest("reinviting starts with new options off and cancels old notices", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await driver.client.rpc("set_share_options", choice(family.id)));
  ok(await car.client.rpc("report_drive_end", args()));
  ok(await car.client.rpc("revoke_all_shares"));
  const code = ok(
    await driver.client.rpc("create_invite", {
      p_scope: "stage",
      p_delay_days: 0,
      p_share_pattern: false,
    }),
  );
  ok(await family.client.rpc("accept_invite", { p_code: code }));
  assertEquals(ok(await driver.client.rpc("get_sharing_state")).recipients[0].drive_done, null);
  assertEquals(ok(await family.client.rpc("get_family_drive_events")), []);
  assertEquals(await pending(driver.id), []);
});

integrationTest("KST midnight and exact 20h choose the correct evening date", async () => {
  for (
    const [p_end, expected] of [
      ["2026-10-31T23:59:59+09:00", "2026-11-01"],
      ["2026-11-01T00:00:00+09:00", "2026-11-01"],
      ["2026-11-01T19:59:59.999+09:00", "2026-11-01"],
      ["2026-11-01T20:00:00+09:00", "2026-11-02"],
    ]
  ) {
    assertEquals(ok(await admin.rpc("drive_evening_date", { p_end })), expected);
  }
});

integrationTest("late expired evening uploads cannot queue behind today's digest", async () => {
  const { driver, car, family } = await sharedPair("stage", 0);
  ok(await car.client.rpc("set_share_options", choice(family.id, "evening")));
  ok(
    await car.client.rpc(
      "report_drive_end",
      args(new Date(Date.now() - 3 * 86_400_000).toISOString()),
    ),
  );
  assertEquals(await pending(driver.id), []);
  // Still show the legitimate historical record in the 14-day feed.
  assertEquals(ok(await family.client.rpc("get_family_drive_events")).length, 1);
});
