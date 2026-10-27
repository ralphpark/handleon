import { assert, assertEquals, assertFalse, assertRejects } from "jsr:@std/assert@1";
import { accessToken, message, sendMessage } from "../supabase/functions/_shared/fcm.ts";
import {
  canSend,
  type DeliveryState,
  dispatchOne,
  type DispatchStore,
  type QueueItem,
} from "../supabase/functions/_shared/dispatch.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { pendingNotifications } from "../supabase/functions/_shared/dispatch-store.ts";

const now = new Date("2026-10-22T12:00:00Z");
const queue: QueueItem = {
  id: 1,
  driver_id: "driver",
  family_id: "family",
  month: "2026-10-01",
  due_at: now.toISOString(),
  sent_at: null,
  cancelled_at: null,
};
function state(): DeliveryState {
  return {
    queue: { ...queue },
    link: { revoked_at: null, delay_days: 3 },
    summary: { created_at: "2026-10-19T12:00:00Z", stage: "talk" },
  };
}
function fakeStore(initial = state()) {
  let current = initial;
  let claimed = false;
  let sends = 0;
  let completed = false;
  let released = false;
  const store: DispatchStore = {
    claim: () => {
      if (claimed) return Promise.resolve(false);
      claimed = true;
      return Promise.resolve(true);
    },
    state: () => Promise.resolve(structuredClone(current)),
    tokens: () => Promise.resolve(["token-a", "token-b"]),
    send: () => {
      sends++;
      return Promise.resolve();
    },
    complete: () => {
      completed = true;
      return Promise.resolve();
    },
    release: () => {
      released = true;
      return Promise.resolve();
    },
    forget: () => Promise.resolve(),
  };
  return {
    store,
    change: (s: DeliveryState) => {
      current = s;
    },
    sends: () => sends,
    completed: () => completed,
    released: () => released,
  };
}

Deno.test("OAuth assertion is an RS256 signed JWT with correct audience, scope and times", async () => {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    btoa(String.fromCharCode(...der))
  }\n-----END PRIVATE KEY-----`;
  const serviceAccount = JSON.stringify({
    client_email: "test@example.invalid",
    project_id: "test-project",
    private_key: pem,
  });
  let calls = 0;
  const result = await accessToken(serviceAccount, now, async (input, init) => {
    calls++;
    const req = new Request(input, init);
    assertEquals(req.url, "https://oauth2.googleapis.com/token");
    assertEquals(req.method, "POST");
    const body = new URLSearchParams(await req.text());
    assertEquals(body.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
    const parts = body.get("assertion")!.split(".");
    assertEquals(parts.length, 3);
    const decode = (value: string) =>
      Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    assertEquals(JSON.parse(new TextDecoder().decode(decode(parts[0]))), {
      alg: "RS256",
      typ: "JWT",
    });
    const payload = JSON.parse(new TextDecoder().decode(decode(parts[1])));
    assertEquals(payload.iss, "test@example.invalid");
    assertEquals(payload.aud, "https://oauth2.googleapis.com/token");
    assertEquals(payload.scope, "https://www.googleapis.com/auth/firebase.messaging");
    assertEquals(payload.iat, 1792670400);
    assertEquals(payload.exp, 1792674000);
    assert(
      await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        pair.publicKey,
        decode(parts[2]),
        new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
      ),
    );
    return Response.json({ access_token: "test-access", expires_in: 3600, token_type: "Bearer" });
  });
  assertEquals(result, "test-access");
  assertEquals(calls, 1);
});
Deno.test("invalid service account fails before network access", async () => {
  let calls = 0;
  await assertRejects(() =>
    accessToken("{}", now, () => {
      calls++;
      throw new Error("unexpected");
    })
  );
  assertEquals(calls, 0);
});
Deno.test("message never names the stage or includes details", () => {
  const body = message("t");
  assertFalse(/talk|watch|대화|관찰/.test(JSON.stringify(body)));
  assertEquals(body, {
    message: { token: "t", notification: { title: "핸들온", body: "새 운전 요약이 도착했어요" } },
  });
});
Deno.test("FCM sends HTTP v1 payload and refuses failed delivery", async () => {
  await sendMessage("test-project", "test-access", "device", async (input, init) => {
    const request = new Request(input, init);
    assertEquals(request.url, "https://fcm.googleapis.com/v1/projects/test-project/messages:send");
    assertEquals(request.headers.get("authorization"), "Bearer test-access");
    assertEquals(await request.json(), message("device"));
    return Response.json({ name: "projects/test-project/messages/test" });
  });
  await assertRejects(() =>
    sendMessage(
      "test-project",
      "test-access",
      "device",
      () => Promise.resolve(new Response("error", { status: 500 })),
    )
  );
});
Deno.test("72 hour boundary is exact, including 1ms before and at due time", () => {
  assertFalse(canSend(state(), new Date("2026-10-22T11:59:59.999Z")));
  assert(canSend(state(), now));
  assert(canSend(state(), new Date("2026-10-22T12:00:00.001Z")));
});
Deno.test("pending selection rejects missing links, revocation, cancellation, sent and stale stage", () => {
  const cases: DeliveryState[] = [
    { ...state(), link: null },
    { ...state(), summary: null },
    { ...state(), link: { revoked_at: now.toISOString(), delay_days: 3 } },
    { ...state(), queue: { ...queue, cancelled_at: now.toISOString() } },
    { ...state(), queue: { ...queue, sent_at: now.toISOString() } },
    { ...state(), summary: { created_at: "2026-10-19T12:00:00Z", stage: "stable" } },
  ];
  for (const s of cases) assertFalse(canSend(s, now));
});
Deno.test("increased sharing delay and newer upload defer an already due queue", () => {
  assertFalse(canSend({ ...state(), link: { revoked_at: null, delay_days: 7 } }, now));
  assertFalse(
    canSend({ ...state(), summary: { created_at: "2026-10-21T12:00:00Z", stage: "talk" } }, now),
  );
});
Deno.test("revoked family is skipped at dispatch with zero sends", async () => {
  const f = fakeStore({ ...state(), link: { revoked_at: now.toISOString(), delay_days: 0 } });
  assertFalse(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.sends(), 0);
  assertFalse(f.completed());
  assert(f.released());
});
Deno.test("revocation after token lookup is rechecked before the first send", async () => {
  const f = fakeStore();
  f.store.tokens = () => {
    f.change({ ...state(), link: null });
    return Promise.resolve(["device"]);
  };
  await dispatchOne(queue, f.store, () => now);
  assertEquals(f.sends(), 0);
  assertFalse(f.completed());
});
Deno.test("revocation between tokens stops later devices", async () => {
  const f = fakeStore();
  let sent = 0;
  f.store.send = () => {
    sent++;
    f.change({ ...state(), link: null });
    return Promise.resolve();
  };
  await dispatchOne(queue, f.store, () => now);
  assertEquals(sent, 1);
  assertFalse(f.completed());
});
Deno.test("active family receives every token then marks sent and competing dispatcher skips", async () => {
  const f = fakeStore();
  const results = await Promise.all([
    dispatchOne(queue, f.store, () => now),
    dispatchOne(queue, f.store, () => now),
  ]);
  assertEquals(results.filter(Boolean).length, 1);
  assertEquals(f.sends(), 2);
  assert(f.completed());
});
Deno.test("failed delivery and absent tokens remain unsent and release claim", async () => {
  const f = fakeStore();
  f.store.send = () => Promise.reject(new Error("delivery_failed"));
  await assertRejects(() => dispatchOne(queue, f.store, () => now));
  assertFalse(f.completed());
  assert(f.released());
  const empty = fakeStore();
  empty.store.tokens = () => Promise.resolve([]);
  assertFalse(await dispatchOne(queue, empty.store, () => now));
  assertFalse(empty.completed());
  assert(empty.released());
});

Deno.test("pending query excludes deferred attempts and prioritizes untried rows", async () => {
  let called = false;
  const db = createClient("https://example.invalid", "test-only", {
    auth: { persistSession: false, autoRefreshToken: false },
    global: {
      fetch: (input) => {
        called = true;
        const url = new URL(String(input));
        assertEquals(url.searchParams.get("next_attempt_at"), "lte.2026-10-22T12:00:00.000Z");
        assertEquals(url.searchParams.get("order"), "next_attempt_at.asc,due_at.asc");
        assertEquals(url.searchParams.get("limit"), "20");
        assertEquals(url.searchParams.get("sent_at"), "is.null");
        assertEquals(url.searchParams.get("cancelled_at"), "is.null");
        return Promise.resolve(Response.json([queue]));
      },
    },
  });
  assertEquals(await pendingNotifications(db, now), [queue]);
  assert(called);
});
