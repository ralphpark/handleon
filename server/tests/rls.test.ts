import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  admin,
  integrationTest as test,
  linkedPair,
  newCar,
  newUser,
  ok,
  row,
  seedMonth,
  sharedPair,
} from "./_client.ts";

test("linked car can insert and upsert its driver's month", async () => {
  const { car, driver } = await linkedPair();
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id)));
  ok(await car.client.from("monthly_summaries").upsert(row(driver.id, { stage: "watch" })));
  const rows = ok(await driver.client.from("monthly_summaries").select());
  assertEquals(rows?.length, 1);
  assertEquals(rows![0].stage, "watch");
});
test("unlinked car and another driver's car cannot write anyone else's month", async () => {
  const car = await newCar();
  const d = await newUser("driver");
  assert((await car.client.from("monthly_summaries").insert(row(d.id))).error);
  const other = await linkedPair();
  assert((await other.car.client.from("monthly_summaries").insert(row(d.id))).error);
  await seedMonth(d.id);
  ok(
    await other.car.client.from("monthly_summaries").update({ stage: "talk" }).eq(
      "driver_id",
      d.id,
    ),
  );
  assertEquals(
    ok(await admin.from("monthly_summaries").select("stage").eq("driver_id", d.id).single()).stage,
    "stable",
  );
});
test("family cannot read summaries or daily conditions directly", async () => {
  const { driver, family } = await sharedPair("detail", 0);
  await seedMonth(driver.id);
  ok(
    await admin.from("daily_conditions").insert({
      driver_id: driver.id,
      day: "2026-10-22",
      ewma: 0.1,
      threshold: 0.3,
      alert: false,
    }),
  );
  assertEquals(ok(await family.client.from("monthly_summaries").select()), []);
  assertEquals(ok(await family.client.from("daily_conditions").select()), []);
});
test("driver reads own summaries but cannot upload or read another driver", async () => {
  const d = await newUser("driver");
  const other = await newUser("driver");
  await seedMonth(d.id);
  await seedMonth(other.id);
  assertEquals(ok(await d.client.from("monthly_summaries").select())?.map((r) => r.driver_id), [
    d.id,
  ]);
  assert(
    (await d.client.from("monthly_summaries").insert(row(d.id, { month: "2026-09-01" }))).error,
  );
});
test("daily writes require the linked car and reject reassignment", async () => {
  const { car, driver } = await linkedPair();
  const other = await newUser("driver");
  const daily = {
    driver_id: driver.id,
    day: "2026-10-22",
    ewma: 0.1,
    threshold: 0.3,
    alert: false,
  };
  ok(await car.client.from("daily_conditions").upsert(daily));
  ok(await car.client.from("daily_conditions").upsert({ ...daily, alert: true }));
  assert(
    (await car.client.from("daily_conditions").update({ driver_id: other.id }).eq(
      "driver_id",
      driver.id,
    )).error,
  );
  assertEquals(ok(await driver.client.from("daily_conditions").select())?.length, 1);
});
test("private tables cannot be directly written and car_links is visible only to participants", async () => {
  const { car, driver } = await linkedPair();
  const other = await newUser("family");
  assertEquals(ok(await car.client.from("car_links").select())?.length, 1);
  assertEquals(ok(await driver.client.from("car_links").select())?.length, 1);
  assertEquals(ok(await other.client.from("car_links").select()), []);
  const denied: Record<string, Record<string, unknown>> = {
    car_links: { car_id: car.id, driver_id: driver.id },
    pairing_codes: { code: "TEST22", car_id: car.id, expires_at: new Date().toISOString() },
    invites: {
      code: "TEST2222",
      driver_id: driver.id,
      scope: "detail",
      delay_days: 0,
      expires_at: new Date().toISOString(),
    },
    family_links: { driver_id: driver.id, family_id: other.id, scope: "detail", delay_days: 0 },
    notification_queue: {
      driver_id: driver.id,
      family_id: other.id,
      month: "2026-10-01",
      due_at: new Date().toISOString(),
    },
  };
  for (const [table, value] of Object.entries(denied)) {
    assert((await driver.client.from(table).insert(value)).error, table);
  }
});
test("profiles and push tokens are self-only and anonymous cars cannot become people", async () => {
  const d = await newUser("driver");
  const f = await newUser("family");
  const car = await newCar();
  assert((await car.client.from("profiles").insert({ id: car.id, role: "driver" })).error);
  assertEquals(ok(await d.client.from("profiles").select())?.map((r) => r.id), [d.id]);
  assert(
    (await d.client.from("push_tokens").insert({
      user_id: f.id,
      token: "test",
      platform: "android",
    })).error,
  );
  ok(await d.client.rpc("register_push_token", { p_token: "test-only", p_platform: "android" }));
  assertEquals(ok(await f.client.from("push_tokens").select()), []);
});
test("client timestamp cannot bypass upload delay and payload rejects location keys", async () => {
  const { car, driver } = await linkedPair();
  ok(
    await car.client.from("monthly_summaries").insert(
      row(driver.id, { created_at: "2000-01-01T00:00:00Z" }),
    ),
  );
  const saved = ok(await driver.client.from("monthly_summaries").select().single());
  assert(Date.parse(saved.created_at) > Date.now() - 60_000);
  assert(
    (await car.client.from("monthly_summaries").upsert(row(driver.id, { domains: { lat: 37 } })))
      .error,
  );
  assert(
    (await car.client.from("monthly_summaries").upsert(
      row(driver.id, { top: [{ feature: "W3", text: "기록", grade: "C", lon: 127 }] }),
    )).error,
  );
});

test("users cannot change their own role", async () => {
  const d = await newUser("driver");
  const { error } = await d.client.from("profiles").update({ role: "family" }).eq("id", d.id);
  assert(error);
  ok(await d.client.from("profiles").update({ display_name: "새 이름" }).eq("id", d.id));
});
