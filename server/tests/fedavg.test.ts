import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import { average, type FedStore, runRound } from "../supabase/functions/_shared/fedavg.ts";
import { handler } from "../supabase/functions/fed-average/index.ts";
import fixture from "./fixtures/fedavg.json" with { type: "json" };
import { assertFedWeights, testFedRound } from "./_fed.ts";

const zeros = () => Array<number>(15).fill(0);
const first = "11111111-1111-4111-8111-111111111111";
const second = "22222222-2222-4222-8222-222222222222";
const hash = "a".repeat(64);
function snapshot() {
  return {
    round: 4,
    weights: zeros(),
    input_hash: hash,
    updates: fixture.updates.map((u, i) => ({
      ...structuredClone(u),
      driver_id: i ? second : first,
      client_tag: "self",
    })),
  };
}
function store(value: unknown = snapshot()): FedStore {
  return {
    snapshot: async () => value,
    publish: async (round) => ({ advanced: true, round: round + 1, clients: 2 }),
  };
}
Deno.test("average weights by n", () => {
  // Same participating inputs and expected values as HolidayModelTest's
  // "average weights updates by sample count without mutating them" (E9).
  const input = structuredClone(fixture.updates);
  assertFedWeights(average(input), fixture.expected);
  assertEquals(input, fixture.updates);
});
Deno.test("average includes the last coordinate and preserves signed deltas", () => {
  const a = zeros();
  a[14] = -1;
  const b = zeros();
  b[14] = 1;
  assertEquals(average([{ delta: a, n: 1 }, { delta: b, n: 3 }]), [...zeros().slice(0, 14), 0.5]);
});
Deno.test("average rejects empty batches, wrong size, nonfinite values and unclipped deltas", () => {
  for (
    const delta of [
      [],
      zeros().slice(1),
      [...zeros(), 0],
      [zeros()],
      [null, ...zeros().slice(1)],
      ["0", ...zeros().slice(1)],
      [NaN, ...zeros().slice(1)],
      [Infinity, ...zeros().slice(1)],
      [-Infinity, ...zeros().slice(1)],
      [1.000001, ...zeros().slice(1)],
      [-1.000001, ...zeros().slice(1)],
      Array(15),
    ]
  ) {
    assertThrows(() => average([{ delta, n: 1 }] as never));
  }
  assertThrows(() => average([]));
});
Deno.test("average accepts clipping boundaries and rejects invalid sample counts", () => {
  assertEquals(average([{ delta: [1, -1, ...zeros().slice(2)], n: 520 }]), [
    1,
    -1,
    ...zeros().slice(2),
  ]);
  for (const n of [0, -1, 521, 1.5, NaN, Infinity, null, "2"]) {
    assertThrows(() => average([{ delta: zeros(), n }] as never));
  }
});
Deno.test("round advances only with two or more clients", async () => {
  for (const count of [0, 1]) {
    const value = snapshot();
    value.updates.length = count;
    const s = store(value);
    s.publish = () => {
      throw new Error("must not publish");
    };
    assertEquals(await runRound(s), {
      advanced: false,
      round: 4,
      clients: count,
      reason: "insufficient_clients",
    });
  }
  const s = store();
  s.publish = async (round, inputHash, weights) => {
    assertEquals(round, 4);
    assertEquals(inputHash, hash);
    assertFedWeights(weights, fixture.expected);
    return { advanced: true, round: 5, clients: 2 };
  };
  assertEquals(await runRound(s), { advanced: true, round: 5, clients: 2 });
});
Deno.test("next global weights add the weighted delta to the previous model", async () => {
  const value = snapshot();
  value.weights[0] = 2;
  value.weights[1] = -2;
  const s = store(value);
  s.publish = async (_round, _hash, weights) => {
    assertFedWeights(weights, value.weights.map((w, i) => w + fixture.expected[i]));
    return { advanced: true, round: 5, clients: 2 };
  };
  assertEquals((await runRound(s)).round, 5);
});
Deno.test("virtual tags count as distinct demo clients but duplicate clients are rejected", async () => {
  const value = snapshot();
  value.updates[1].driver_id = first;
  value.updates[1].client_tag = "virtual-1";
  assertEquals((await runRound(store(value))).clients, 2);
  value.updates[1].client_tag = "self";
  await assertRejects(() => runRound(store(value)));
});
Deno.test("invalid snapshots cannot publish", async () => {
  const cases: unknown[] = [null, { ...snapshot(), round: -1 }, {
    ...snapshot(),
    input_hash: "bad",
  }, { ...snapshot(), weights: [NaN, ...zeros().slice(1)] }];
  const bad = snapshot();
  bad.updates[0].client_tag = "private-place";
  cases.push(bad);
  for (const value of cases) {
    const s = store(value);
    s.publish = () => {
      throw new Error("must not publish");
    };
    await assertRejects(() => runRound(s));
  }
});
Deno.test("changed inputs and concurrent publication are returned without retrying another round", async () => {
  for (const reason of ["inputs_changed", "round_changed"] as const) {
    let calls = 0;
    const s = store();
    s.publish = async () => {
      calls++;
      return { advanced: false, round: 4, clients: 2, reason };
    };
    assertEquals(await runRound(s), { advanced: false, round: 4, clients: 2, reason });
    assertEquals(calls, 1);
  }
});
Deno.test("integration publication requires the exact test round and client identities", async () => {
  const value = snapshot();
  const clients = value.updates.map(({ driver_id, client_tag }) => ({ driver_id, client_tag }));
  const source = store(value);
  const isolated = testFedRound(source, 4, clients);
  assertEquals(await isolated.snapshot(), value);
  assertEquals(await runRound(isolated), { advanced: true, round: 5, clients: 2 });
  let publications = 0;
  source.publish = async () => {
    publications++;
    return { advanced: true, round: 5, clients: 2 };
  };
  for (
    const [round, expected] of [
      [5, clients],
      [4, clients.slice(0, 1)],
      [4, [...clients, { driver_id: first, client_tag: "virtual-1" }]],
      [4, [clients[0], { driver_id: second, client_tag: "virtual-1" }]],
    ] as const
  ) {
    await assertRejects(
      () => runRound(testFedRound(source, round, expected)),
      Error,
      "test_requires_isolated_federated_round",
    );
  }
  assertEquals(publications, 0);
});
const token = (role: string) => `Bearer x.${btoa(JSON.stringify({ role })).replace(/=+$/, "")}.sig`;
function request(role = "service_role", body: unknown = {}, method = "POST") {
  return new Request("http://local/fed-average", {
    method,
    headers: { authorization: token(role) },
    ...(method === "POST" ? { body: JSON.stringify(body) } : {}),
  });
}
Deno.test("fed-average permits only service role POST with an empty object", async () => {
  for (
    const [req, status] of [
      [request("authenticated"), 401],
      [request("anon"), 401],
      [new Request("http://local", { method: "POST" }), 401],
      [request("service_role", {}, "GET"), 405],
      [request("service_role", { delta: zeros(), place: "private" }), 400],
      [request("service_role", []), 400],
    ] as const
  ) {
    const s = store();
    s.snapshot = () => {
      throw new Error("must not read");
    };
    assertEquals((await handler(req, s)).status, status);
  }
  assertEquals((await handler(request(), store())).status, 200);
});
Deno.test("fed-average database failures return a retryable response without details", async () => {
  for (
    const s of [
      {
        ...store(),
        snapshot: async () => {
          throw new Error("PRIVATE_KEY_OR_ROW");
        },
      },
      {
        ...store(),
        publish: async () => {
          throw new Error("PRIVATE_KEY_OR_ROW");
        },
      },
    ]
  ) {
    const r = await handler(request(), s);
    assertEquals(r.status, 503);
    assertEquals(await r.json(), { error: "fed_unavailable" });
  }
});
