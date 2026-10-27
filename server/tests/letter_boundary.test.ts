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

const REQ: LetterRequest = {
  month: "2026-10-01",
  mode: "condition",
  stage: "watch",
  top: [{ feature: "M1", direction: "down", grade: "A" }],
};
const FALLBACK = { letter: fallbackLetter(REQ), fallback: true };

function reply(content: unknown[], stop_reason = "end_turn") {
  return Response.json({
    id: "msg_x",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content,
    stop_reason,
    stop_details: null,
    usage: { input_tokens: 1, output_tokens: 1 },
  });
}
const text = (t: string) => ({ type: "text", text: t });
function apiError(status: number) {
  return Response.json({ type: "error", error: { type: "api_error", message: "test" } }, {
    status,
    headers: { "retry-after-ms": "1" },
  });
}
function rejects(body: unknown) {
  assertThrows(() => parseLetterRequest(body), Error, "invalid_request");
}

Deno.test("request validator accepts January and December months and rejects malformed months", () => {
  for (const month of ["2026-01-01", "2026-12-01"]) {
    assertEquals(parseLetterRequest({ ...REQ, month }).month, month);
  }
  for (
    const month of [
      "2026-00-01",
      "2026-13-01",
      "2026-1-01",
      "2026-10-1",
      "26-10-01",
      "2026/10/01",
      "2026-10-01T00:00:00Z",
      " 2026-10-01",
      "2026-10-01 ",
      "2026-10-01\n",
      "２０２６-10-01",
      "",
      20261001,
      null,
    ]
  ) rejects({ ...REQ, month });
});

Deno.test("request validator accepts both modes with all four stages and rejects case or whitespace variants", () => {
  for (const mode of ["maeum", "condition"] as const) {
    for (const stage of ["building", "stable", "watch", "talk"] as const) {
      assertEquals(parseLetterRequest({ ...REQ, mode, stage }), { ...REQ, mode, stage });
    }
  }
  for (const mode of ["Maeum", "maeum ", "", null, 1]) rejects({ ...REQ, mode });
  for (const stage of ["TALK", " talk", "Watch", "", null, 0]) rejects({ ...REQ, stage });
});

Deno.test("request validator accepts zero to three top items and rejects a fourth or a non-array top", () => {
  const item = REQ.top[0];
  for (const top of [[], [item], [item, item, item]]) {
    assertEquals(parseLetterRequest({ ...REQ, top }).top, top);
  }
  for (const top of [[item, item, item, item], {}, { 0: item, length: 1 }, "M1", null]) {
    rejects({ ...REQ, top });
  }
});

Deno.test("request validator enforces feature id shape and direction and grade enums inside top", () => {
  const item = REQ.top[0];
  for (const feature of ["M1", "W9", "O10", "M99"]) {
    assertEquals(
      parseLetterRequest({ ...REQ, top: [{ ...item, feature }] }).top[0].feature,
      feature,
    );
  }
  for (const feature of ["M0", "M100", "m1", "X1", "M01", "M 1", "M1 ", "", 1]) {
    rejects({ ...REQ, top: [{ ...item, feature }] });
  }
  for (const direction of ["UP", "flat", "", null]) {
    rejects({ ...REQ, top: [{ ...item, direction }] });
  }
  for (const grade of ["D", "a", "", null]) rejects({ ...REQ, top: [{ ...item, grade }] });
  for (const key of ["feature", "direction", "grade"] as const) {
    const { [key]: _omitted, ...missing } = item;
    rejects({ ...REQ, top: [missing] });
  }
  for (const bad of [null, [], "M1"]) rejects({ ...REQ, top: [bad] });
});

Deno.test("request validator rejects non-object bodies, each missing key and an own __proto__ key", () => {
  for (const body of [null, undefined, [], "body", 1, true, {}]) rejects(body);
  for (const key of ["month", "mode", "stage", "top"] as const) {
    const { [key]: _omitted, ...missing } = REQ;
    rejects(missing);
  }
  rejects(
    JSON.parse(`{"month":"2026-10-01","mode":"maeum","stage":"talk","top":[],"__proto__":{}}`),
  );
  rejects(
    JSON.parse(
      `{"month":"2026-10-01","mode":"maeum","stage":"talk","top":[{"feature":"M1","direction":"up","grade":"A","__proto__":{}}]}`,
    ),
  );
});

Deno.test({
  name: "request validator rejects non-string enum values that stringify to allowed words",
  fn() {
    const item = REQ.top[0];
    rejects({ ...REQ, mode: ["maeum"] });
    rejects({ ...REQ, stage: ["talk"] });
    rejects({ ...REQ, top: [{ ...item, direction: ["up"] }] });
    rejects({ ...REQ, top: [{ ...item, direction: [["up"]] }] });
    rejects({ ...REQ, top: [{ ...item, grade: ["A"] }] });
  },
});

Deno.test("empty top builds a prompt with no changes and the same fallback letter", () => {
  const empty = { ...REQ, top: [] };
  assertEquals(JSON.parse(buildPrompt(empty).user), {
    month: "2026-10-01",
    mode: "condition",
    stage: "watch",
    changes: [],
  });
  assertEquals(buildPrompt(empty).system, buildPrompt(REQ).system);
  assertEquals(fallbackLetter(empty), fallbackLetter(REQ));
});

Deno.test("prompt maps direction to 늘어남/줄어듦 and grade to evidence for each item in order, with only projected keys", () => {
  const { user } = buildPrompt({
    ...REQ,
    top: [
      { feature: "M2", direction: "up", grade: "C" },
      { feature: "W4", direction: "down", grade: "B" },
      { feature: "O8", direction: "up", grade: "A" },
    ],
  });
  const parsed = JSON.parse(user);
  assertEquals(Object.keys(parsed), ["month", "mode", "stage", "changes"]);
  assertEquals(parsed.changes, [
    { activity: "운전하시는 날", change: "늘어남", evidence: "C" },
    { activity: "익숙한 곳에 갈 때 길안내 켜기", change: "줄어듦", evidence: "B" },
    { activity: "회전할 때 방향지시등 켜기", change: "늘어남", evidence: "A" },
  ]);
});

Deno.test("every well-formed feature id maps to a forbidden-free activity and never echoes the id", () => {
  for (const prefix of ["M", "W", "O"]) {
    for (let n = 1; n <= 99; n++) {
      const feature = `${prefix}${n}`;
      const { user } = buildPrompt({ ...REQ, top: [{ feature, direction: "up", grade: "B" }] });
      const activity: string = JSON.parse(user).changes[0].activity;
      assert(isSafe(activity), feature);
      assertFalse(user.includes(feature), feature);
    }
  }
  for (const unknown of ["M8", "W1", "O3", "O10", "M99"]) {
    const { user } = buildPrompt({
      ...REQ,
      top: [{ feature: unknown, direction: "up", grade: "B" }],
    });
    assertEquals(JSON.parse(user).changes[0].activity, "운전 습관");
  }
});

Deno.test("forbidden words split by any whitespace or format character are still caught", () => {
  const separators = [
    " ",
    "\n",
    "\t",
    "\u00a0",
    "\u3000",
    "\u2028",
    "\u200b",
    "\u200c",
    "\u200d",
    "\u2060",
    "\ufeff",
    "\u00ad",
    "\u200e",
    "\u202e",
  ];
  for (const word of FORBIDDEN) {
    for (const sep of separators) {
      const split = [...word].join(sep);
      assertFalse(
        isSafe(`이번 달 ${split} 이야기`),
        `${word} / U+${sep.codePointAt(0)!.toString(16)}`,
      );
    }
  }
});

Deno.test("forbidden words embedded in longer words or written as decomposed Hangul are caught", () => {
  for (
    const t of [
      "치매안심",
      "인지장애인",
      "진단서",
      "위험하다",
      "점수표",
      "질환자",
      "판정승",
      "우울감",
    ]
  ) assertFalse(isSafe(t), t);
  for (const word of FORBIDDEN) {
    const decomposed = word.normalize("NFD");
    assert(decomposed !== word);
    assertFalse(isSafe(`편지 ${decomposed} 끝`), word);
  }
});

Deno.test("empty or invisible-only text is unsafe while safe text wrapped in invisible characters passes", () => {
  for (const t of ["", "\u200b\u200d\ufeff", "\n\t\u3000\u00a0", "\u00ad\u2060"]) {
    assertFalse(isSafe(t), JSON.stringify(t));
  }
  assert(isSafe("\u200b요즘 편안하세요\ufeff"));
});

// Design choice (fail closed): whitespace is removed before matching, so a forbidden word formed
// across a word boundary is also rejected and the fixed fallback letter is used.
Deno.test("whitespace removal joins adjacent words so a forbidden word across a word boundary is rejected", () => {
  assertFalse(isSafe("지금 시점 수고하셨어요"));
  assert(isSafe("지금 이 시점에 수고하셨어요"));
});

Deno.test("text blocks are joined before the safety check: a split forbidden word falls back and split safe text is kept", async () => {
  const split = await generateLetter(REQ, {
    apiKey: "test-only",
    judge: async () => true,
    fetch: () => Promise.resolve(reply([text("이번 달 치"), text("매 이야기")])),
  });
  assertEquals(split, FALLBACK);
  const safe = await generateLetter(REQ, {
    apiKey: "test-only",
    judge: async () => true,
    fetch: () => Promise.resolve(reply([text("  이번 달도 "), text("함께해요.\n핸들온 드림\n")])),
  });
  assertEquals(safe, { letter: "이번 달도 함께해요.\n핸들온 드림", fallback: false });
});

Deno.test("response with no text blocks, only a thinking block or whitespace-only text falls back", async () => {
  for (
    const content of [
      [],
      [{ type: "thinking", thinking: "x", signature: "test" }],
      [text(" \n\t\u3000")],
    ]
  ) {
    const result = await generateLetter(REQ, {
      apiKey: "test-only",
      judge: async () => true,
      fetch: () => Promise.resolve(reply(content)),
    });
    assertEquals(result, FALLBACK, JSON.stringify(content));
  }
});

Deno.test("client errors fall back after one guard retry", async () => {
  for (const status of [400, 401, 403, 404]) {
    let calls = 0;
    const result = await generateLetter(REQ, {
      apiKey: "test-only",
      judge: async () => true,
      fetch: () => {
        calls++;
        return Promise.resolve(apiError(status));
      },
    });
    assertEquals(result, FALLBACK);
    assertEquals(calls, 2, String(status));
  }
});

Deno.test("timeout, conflict, rate limit and overload statuses are each retried exactly once", async () => {
  for (const status of [408, 409, 429, 529]) {
    let calls = 0;
    const result = await generateLetter(REQ, {
      apiKey: "test-only",
      judge: async () => true,
      fetch: () => {
        calls++;
        return Promise.resolve(apiError(status));
      },
    });
    assertEquals(result, FALLBACK);
    assertEquals(calls, 2, String(status));
  }
});

Deno.test("transient server error followed by a safe reply returns the model letter", async () => {
  let calls = 0;
  const result = await generateLetter(REQ, {
    apiKey: "test-only",
    judge: async () => true,
    fetch: () =>
      Promise.resolve(
        ++calls === 1 ? apiError(500) : reply([text("편안한 한 달이었어요.\n핸들온 드림")]),
      ),
  });
  assertEquals(result, { letter: "편안한 한 달이었어요.\n핸들온 드림", fallback: false });
  assertEquals(calls, 2);
});

Deno.test("connection failure on the retry attempt falls back without a third call", async () => {
  let calls = 0;
  const result = await generateLetter(REQ, {
    apiKey: "test-only",
    judge: async () => true,
    fetch: () =>
      ++calls === 1
        ? Promise.resolve(apiError(500))
        : Promise.reject(new TypeError("network down")),
  });
  assertEquals(result, FALLBACK);
  assertEquals(calls, 2);
});

Deno.test("malformed success bodies fall back without exposing their content", async () => {
  const bodies = [
    () => new Response("치매 not json", { headers: { "content-type": "application/json" } }),
    () =>
      Response.json({ id: "msg_x", type: "message", role: "assistant", stop_reason: "end_turn" }),
  ];
  for (const body of bodies) {
    let calls = 0;
    const result = await generateLetter(REQ, {
      apiKey: "test-only",
      judge: async () => true,
      fetch: () => {
        calls++;
        return Promise.resolve(body());
      },
    });
    assertEquals(result, FALLBACK);
    assertEquals(calls, 2);
  }
});
