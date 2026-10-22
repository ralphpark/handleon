import { assert, assertAlmostEquals, assertEquals } from "jsr:@std/assert@1";
import {
  admin,
  hoursAgo,
  integrationTest as test,
  ok,
  row,
  seedMonth,
  sharedPair,
} from "./_client.ts";
import { dispatchOne, type QueueItem } from "../supabase/functions/_shared/dispatch.ts";
import {
  createDispatchStore,
  pendingNotifications,
} from "../supabase/functions/_shared/dispatch-store.ts";

async function queueFor(familyId: string): Promise<QueueItem[]> {
  return ok(
    await admin.from("notification_queue").select("*").eq("family_id", familyId).order("id"),
  );
}
test("talk stage enqueues with chosen seven day delay", async () => {
  const { driver, car, family } = await sharedPair("trend", 7);
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id, { stage: "talk" })));
  const [q] = await queueFor(family.id);
  const m = ok(
    await admin.from("monthly_summaries").select("created_at").eq("driver_id", driver.id).single(),
  );
  assertAlmostEquals((Date.parse(q.due_at) - Date.parse(m.created_at)) / 86_400_000, 7, 0.000001);
});
test("same stage again does not enqueue twice and stable stage does not notify", async () => {
  const { driver, car, family } = await sharedPair("trend", 0);
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id)));
  assertEquals((await queueFor(family.id)).length, 0);
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id, { stage: "talk" })));
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id, { stage: "talk" })));
  assertEquals((await queueFor(family.id)).length, 1);
});
test("revoked family is skipped at dispatch with zero FCM sends", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id, { stage: "talk" });
  const [q] = await queueFor(family.id);
  ok(
    await family.client.rpc("register_push_token", {
      p_token: "test-device",
      p_platform: "android",
    }),
  );
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  let sends = 0;
  const store = createDispatchStore(admin, () => {
    sends++;
    return Promise.resolve();
  });
  assertEquals(await dispatchOne(q, store), false);
  assertEquals(sends, 0);
  assert((await queueFor(family.id))[0].cancelled_at);
});
test("71 hour old upload is not dispatched, 73 hour old upload is", async () => {
  const { driver, family } = await sharedPair("detail", 3);
  ok(
    await family.client.rpc("register_push_token", {
      p_token: "test-device",
      p_platform: "android",
    }),
  );
  await seedMonth(driver.id, { stage: "talk", createdAt: hoursAgo(71) });
  let sends = 0;
  const store = createDispatchStore(admin, () => {
    sends++;
    return Promise.resolve();
  });
  assertEquals(await dispatchOne((await queueFor(family.id))[0], store), false);
  assertEquals(sends, 0);
  await seedMonth(driver.id, { month: "2026-09-01", stage: "watch", createdAt: hoursAgo(73) });
  assertEquals(await dispatchOne((await queueFor(family.id))[1], store), true);
  assertEquals(sends, 1);
  assert((await queueFor(family.id))[1].sent_at);
});
test("changing delay reschedules pending queues and revoked links create no new queue", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id, { stage: "talk" });
  ok(
    await driver.client.rpc("update_share", {
      p_family_id: family.id,
      p_scope: "stage",
      p_delay_days: 14,
    }),
  );
  const [q] = await queueFor(family.id);
  assert(Date.parse(q.due_at) > Date.now() + 13 * 86_400_000);
  ok(await driver.client.rpc("revoke_share", { p_family_id: family.id }));
  await seedMonth(driver.id, { month: "2026-09-01", stage: "watch" });
  assertEquals((await queueFor(family.id)).length, 1);
});
test("concurrent dispatchers claim once", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id, { stage: "talk" });
  ok(
    await family.client.rpc("register_push_token", {
      p_token: "test-device",
      p_platform: "android",
    }),
  );
  const [q] = await queueFor(family.id);
  let sends = 0;
  const send = () => {
    sends++;
    return Promise.resolve();
  };
  const results = await Promise.all([
    dispatchOne(q, createDispatchStore(admin, send)),
    dispatchOne(q, createDispatchStore(admin, send)),
  ]);
  assertEquals(results.filter(Boolean).length, 1);
  assertEquals(sends, 1);
});

test("twenty tokenless rows do not starve the next deliverable notification", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  for (let index = 1; index <= 20; index++) {
    const month = `${2024 + Math.floor((index - 1) / 12)}-${
      String((index - 1) % 12 + 1).padStart(2, "0")
    }-01`;
    await seedMonth(driver.id, { month, stage: "talk", createdAt: hoursAgo(1) });
  }
  let sends = 0;
  const store = createDispatchStore(admin, () => {
    sends++;
    return Promise.resolve();
  });
  for (const q of await queueFor(family.id)) assertEquals(await dispatchOne(q, store), false);
  const valid = await sharedPair("detail", 0);
  ok(
    await valid.family.client.rpc("register_push_token", {
      p_token: "test-device",
      p_platform: "android",
    }),
  );
  await seedMonth(valid.driver.id, { stage: "talk", createdAt: hoursAgo(1) });
  const batch = await pendingNotifications(admin, new Date());
  assert(batch.some((q) => q.family_id === valid.family.id));
  assert(!batch.some((q) => q.family_id === family.id));
  assertEquals(await dispatchOne(batch.find((q) => q.family_id === valid.family.id)!, store), true);
  assertEquals(sends, 1);
});

test("failed delivery leaves sent_at empty and defers another attempt", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id, { stage: "talk" });
  ok(
    await family.client.rpc("register_push_token", {
      p_token: "test-device",
      p_platform: "android",
    }),
  );
  const [q] = await queueFor(family.id);
  let failed = false;
  try {
    await dispatchOne(
      q,
      createDispatchStore(admin, () => Promise.reject(new Error("test_delivery_failure"))),
    );
  } catch {
    failed = true;
  }
  assert(failed);
  const saved = ok(await admin.from("notification_queue").select("*").eq("id", q.id).single());
  assertEquals(saved.sent_at, null);
  assertEquals(saved.claim_token, null);
  assert(Date.parse(saved.next_attempt_at) > Date.now());
});
