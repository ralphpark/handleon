import { assert, assertEquals, assertMatch } from "jsr:@std/assert@1";
import {
  admin,
  hoursAgo,
  integrationTest as test,
  linkedPair,
  newCar,
  newUser,
  ok,
  seedMonth,
  sharedPair,
} from "./_client.ts";

test("pairing code works once", async () => {
  const car = await newCar();
  const d = await newUser("driver");
  const code = ok(await car.client.rpc("create_pairing_code"));
  assertMatch(code, /^[A-Z2-9]{6}$/);
  ok(await d.client.rpc("claim_pairing", { p_code: code }));
  assertEquals(
    (await d.client.rpc("claim_pairing", { p_code: code })).error?.message,
    "invalid_or_expired_code",
  );
});
test("expired code fails", async () => {
  const car = await newCar();
  const d = await newUser("driver");
  const code = ok(await car.client.rpc("create_pairing_code"));
  ok(await admin.from("pairing_codes").update({ expires_at: hoursAgo(1) }).eq("code", code));
  assertEquals(
    (await d.client.rpc("claim_pairing", { p_code: code })).error?.message,
    "invalid_or_expired_code",
  );
});
test("only anonymous cars generate pairing codes and only drivers claim them", async () => {
  const car = await newCar();
  const d = await newUser("driver");
  const f = await newUser("family");
  assert((await d.client.rpc("create_pairing_code")).error);
  const code = ok(await car.client.rpc("create_pairing_code"));
  assert((await f.client.rpc("claim_pairing", { p_code: code })).error);
  assert((await car.client.rpc("claim_pairing", { p_code: code })).error);
  ok(await d.client.rpc("claim_pairing", { p_code: code }));
});
test("concurrent claims have exactly one winner and new code invalidates old code", async () => {
  const car = await newCar();
  const d = await newUser("driver");
  const other = await newUser("driver");
  const old = ok(await car.client.rpc("create_pairing_code"));
  const code = ok(await car.client.rpc("create_pairing_code"));
  assert((await d.client.rpc("claim_pairing", { p_code: old })).error);
  const outcomes = await Promise.all(
    [d, other].map((a) => a.client.rpc("claim_pairing", { p_code: code })),
  );
  assertEquals(outcomes.filter((r) => !r.error).length, 1);
});
test("invite requires both scope and timing with no defaults", async () => {
  const d = await newUser("driver");
  for (const [scope, delay] of [["stage", null], [null, 0], ["bad", 3], ["trend", 1]]) {
    assertEquals(
      (await d.client.rpc("create_invite", { p_scope: scope, p_delay_days: delay })).error?.message,
      "scope_and_timing_required",
    );
  }
  const code = ok(await d.client.rpc("create_invite", { p_scope: "detail", p_delay_days: 14 }));
  assertMatch(code, /^[A-Z2-9]{8}$/);
});
test("invite accepts only family, expires, and is single use", async () => {
  const d = await newUser("driver");
  const f = await newUser("family");
  const create = () => d.client.rpc("create_invite", { p_scope: "trend", p_delay_days: 7 });
  const code = ok(await create());
  assert((await d.client.rpc("accept_invite", { p_code: code })).error);
  ok(await f.client.rpc("accept_invite", { p_code: code }));
  assertEquals(
    (await f.client.rpc("accept_invite", { p_code: code })).error?.message,
    "invalid_or_expired_code",
  );
  const expired = ok(await create());
  ok(await admin.from("invites").update({ expires_at: hoursAgo(1) }).eq("code", expired));
  assertEquals(
    (await f.client.rpc("accept_invite", { p_code: expired })).error?.message,
    "invalid_or_expired_code",
  );
});
test("delay hides summary before 72 hours and exposes it afterwards", async () => {
  const { driver, family } = await sharedPair("detail", 3);
  await seedMonth(driver.id, { createdAt: hoursAgo(71) });
  assertEquals(ok(await family.client.rpc("get_family_feed")), []);
  await seedMonth(driver.id, { month: "2026-09-01", createdAt: hoursAgo(73) });
  const feed = ok(await family.client.rpc("get_family_feed"));
  assertEquals(feed.length, 1);
  assertEquals(feed[0].month, "2026-09-01");
});
for (const scope of ["stage", "trend", "detail"] as const) {
  test(`${scope} scope exposes only allowed fields`, async () => {
    const { driver, family } = await sharedPair(scope, 0);
    await seedMonth(driver.id);
    const [r] = ok(await family.client.rpc("get_family_feed"));
    assertEquals(r.driver_id, driver.id);
    assertEquals(r.stage, "stable");
    if (scope === "stage") assertEquals([r.domains, r.top, r.letter], [null, null, null]);
    if (scope === "trend") {
      assert(r.domains);
      assertEquals([r.top, r.letter], [null, null]);
    }
    if (scope === "detail") {
      assert(r.domains);
      assert(r.top);
      assert(r.letter);
    }
    assertEquals(
      Object.keys(r).sort(),
      ["driver_id", "driver_name", "month", "stage", "domains", "top", "letter", "scope"].sort(),
    );
  });
}
test("share changes take effect immediately and only owner can change or revoke", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  const other = await newUser("driver");
  await seedMonth(driver.id);
  assert(
    (await other.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "stage",
      p_delay_days: 3,
    })).error,
  );
  assert((await other.client.rpc("revoke_share", { p_family_id: family.id })).error);
  assert(
    (await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: null,
      p_delay_days: 0,
    })).error,
  );
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "stage",
      p_delay_days: 3,
    }),
  );
  assertEquals(ok(await family.client.rpc("get_family_feed")), []);
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "stage",
      p_delay_days: 0,
    }),
  );
  assertEquals(ok(await family.client.rpc("get_family_feed"))[0].letter, null);
});
test("revoked family sees nothing and push registration validates platform", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id);
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  assertEquals(ok(await family.client.rpc("get_family_feed")), []);
  assert(
    (await family.client.rpc("register_push_token", { p_token: "t", p_platform: "web" })).error,
  );
  ok(
    await family.client.rpc("register_push_token", {
      p_token: "test-token",
      p_platform: "android",
    }),
  );
  ok(await family.client.rpc("register_push_token", { p_token: "test-token", p_platform: "ios" }));
  assertEquals(ok(await family.client.from("push_tokens").select())?.length, 1);
});

test("letter save is service-only, rejects re-paired cars and stale summary versions", async () => {
  const { driver, car } = await linkedPair();
  const summary = await seedMonth(driver.id);
  const params = {
    p_car_id: car.id,
    p_driver_id: driver.id,
    p_month: "2026-10-01",
    p_created_at: summary.created_at,
    p_letter: "함께 돌아보아요. 핸들온 드림",
  };
  assert((await car.client.rpc("save_monthly_letter", params)).error);
  assert((await driver.client.rpc("save_monthly_letter", params)).error);
  ok(await admin.rpc("save_monthly_letter", params));
  assertEquals(
    ok(await driver.client.from("monthly_summaries").select("letter").single()).letter,
    params.p_letter,
  );
  assertEquals(
    (await admin.rpc("save_monthly_letter", { ...params, p_created_at: hoursAgo(24) })).error
      ?.message,
    "summary_changed",
  );
  const newDriver = await newUser("driver");
  const code = ok(await car.client.rpc("create_pairing_code"));
  ok(await newDriver.client.rpc("claim_pairing", { p_code: code }));
  assertEquals(
    (await admin.rpc("save_monthly_letter", params)).error?.message,
    "linked_car_required",
  );
});
test("driver sees own active shares with family names and feed carries scope", async () => {
  const { driver, family } = await sharedPair("trend", 0);
  const other = await newUser("driver");
  const [share] = ok(await driver.client.rpc("get_my_shares"));
  assertEquals([share.family_id, share.family_name, share.scope, share.delay_days], [family.id, "시험 사용자", "trend", 0]);
  assertEquals(ok(await other.client.rpc("get_my_shares")), []);
  assert((await family.client.rpc("get_my_shares")).error);
  await seedMonth(driver.id);
  assertEquals(ok(await family.client.rpc("get_family_feed"))[0].scope, "trend");
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  assertEquals(ok(await driver.client.rpc("get_my_shares")), []);
});
test("family previews an invite before accepting, never after or expired", async () => {
  const driver = await newUser("driver");
  const family = await newUser("family");
  const code = ok(await driver.client.rpc("create_invite", { p_scope: "detail", p_delay_days: 7 }));
  assert((await driver.client.rpc("preview_invite", { p_code: code })).error);
  assertEquals(ok(await family.client.rpc("preview_invite", { p_code: code })),
    [{ driver_name: "시험 사용자", scope: "detail", delay_days: 7 }]);
  ok(await family.client.rpc("accept_invite", { p_code: code }));
  assertEquals((await family.client.rpc("preview_invite", { p_code: code })).error?.message, "invalid_or_expired_code");
  const late = ok(await driver.client.rpc("create_invite", { p_scope: "stage", p_delay_days: 0 }));
  ok(await admin.from("invites").update({ expires_at: hoursAgo(1) }).eq("code", late));
  assertEquals((await family.client.rpc("preview_invite", { p_code: late })).error?.message, "invalid_or_expired_code");
});
