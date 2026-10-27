import { assert, assertEquals, assertFalse } from "jsr:@std/assert@1";
import { FORBIDDEN, guarded, haikuJudge, ruleSafe } from "../supabase/functions/_shared/guard.ts";
import { opusText } from "../supabase/functions/_shared/ai-runtime.ts";
import { generateLetter } from "../supabase/functions/_shared/letter.ts";

Deno.test("SDK logging cannot emit generation inputs even when debug logging is enabled", async () => {
  const previous = Deno.env.get("ANTHROPIC_LOG");
  const original = {
    debug: console.debug,
    info: console.info,
    warn: console.warn,
    error: console.error,
  };
  const logs: unknown[][] = [];
  try {
    Deno.env.set("ANTHROPIC_LOG", "debug");
    for (const level of ["debug", "info", "warn", "error"] as const) {
      console[level] = (...args) => {
        logs.push(args);
      };
    }
    await opusText({ system: "PRIVATE_PROMPT", user: "PRIVATE_CONTEXT" }, {
      apiKey: "test-only",
      fetch: async () =>
        Response.json({
          id: "msg_test",
          type: "message",
          role: "assistant",
          model: "claude-opus-5-5",
          content: [{ type: "text", text: "PRIVATE_OUTPUT" }],
          stop_reason: "end_turn",
          stop_details: null,
          usage: { input_tokens: 1, output_tokens: 1 },
        }),
    });
  } finally {
    Object.assign(console, original);
    if (previous === undefined) Deno.env.delete("ANTHROPIC_LOG");
    else Deno.env.set("ANTHROPIC_LOG", previous);
  }
  assertEquals(logs.length, 0);
});

Deno.test("generation deadline abort reaches the SDK fetch before fallback returns", async () => {
  const controller = new AbortController();
  let fetched = false;
  let aborted = false;
  const r = await guarded(
    () =>
      opusText({ system: "test", user: "test" }, {
        apiKey: "test-only",
        signal: controller.signal,
        fetch: (input, init) =>
          new Promise((_resolve, reject) => {
            fetched = true;
            const signal = new Request(input, init).signal;
            signal.addEventListener("abort", () => {
              aborted = true;
              reject(new DOMException("aborted", "AbortError"));
            }, { once: true });
          }),
      }),
    (v) => [v],
    "fallback",
    async () => true,
    { timeoutMs: 100, onTimeout: () => controller.abort() },
  );
  assertEquals(r.source, "fallback");
  assert(fetched);
  assert(aborted);
});

Deno.test("ruleSafe catches spaced and zero-width variants", () => {
  for (
    const t of [
      "면 허 반 납",
      "치\u200b매",
      "인지 장 애",
      "등 급",
      "순\u200d위",
      "알츠하이머",
      "고령 운전자",
      " ",
    ]
  ) {
    assertFalse(ruleSafe(t), t);
  }
  for (const t of FORBIDDEN) assertFalse(ruleSafe([...t.normalize("NFD")].join("\u200b")), t);
  assert(ruleSafe("이번 주에도 함께 이야기 나눠요"));
});
Deno.test("guarded retries once then falls back", async () => {
  let generated = 0;
  let judged = 0;
  const r = await guarded(
    async () => {
      generated++;
      return "면허 반납";
    },
    (v) => [v],
    "함께해요",
    async () => {
      judged++;
      return true;
    },
  );
  assertEquals(r, { value: "함께해요", source: "fallback" });
  assertEquals(generated, 2);
  assertEquals(judged, 0);
});
Deno.test("guarded uses judge after rules pass", async () => {
  const seen: string[] = [];
  let n = 0;
  const r = await guarded(
    async () => ++n === 1 ? "당장 그만하세요" : "함께 이야기해요",
    (v) => [v],
    "쉬어 가요",
    async (t) => {
      seen.push(t);
      return t === "함께 이야기해요";
    },
  );
  assertEquals(r, { value: "함께 이야기해요", source: "ai" });
  assertEquals(seen, ["당장 그만하세요", "함께 이야기해요"]);
});
Deno.test("guarded fails closed on exceptions, empty texts and stalled judges", async () => {
  for (const texts of [() => [], () => [""]]) {
    assertEquals(
      (await guarded(async () => "ok", texts, "fallback", async () => true)).source,
      "fallback",
    );
  }
  assertEquals(
    (await guarded(async () => "안녕하세요", (v) => [v], "fallback", async () => {
      throw new Error("private");
    })).source,
    "fallback",
  );
  assertEquals(
    await guarded(async () => "안녕하세요", (v) => [v], "fallback", () => new Promise(() => {}), {
      timeoutMs: 10,
    }),
    { value: "fallback", source: "fallback" },
  );
});
function judgeReply(text: string, stop_reason = "end_turn") {
  return Response.json({ content: [{ type: "text", text }], stop_reason });
}
Deno.test("haiku judge accepts only a strict boolean JSON verdict", async () => {
  for (
    const [t, expected] of [
      [`{"safe":true}`, true],
      [`{"safe":false}`, false],
      [`{"safe":"true"}`, false],
      [`{"safe":true,"extra":1}`, false],
      ['```json\n{"safe":true}\n```', false],
      ["안전합니다", false],
    ] as const
  ) {
    const judge = haikuJudge("test-only", {
      fetch: async (input, init) => {
        const body = await new Request(input, init).json();
        assertEquals(body.model, "claude-haiku-5-5");
        return judgeReply(t);
      },
    });
    assertEquals(await judge("함께해요"), expected);
  }
  for (
    const response of [
      () => judgeReply(`{"safe":true}`, "refusal"),
      () => judgeReply(`{"safe":true}`, "max_tokens"),
      () => new Response("failure", { status: 429 }),
    ]
  ) {
    assertFalse(await haikuJudge("test-only", { fetch: async () => response() })("함께해요"));
  }
});

Deno.test("haiku judge times out a stalled response body without exposing text", async () => {
  let cancelled = false;
  const judge = haikuJudge("test-only", {
    timeoutMs: 10,
    fetch: (_input, init) => {
      const signal = (init as RequestInit).signal!;
      signal.addEventListener("abort", () => {
        cancelled = true;
      }, { once: true });
      return Promise.resolve(
        new Response(new ReadableStream({ start() {} }), {
          headers: { "content-type": "application/json" },
        }),
      );
    },
  });
  assertFalse(await judge("함께 이야기해요"));
  assert(cancelled);
});
Deno.test("monthly letters also retry semantic rejection through the shared guard", async () => {
  let n = 0;
  const r = await generateLetter({ month: "2026-10-01", mode: "maeum", stage: "stable", top: [] }, {
    apiKey: "test-only",
    generate: async () => {
      n++;
      return "당장 그만하세요";
    },
    judge: async () => false,
  });
  assert(r.fallback);
  assertEquals(n, 2);
  assert(ruleSafe(r.letter));
});
