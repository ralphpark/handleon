// Remote integration tests: Claude runs these in the development project only.
// Publication tests advance the global model; they never reset/delete model history.
import { assert, assertEquals } from "jsr:@std/assert@1";
import { admin, integrationTest, linkedPair, newCar, newUser, ok } from "./_client.ts";
import { createFedStore } from "../supabase/functions/_shared/fedavg-store.ts";
import { runRound } from "../supabase/functions/_shared/fedavg.ts";
import fixture from "./fixtures/fedavg.json" with { type: "json" };
import { assertFedWeights, testFedRound } from "./_fed.ts";

const zero = () => Array<number>(15).fill(0);
const current = async () => ok(await admin.rpc("fed_round_snapshot"));
async function idleRound() {
  const value = await current();
  assertEquals(
    value.updates,
    [],
    "Use an idle development round; do not consume other clients' updates",
  );
  return value;
}
async function participant() {
  const pair = await linkedPair();
  ok(await pair.driver.client.rpc("set_fed_opt_in", { p_on: true }));
  return pair;
}
const submission = (round: number, extra = {}) => ({
  p_round: round,
  p_client_tag: "self",
  p_delta: zero(),
  p_n: 1,
  ...extra,
});

integrationTest("opt-out driver cannot submit", async () => {
  const { driver, car } = await linkedPair();
  const round = (await current()).round;
  assertEquals(
    ok(await driver.client.from("profiles").select("fed_opt_in").single()).fed_opt_in,
    false,
  );
  for (const actor of [driver, car]) {
    assertEquals(
      (await actor.client.rpc("submit_fed_update", submission(round))).error?.message,
      "fed_opt_in_required",
    );
    assertEquals(
      (await actor.client.rpc("set_fed_opt_in", { p_on: null })).error?.message,
      "choice_required",
    );
  }
  assertEquals(
    (await driver.client.from("profiles").update({ fed_opt_in: true }).eq("id", driver.id)).error
      ?.code,
    "42501",
  );
  assertEquals(
    (await driver.client.from("profiles").insert({
      id: driver.id,
      role: "driver",
      fed_opt_in: true,
    })).error?.code,
    "42501",
  );
  ok(await car.client.rpc("set_fed_opt_in", { p_on: true }));
  ok(await driver.client.rpc("submit_fed_update", submission(round)));
});
integrationTest("delta outside unit range is rejected", async () => {
  const { car } = await participant();
  const round = (await current()).round;
  for (
    const delta of [
      [],
      zero().slice(1),
      [...zero(), 0],
      [zero()],
      [null, ...zero().slice(1)],
      ["NaN", ...zero().slice(1)],
      ["Infinity", ...zero().slice(1)],
      ["-Infinity", ...zero().slice(1)],
      [1.01, ...zero().slice(1)],
      [-1.01, ...zero().slice(1)],
    ]
  ) {
    assert(
      (await car.client.rpc("submit_fed_update", submission(round, { p_delta: delta }))).error,
      JSON.stringify(delta),
    );
  }
  ok(
    await car.client.rpc(
      "submit_fed_update",
      submission(round, { p_delta: [1, -1, ...zero().slice(2)], p_n: 520 }),
    ),
  );
});
integrationTest(
  "federated RPCs reject wrong callers, stale rounds, tags and sample counts",
  async () => {
    const { driver, car } = await participant();
    const family = await newUser("family");
    const stranger = await newCar();
    const round = (await current()).round;
    for (const actor of [family, stranger]) {
      for (
        const [name, args] of [["set_fed_opt_in", { p_on: true }], ["get_fed_model", {}], [
          "submit_fed_update",
          submission(round),
        ]] as const
      ) {
        assert((await actor.client.rpc(name, args)).error);
      }
    }
    for (
      const extra of [
        { p_round: round + 1 },
        { p_round: null },
        { p_client_tag: "place-name" },
        { p_client_tag: null },
        { p_n: 0 },
        { p_n: 521 },
        { p_n: null },
      ]
    ) {
      assert((await car.client.rpc("submit_fed_update", submission(round, extra))).error);
    }
    const model = ok(await driver.client.rpc("get_fed_model"));
    assertEquals(Object.keys(model).sort(), ["round", "weights"]);
    assertEquals(model.weights.length, 15);
    assertEquals(ok(await car.client.rpc("get_fed_model")), model);
    ok(await admin.from("car_links").delete().eq("car_id", car.id));
    assert((await car.client.rpc("submit_fed_update", submission(round))).error);
  },
);
integrationTest("federated tables and publication RPCs are service-only", async () => {
  const { driver, car } = await participant();
  const family = await newUser("family");
  for (const actor of [driver, car, family]) {
    for (const table of ["fed_models", "fed_updates"]) {
      assert((await actor.client.from(table).select("*")).error);
    }
    assert((await actor.client.rpc("fed_round_snapshot")).error);
    assert(
      (await actor.client.rpc("publish_fed_round", {
        p_round: 0,
        p_input_hash: "0".repeat(64),
        p_weights: zero(),
      })).error,
    );
  }
  assert((await admin.from("fed_models").insert({ round: -1, weights: zero(), clients: 0 })).error);
  const round = (await current()).round;
  for (
    const delta of [[], [null, ...zero().slice(1)], ["NaN", ...zero().slice(1)], [
      "Infinity",
      ...zero().slice(1),
    ], [1.1, ...zero().slice(1)]]
  ) {
    assertEquals(
      (await admin.from("fed_updates").insert({
        round,
        driver_id: driver.id,
        client_tag: "self",
        delta,
        n: 1,
      })).error?.code,
      "23514",
    );
  }
});
integrationTest(
  "repeated submissions replace one client and opting out removes pending updates",
  async () => {
    const { driver, car } = await participant();
    const round = (await current()).round;
    const args = submission(round);
    ok(await car.client.rpc("submit_fed_update", args));
    const before = ok(await admin.from("fed_updates").select("*").eq("driver_id", driver.id));
    ok(await car.client.rpc("submit_fed_update", args));
    assertEquals(
      ok(await admin.from("fed_updates").select("*").eq("driver_id", driver.id)),
      before,
    );
    ok(await car.client.rpc("submit_fed_update", { ...args, p_n: 3 }));
    assertEquals(
      ok(await admin.from("fed_updates").select("n").eq("driver_id", driver.id).single()).n,
      3,
    );
    ok(await driver.client.rpc("set_fed_opt_in", { p_on: false }));
    assertEquals(ok(await admin.from("fed_updates").select("*").eq("driver_id", driver.id)), []);
    ok(await driver.client.rpc("set_fed_opt_in", { p_on: true }));
    assertEquals(ok(await admin.from("fed_updates").select("*").eq("driver_id", driver.id)), []);
  },
);
integrationTest("federated round advances only with two or more clients", async () => {
  const base = await idleRound();
  const { driver, car } = await participant();
  const store = (round: number, tags: string[]) =>
    testFedRound(
      createFedStore(admin),
      round,
      tags.map((client_tag) => ({
        driver_id: driver.id,
        client_tag,
      })),
    );
  assertEquals((await runRound(store(base.round, []))).advanced, false);
  ok(
    await car.client.rpc(
      "submit_fed_update",
      submission(base.round, { p_delta: fixture.updates[0].delta, p_n: 1 }),
    ),
  );
  assertEquals(await runRound(store(base.round, ["self"])), {
    advanced: false,
    round: base.round,
    clients: 1,
    reason: "insufficient_clients",
  });
  ok(
    await car.client.rpc(
      "submit_fed_update",
      submission(base.round, {
        p_client_tag: "virtual-1",
        p_delta: fixture.updates[1].delta,
        p_n: 3,
      }),
    ),
  );
  assertEquals(await runRound(store(base.round, ["self", "virtual-1"])), {
    advanced: true,
    round: base.round + 1,
    clients: 2,
  });
  const model = ok(await driver.client.rpc("get_fed_model"));
  assertFedWeights(
    model.weights,
    base.weights.map((w: number, i: number) => w + fixture.expected[i]),
  );
  assertEquals((await runRound(store(base.round + 1, []))).advanced, false);
  assertEquals(
    (await car.client.rpc("submit_fed_update", submission(base.round))).error?.message,
    "round_changed",
  );
});
integrationTest("publication rechecks opt-in and changed input before saving", async () => {
  const base = await idleRound();
  const { driver, car } = await participant();
  for (const tag of ["self", "virtual-1"]) {
    ok(await car.client.rpc("submit_fed_update", submission(base.round, { p_client_tag: tag })));
  }
  const store = testFedRound(
    createFedStore(admin),
    base.round,
    ["self", "virtual-1"].map((client_tag) => ({ driver_id: driver.id, client_tag })),
  );
  const old = await store.snapshot();
  ok(await car.client.rpc("submit_fed_update", submission(base.round, { p_n: 2 })));
  let result = ok(
    await admin.rpc("publish_fed_round", {
      p_round: old.round,
      p_input_hash: old.input_hash,
      p_weights: base.weights,
    }),
  );
  assertEquals(result.reason, "inputs_changed");
  const newer = await store.snapshot();
  ok(await driver.client.rpc("set_fed_opt_in", { p_on: false }));
  result = ok(
    await admin.rpc("publish_fed_round", {
      p_round: newer.round,
      p_input_hash: newer.input_hash,
      p_weights: base.weights,
    }),
  );
  assertEquals(result.advanced, false);
  assertEquals((await current()).round, base.round);
});
integrationTest("concurrent publishers advance the same round exactly once", async () => {
  const base = await idleRound();
  const { driver, car } = await participant();
  for (const tag of ["self", "virtual-1"]) {
    ok(await car.client.rpc("submit_fed_update", submission(base.round, { p_client_tag: tag })));
  }
  const value = await testFedRound(
    createFedStore(admin),
    base.round,
    ["self", "virtual-1"].map((client_tag) => ({ driver_id: driver.id, client_tag })),
  ).snapshot();
  const results = await Promise.all(
    Array.from({ length: 2 }, async () =>
      ok(
        await admin.rpc("publish_fed_round", {
          p_round: value.round,
          p_input_hash: value.input_hash,
          p_weights: value.weights,
        }),
      )),
  );
  assertEquals(results.filter((r) => r.advanced).length, 1);
  assertEquals(results.filter((r) => r.reason === "round_changed").length, 1);
  assertEquals((await current()).round, base.round + 1);
});
integrationTest("fed-average endpoint refuses user JWTs", async () => {
  for (const actor of [await newUser("driver"), await newUser("family"), await newCar()]) {
    const { error } = await actor.client.functions.invoke("fed-average", { body: {} });
    assert(error);
    const response = (error as { context?: Response }).context;
    assertEquals(response?.status, 401);
    await response?.text();
  }
});
