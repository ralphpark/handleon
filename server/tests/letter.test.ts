import { assert, assertEquals, assertFalse, assertThrows } from "jsr:@std/assert@1";
import {
  buildPrompt,
  fallbackLetter,
  FORBIDDEN,
  generateLetter,
  isSafe,
  type LetterRequest,
  parseLetterRequest,
} from "../supabase/functions/_shared/letter.ts";

const TALK_REQ: LetterRequest = {
  month: "2026-10-01",
  mode: "maeum",
  stage: "talk",
  top: [{ feature: "W3", direction: "up", grade: "C" }],
};
function reply(text: string, stop_reason = "end_turn") {
  return Response.json({
    id: "msg_x",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content: [{ type: "text", text }],
    stop_reason,
    stop_details: null,
    usage: { input_tokens: 1, output_tokens: 1 },
  });
}

Deno.test("forbidden words are caught", () => {
  assertEquals(FORBIDDEN, ["치매", "인지장애", "진단", "위험", "점수", "질환", "판정", "우울"]);
  for (const word of FORBIDDEN) assertFalse(isSafe(`이번 달 ${word} 안내`));
  assertFalse(isSafe("치매가 의심됩니다"));
  assertFalse(isSafe("진\u200b단"));
  assertFalse(isSafe(" "));
  assert(isSafe("요즘 가시는 곳이 조금 줄었어요"));
});
Deno.test("fallback letter is safe and signed in every stage", () => {
  for (const stage of ["building", "stable", "watch", "talk"] as const) {
    const t = fallbackLetter({ ...TALK_REQ, stage });
    assert(isSafe(t));
    assert(t.endsWith("핸들온 드림"));
    assert(t.split(/[.!?]/).length <= 6);
    if (stage === "talk") {
      assert(t.includes("가족"));
      assert(t.includes("무료 선별검사"));
    }
  }
});
Deno.test("prompt carries no location keys even with extra runtime fields", () => {
  const { system, user } = buildPrompt(
    { ...TALK_REQ, lat: 37, lon: 127, cell: "x", 주소: "x" } as LetterRequest,
  );
  assertFalse(/lat|lon|cell|주소/.test(user));
  assert(system.includes("5문장"));
  assert(system.includes("핸들온 드림"));
  assertFalse(user.includes("W3"));
});
Deno.test("request validator rejects location injection and invalid contracts", () => {
  for (
    const body of [
      { ...TALK_REQ, lat: 37 },
      { ...TALK_REQ, month: "2026-10-02" },
      { ...TALK_REQ, stage: "unknown" },
      { ...TALK_REQ, top: [{ feature: "주소", direction: "up", grade: "C" }] },
      { ...TALK_REQ, top: [{ ...TALK_REQ.top[0], lat: 37 }] },
    ]
  ) assertThrows(() => parseLetterRequest(body));
  assertEquals(parseLetterRequest(TALK_REQ), TALK_REQ);
});
Deno.test("model returns forbidden text: falls back through official SDK", async () => {
  const result = await generateLetter(TALK_REQ, {
    apiKey: "test-only",
    fetch: () => Promise.resolve(reply("진단 결과입니다")),
  });
  assertEquals(result, { letter: fallbackLetter(TALK_REQ), fallback: true });
});
Deno.test("refusal falls back even with safe text", async () => {
  const result = await generateLetter(TALK_REQ, {
    apiKey: "test-only",
    fetch: () => Promise.resolve(reply("편안한 하루 보내세요", "refusal")),
  });
  assert(result.fallback);
});
Deno.test("500 response falls back after bounded retry", async () => {
  let calls = 0;
  const result = await generateLetter(TALK_REQ, {
    apiKey: "test-only",
    fetch: () => {
      calls++;
      return Promise.resolve(
        Response.json({ type: "error", error: { type: "api_error", message: "test" } }, {
          status: 500,
          headers: { "retry-after-ms": "1" },
        }),
      );
    },
  });
  assert(result.fallback);
  assertEquals(calls, 2);
});
Deno.test("safe model output uses requested beta parameters and only text blocks", async () => {
  let request: Record<string, unknown> = {};
  const result = await generateLetter(TALK_REQ, {
    apiKey: "test-only",
    fetch: async (input, init) => {
      request = await new Request(input, init).json();
      const response = await reply("이번 달도 함께 살펴보아요.\n핸들온 드림").json();
      response.content.unshift({ type: "thinking", thinking: "진단", signature: "test" });
      return Response.json(response);
    },
  });
  assertFalse(result.fallback);
  assertEquals(result.letter, "이번 달도 함께 살펴보아요.\n핸들온 드림");
  assertEquals(request.model, "claude-opus-5-5");
  assertEquals(request.max_tokens, 16000);
  assertEquals(request.output_config, { effort: "low" });
  assertEquals(request.fallbacks, "default");
  assertFalse("thinking" in request);
});
Deno.test("empty response falls back", async () => {
  assert(
    (await generateLetter(TALK_REQ, {
      apiKey: "test-only",
      fetch: () => Promise.resolve(reply("")),
    })).fallback,
  );
});
Deno.test("20 second total deadline aborts a stalled model call", async () => {
  let aborted = false;
  const started = performance.now();
  const result = await generateLetter(TALK_REQ, {
    apiKey: "test-only",
    fetch: (input, init) =>
      new Promise((_resolve, reject) => {
        new Request(input, init).signal.addEventListener("abort", () => {
          aborted = true;
          reject(new DOMException("aborted", "AbortError"));
        }, { once: true });
      }),
  });
  assert(result.fallback);
  assert(aborted);
  assert(performance.now() - started < 22_000);
});
