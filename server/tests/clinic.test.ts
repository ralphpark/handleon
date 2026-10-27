import { assert, assertEquals } from "jsr:@std/assert@1";
import { type ClinicDeps, handler } from "../supabase/functions/clinic-summary/index.ts";
import {
  buildClinicPrompt,
  CLINIC_FALLBACK,
  clinicWindow,
} from "../supabase/functions/_shared/advice.ts";

function deps(extra: Partial<ClinicDeps> = {}): ClinicDeps {
  return {
    authenticate: async () => ({ id: "driver", role: "driver" }),
    load: async () => ({}),
    generate: async () => CLINIC_FALLBACK,
    judge: async () => true,
    ...extra,
  };
}
function request(body: unknown = {}) {
  return new Request("http://local/clinic", {
    method: "POST",
    headers: { authorization: "Bearer fake" },
    body: JSON.stringify(body),
  });
}
Deno.test("family caller is forbidden", async () => {
  for (const role of ["family", null]) {
    const r = await handler(
      request(),
      deps({
        authenticate: async () => ({ id: "caller", role }),
        load: async () => {
          throw new Error("must not read");
        },
      }),
    );
    assertEquals(r.status, 403);
    assertEquals(await r.json(), { error: "driver_required" });
  }
});
Deno.test("summary falls back on unsafe output", async () => {
  let n = 0;
  const r = await handler(
    request(),
    deps({
      generate: async () => {
        n++;
        return { summary: "위험 운전", questions: ["하나", "둘", "셋"] };
      },
    }),
  );
  assertEquals(r.status, 200);
  assertEquals(await r.json(), { ...CLINIC_FALLBACK, source: "fallback" });
  assertEquals(n, 2);
});
Deno.test("summary length is capped at 600", async () => {
  const r = await handler(
    request(),
    deps({ generate: async () => ({ ...CLINIC_FALLBACK, summary: "가".repeat(700) }) }),
  );
  assertEquals((await r.json()).summary, "가".repeat(600));
});
Deno.test("clinic rejects unsafe questions and malformed output", async () => {
  for (
    const v of [{ ...CLINIC_FALLBACK, questions: ["진단", "둘", "셋"] }, {
      ...CLINIC_FALLBACK,
      questions: ["하나"],
    }, { ...CLINIC_FALLBACK, summary: " " }]
  ) {
    assertEquals(await (await handler(request(), deps({ generate: async () => v }))).json(), {
      ...CLINIC_FALLBACK,
      source: "fallback",
    });
  }
});
Deno.test("clinic model, judge, database and timeout failures never return 500", async () => {
  for (
    const extra of [
      {
        generate: async () => {
          throw new Error("private");
        },
      },
      {
        judge: async () => {
          throw new Error("private");
        },
      },
      {
        load: async () => {
          throw new Error("private");
        },
      },
      { generate: () => new Promise<unknown>(() => {}), timeoutMs: 10 },
    ]
  ) {
    const r = await handler(request(), deps(extra));
    assertEquals(r.status, 200);
    assertEquals(await r.json(), { ...CLINIC_FALLBACK, source: "fallback" });
  }
});
Deno.test("clinic rejects invalid authentication and another driver in body", async () => {
  assertEquals((await handler(request(), deps({ authenticate: async () => null }))).status, 401);
  assertEquals((await handler(request({ driver_id: "someone" }), deps())).status, 400);
});
Deno.test("clinic context omits free text and uses KST calendar windows", () => {
  const { user } = buildClinicPrompt({
    monthly: [{
      month: "2026-10-01",
      stage: "watch",
      top: [{ feature: "W3", text: "PRIVATE" }],
      letter: "PRIVATE",
      driver_name: "PRIVATE",
    }],
    habits: [{ week_start: "2026-10-26", trips: 2, story: "PRIVATE" }],
    weekly: [{
      week_start: "2026-10-26",
      flagged: true,
      domains: ["MOBILITY"],
      top: [{ feature: "M1", direction: "down", place: "PRIVATE" }],
    }],
    lat: 37,
  });
  assert(!user.includes("PRIVATE"));
  assert(!user.includes("lat"));
  assertEquals(clinicWindow(new Date("2026-10-25T15:00:00Z")), {
    monthFrom: "2026-05-01",
    monthTo: "2026-10-01",
    habitsFrom: "2026-09-07",
    weeklyFrom: "2026-08-10",
    weekTo: "2026-10-26",
  });
});
