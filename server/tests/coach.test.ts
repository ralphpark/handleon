import { assert, assertEquals } from "jsr:@std/assert@1";
import { type CoachDeps, handler } from "../supabase/functions/conversation-coach/index.ts";
import { buildCoachPrompt, COACH_FALLBACK } from "../supabase/functions/_shared/advice.ts";

const driver = "11111111-1111-4111-8111-111111111111";
const context = {
  feed: {
    stage: "watch",
    month: "2026-10-01",
    domains: { mobility: 0.2 },
    top: [{ feature: "M1" }],
  },
};
export function coachDeps(extra: Partial<CoachDeps> = {}): CoachDeps {
  return {
    authenticate: async () => ({ id: "family", role: "family" }),
    begin: async () => ({ status: "generate", context, day: "2026-10-27", input_hash: "hash" }),
    finish: async () => "ok",
    generate: async () => COACH_FALLBACK,
    judge: async () => true,
    ...extra,
  };
}
function request(body: unknown = { driver_id: driver }, token = true) {
  return new Request("http://local/coach", {
    method: "POST",
    headers: token ? { authorization: "Bearer fake" } : {},
    body: JSON.stringify(body),
  });
}
Deno.test("coach falls back when model fails twice", async () => {
  let n = 0;
  const r = await handler(
    request(),
    coachDeps({
      generate: async () => {
        n++;
        throw new Error("private");
      },
    }),
  );
  assertEquals(r.status, 200);
  assertEquals(await r.json(), { advice: COACH_FALLBACK, source: "fallback" });
  assertEquals(n, 2);
});
Deno.test("coach authenticates and enforces caller, request, sharing and quota boundaries", async () => {
  for (
    const [req, deps, status, error] of [
      [request({}, false), coachDeps(), 401, "unauthorized"],
      [request(), coachDeps({ authenticate: async () => null }), 401, "unauthorized"],
      [
        request(),
        coachDeps({ authenticate: async () => ({ id: driver, role: "driver" }) }),
        403,
        "family_required",
      ],
      [request({ driver_id: driver, lat: 37 }), coachDeps(), 400, "invalid_request"],
      [
        request(),
        coachDeps({ begin: async () => ({ status: "share_not_found" }) }),
        404,
        "share_not_found",
      ],
      [
        request(),
        coachDeps({ begin: async () => ({ status: "daily_limit" }) }),
        429,
        "daily_limit",
      ],
    ] as const
  ) {
    const r = await handler(req, deps);
    assertEquals(r.status, status);
    assertEquals(await r.json(), { error });
  }
});
Deno.test("coach returns cached source without another model call", async () => {
  const result = { advice: COACH_FALLBACK, source: "fallback" as const };
  const r = await handler(
    request(),
    coachDeps({
      begin: async () => ({ status: "cached", result }),
      generate: async () => {
        throw new Error("must not generate");
      },
    }),
  );
  assertEquals(await r.json(), result);
});
Deno.test("coach rejects malformed advice and bounds model latency", async () => {
  for (
    const advice of [{ ...COACH_FALLBACK, openers: ["하나"] }, {
      ...COACH_FALLBACK,
      topic: "가".repeat(81),
    }, { ...COACH_FALLBACK, avoid: ["면허 반납", "두번째"] }]
  ) {
    const r = await handler(request(), coachDeps({ generate: async () => advice }));
    assertEquals(await r.json(), { advice: COACH_FALLBACK, source: "fallback" });
  }
  const r = await handler(
    request(),
    coachDeps({ generate: () => new Promise(() => {}), timeoutMs: 10 }),
  );
  assertEquals(r.status, 200);
  assertEquals((await r.json()).source, "fallback");
});
Deno.test("coach hides generated output if sharing or context changes while generating", async () => {
  for (const outcome of ["share_not_found", "context_changed"] as const) {
    const r = await handler(request(), coachDeps({ finish: async () => outcome }));
    assertEquals(r.status, outcome === "share_not_found" ? 404 : 200);
    const body = await r.json();
    assertEquals(
      body,
      outcome === "share_not_found"
        ? { error: "share_not_found" }
        : { advice: COACH_FALLBACK, source: "fallback" },
    );
  }
});
Deno.test("coach database failures return only fixed fallback", async () => {
  for (
    const extra of [{
      begin: async () => {
        throw new Error("private");
      },
    }, {
      finish: async () => {
        throw new Error("private");
      },
    }]
  ) {
    const r = await handler(request(), coachDeps(extra));
    assertEquals(r.status, 200);
    assertEquals(await r.json(), { advice: COACH_FALLBACK, source: "fallback" });
  }
});
Deno.test("coach prompt removes names, places and free text from context", () => {
  const { system, user } = buildCoachPrompt({
    ...context,
    driver_name: "PRIVATE",
    lat: 37,
    feed: {
      ...context.feed,
      letter: "PRIVATE",
      top: [{ feature: "M1", text: "PRIVATE", grade: "A" }],
    },
    habits: [{ week_start: "2026-10-26", trips: 5, story: "PRIVATE" }],
  });
  assert(!user.includes("PRIVATE"));
  assert(!user.includes("lat"));
  assert(user.includes('"trips":5'));
  assert(system.includes("체면"));
  assert(system.includes("위치"));
});
