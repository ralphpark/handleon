import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  admin,
  hoursAgo,
  integrationTest as test,
  newCar,
  newUser,
  ok,
  row,
  seedMonth,
  sharedPair,
} from "./_client.ts";

function pattern(driverId: string, overrides: Record<string, unknown> = {}) {
  return {
    driver_id: driverId,
    month: "2026-10-01",
    partial: false,
    trips: 18,
    drive_minutes: 540,
    drive_days: 12,
    night_ratio: 0.1,
    long_ratio: 0.05,
    radius_ratio: 0.7,
    places_ratio: 0.8,
    usual_trips: 22,
    usual_minutes: 660,
    usual_night_ratio: 0.15,
    usual_long_ratio: 0.1,
    source: "car",
    ...overrides,
  };
}

test("driving pattern reaches only families who were given it, and preview matches", async () => {
  const { driver, car, family } = await sharedPair("stage", 0, true);
  const other = await newUser("family");
  const code = ok(
    await driver.client.rpc("create_invite", {
      p_scope: "stage",
      p_delay_days: 0,
      p_share_pattern: false,
    }),
  );
  ok(await other.client.rpc("accept_invite", { p_code: code }));
  ok(await car.client.from("monthly_patterns").upsert(pattern(driver.id)));
  const [seen] = ok(await family.client.rpc("get_family_patterns"));
  assertEquals([seen.trips, seen.radius_ratio, seen.partial], [18, 0.7, false]);
  assertEquals(ok(await other.client.rpc("get_family_patterns")), []);
  const preview = ok(await driver.client.rpc("preview_family_view", { p_family_id: family.id }));
  assertEquals(preview.patterns, [seen]);
  assertEquals(
    ok(await driver.client.rpc("preview_family_view", { p_family_id: other.id })).patterns,
    [],
  );
  assert((await other.client.rpc("preview_family_view", { p_family_id: family.id })).error);
});
test("pattern rows reject location-like values and follow the chosen delay", async () => {
  const { driver, car, family } = await sharedPair("trend", 3, true);
  assert(
    (await car.client.from("monthly_patterns").upsert(pattern(driver.id, { radius_ratio: 50 })))
      .error,
  );
  assert(
    (await car.client.from("monthly_patterns").upsert(pattern(driver.id, { lat: 37.39 }))).error,
  );
  assert((await family.client.from("monthly_patterns").select("*")).error === null);
  assertEquals(ok(await family.client.from("monthly_patterns").select("*")), []);
  ok(await car.client.from("monthly_patterns").upsert(pattern(driver.id)));
  assertEquals(ok(await family.client.rpc("get_family_patterns")), []);
  ok(
    await admin.from("monthly_patterns").update({ created_at: hoursAgo(73) }).eq(
      "driver_id",
      driver.id,
    ),
  );
  assertEquals(ok(await family.client.rpc("get_family_patterns")).length, 1);
});
test("pause from the car hides everything and cancels pending pushes; resume restores", async () => {
  const { driver, car, family } = await sharedPair("detail", 0, true);
  await seedMonth(driver.id, { stage: "talk" });
  ok(await car.client.from("monthly_patterns").upsert(pattern(driver.id)));
  ok(await car.client.rpc("set_sharing_paused", { p_paused: true }));
  assertEquals(ok(await family.client.rpc("get_family_feed")), []);
  assertEquals(ok(await family.client.rpc("get_family_patterns")), []);
  assertEquals(ok(await family.client.rpc("get_family_links"))[0].paused, true);
  const queue = ok(await admin.from("notification_queue").select("*").eq("family_id", family.id));
  assert(queue.every((q: { cancelled_at: string | null }) => q.cancelled_at !== null));
  ok(
    await car.client.from("monthly_summaries").upsert(
      row(driver.id, { month: "2026-11-01", stage: "talk" }),
    ),
  );
  const after = ok(
    await admin.from("notification_queue").select("*").eq("family_id", family.id).is(
      "cancelled_at",
      null,
    ),
  );
  assertEquals(after, []);
  assertEquals(ok(await driver.client.rpc("get_sharing_state")).paused, true);
  assertEquals(
    ok(await driver.client.rpc("preview_family_view", { p_family_id: family.id })).feed,
    [],
  );
  ok(await driver.client.rpc("set_sharing_paused", { p_paused: false }));
  assertEquals(ok(await family.client.rpc("get_family_feed")).length, 2);
  assert((await car.client.rpc("set_sharing_paused", { p_paused: null })).error);
});
test("a car controls only its own driver and revoke-all ends every share", async () => {
  const { driver, car, family } = await sharedPair("trend", 7, false);
  const stranger = await newCar();
  assertEquals(
    (await stranger.client.rpc("set_sharing_paused", { p_paused: true })).error?.message,
    "driver_or_car_required",
  );
  assert((await family.client.rpc("revoke_all_shares")).error);
  const state = ok(await car.client.rpc("get_sharing_state"));
  assertEquals(state.recipients, [{
    family_name: "시험 사용자",
    scope: "trend",
    delay_days: 7,
    share_pattern: false,
  }]);
  assertEquals(ok(await car.client.rpc("revoke_all_shares")), 1);
  assertEquals(ok(await car.client.rpc("get_sharing_state")).recipients, []);
  assertEquals(ok(await family.client.rpc("get_family_links")), []);
  assertEquals(ok(await driver.client.rpc("get_my_shares")), []);
});
test("family notes reach the linked car, are capped while unread, and show as read", async () => {
  const { driver, car, family } = await sharedPair("stage", 0, false);
  const stranger = await newCar();
  assertEquals(
    (await family.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: "   " })).error
      ?.message,
    "invalid_note",
  );
  for (const body of ["오늘도 안전 운전하세요", "주말에 같이 식사해요?", "사랑해요"]) {
    ok(await family.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: body }));
  }
  assertEquals(
    (await family.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: "하나 더" }))
      .error?.message,
    "too_many_unseen",
  );
  assert(
    (await driver.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: "x" })).error,
  );
  assert((await stranger.client.rpc("get_car_notes")).error);
  const notes = ok(await car.client.rpc("get_car_notes"));
  assertEquals(notes.map((n: { body: string }) => n.body), [
    "오늘도 안전 운전하세요",
    "주말에 같이 식사해요?",
    "사랑해요",
  ]);
  assertEquals(notes[0].family_name, "시험 사용자");
  assert((await stranger.client.rpc("mark_note_seen", { p_id: notes[0].id })).error);
  ok(await car.client.rpc("mark_note_seen", { p_id: notes[0].id }));
  assertEquals(ok(await car.client.rpc("get_car_notes")).length, 2);
  const sent = ok(await family.client.rpc("get_sent_notes"));
  assertEquals(sent.filter((n: { seen_at: string | null }) => n.seen_at !== null).length, 1);
  ok(await family.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: "하나 더" }));
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  assertEquals(ok(await car.client.rpc("get_car_notes")), []);
  assertEquals(
    (await family.client.rpc("send_family_note", { p_driver_id: driver.id, p_body: "안녕" })).error
      ?.message,
    "share_not_found",
  );
  assertEquals(
    (await admin.from("family_notes").select("id").eq("driver_id", driver.id)).data?.length,
    4,
  );
  assert((await family.client.from("family_notes").select("*")).error);
});
