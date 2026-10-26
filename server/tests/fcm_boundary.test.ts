import { assert, assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  accessToken,
  message,
  sendMessage,
  serviceAccount,
} from "../supabase/functions/_shared/fcm.ts";

const now = new Date("2026-10-22T12:00:00Z");
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
const KEY_BASE64 = btoa(
  String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey))),
);
const PEM = `-----BEGIN PRIVATE KEY-----\n${KEY_BASE64}\n-----END PRIVATE KEY-----\n`;

function account(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    client_email: "test@example.invalid",
    project_id: "test-project",
    private_key: PEM,
    ...overrides,
  });
}
const decode = (value: string) =>
  Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
function cancellable(status: number) {
  const state = { cancelled: false };
  const response = new Response(
    new ReadableStream({
      cancel() {
        state.cancelled = true;
      },
    }),
    { status },
  );
  return { response, state };
}
function countingFetch(respond: () => Response) {
  let calls = 0;
  const fetchImpl: typeof fetch = () => {
    calls++;
    return Promise.resolve(respond());
  };
  return { fetch: fetchImpl, calls: () => calls };
}

Deno.test("service account accepts real-shaped JSON with extra fields and rejects missing, empty or non-string required fields", () => {
  const parsed = serviceAccount(JSON.stringify({
    type: "service_account",
    project_id: "handleon-dev",
    private_key_id: "test",
    private_key: "test-key",
    client_email: "test@example.invalid",
    client_id: "1",
    token_uri: "https://oauth2.googleapis.com/token",
  }));
  assertEquals(
    [parsed.client_email, parsed.project_id, parsed.private_key],
    ["test@example.invalid", "handleon-dev", "test-key"],
  );
  for (
    const json of [
      "null",
      "[]",
      '"text"',
      "1",
      "true",
      "{}",
      account({ client_email: undefined }),
      account({ client_email: "" }),
      account({ client_email: 1 }),
      account({ private_key: undefined }),
      account({ private_key: 1 }),
      account({ project_id: undefined }),
      account({ project_id: 1 }),
    ]
  ) assertThrows(() => serviceAccount(json), Error, "invalid_service_account", json);
});

Deno.test("malformed service account JSON throws a parse error, not a partial account", () => {
  for (const json of ["", "{", "{'client_email':'x'}"]) {
    assertThrows(() => serviceAccount(json), SyntaxError);
  }
});

// Design choice: the regex is looser than Google's project id rules (e.g. a trailing hyphen
// passes); sendMessage still URL-encodes the id, so a lenient id cannot change the request path.
Deno.test("service account project id must be lowercase, start alphanumeric and have at least two characters", () => {
  for (const project_id of ["ab", "a1", "1a", "handleon-dev-123", "ab-"]) {
    assertEquals(serviceAccount(account({ project_id })).project_id, project_id);
  }
  for (
    const project_id of ["", "a", "-ab", "Ab", "a_b", "a.b", "a/b", "../x", "a b", "ab\n", "핸들온"]
  ) {
    assertThrows(
      () => serviceAccount(account({ project_id })),
      Error,
      "invalid_service_account",
      JSON.stringify(project_id),
    );
  }
});

Deno.test("empty, non-base64, double-escaped or non-key private keys fail before any network request", async () => {
  for (
    const private_key of [
      "",
      "not base64!!",
      PEM.replace(/\n/g, "\\n"),
      "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----",
    ]
  ) {
    const f = countingFetch(() => Response.json({ access_token: "unexpected" }));
    await assertRejects(() => accessToken(account({ private_key }), now, f.fetch));
    assertEquals(f.calls(), 0, JSON.stringify(private_key.slice(0, 40)));
  }
});

Deno.test("invalid clock rejects with invalid_time before key import or network", async () => {
  const f = countingFetch(() => Response.json({ access_token: "unexpected" }));
  await assertRejects(
    () => accessToken(account({ private_key: "never imported" }), new Date(NaN), f.fetch),
    Error,
    "invalid_time",
  );
  assertEquals(f.calls(), 0);
});

Deno.test("64-column CRLF PEM signs a verifiable assertion and a sub-second clock floors iat", async () => {
  const wrapped = `-----BEGIN PRIVATE KEY-----\r\n${
    KEY_BASE64.match(/.{1,64}/g)!.join("\r\n")
  }\r\n-----END PRIVATE KEY-----\r\n`;
  let assertion = "";
  const token = await accessToken(
    account({ private_key: wrapped }),
    new Date("2026-10-22T12:00:00.999Z"),
    async (input, init) => {
      assertion = new URLSearchParams(await new Request(input, init).text()).get("assertion")!;
      return Response.json({ access_token: "test-access" });
    },
  );
  assertEquals(token, "test-access");
  const [header, payload, signature] = assertion.split(".");
  const claims = JSON.parse(new TextDecoder().decode(decode(payload)));
  assertEquals([claims.iat, claims.exp], [1792670400, 1792674000]);
  assert(
    await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      pair.publicKey,
      decode(signature),
      new TextEncoder().encode(`${header}.${payload}`),
    ),
  );
});

Deno.test("OAuth HTTP failure rejects with oauth_failed after one attempt and cancels the unread body", async () => {
  for (const status of [400, 401, 403, 429, 500, 503]) {
    const { response, state } = cancellable(status);
    const f = countingFetch(() => response);
    await assertRejects(() => accessToken(account(), now, f.fetch), Error, "oauth_failed");
    assertEquals(f.calls(), 1, String(status));
    assert(state.cancelled, String(status));
  }
});

Deno.test("OAuth success without a usable access_token rejects with oauth_invalid_response", async () => {
  for (
    const body of [{}, { access_token: "" }, { access_token: 1 }, { access_token: null }, {
      token: "x",
    }]
  ) {
    await assertRejects(
      () => accessToken(account(), now, () => Promise.resolve(Response.json(body))),
      Error,
      "oauth_invalid_response",
      JSON.stringify(body),
    );
  }
});

// A JSON `null` or non-JSON 200 body still rejects (fail closed), but with a TypeError or
// SyntaxError instead of the named oauth_invalid_response / fcm_invalid_response errors.
Deno.test("OAuth and FCM success responses with null or non-JSON bodies reject", async () => {
  for (const body of ["null", "not json", ""]) {
    await assertRejects(() =>
      accessToken(account(), now, () => Promise.resolve(new Response(body, { status: 200 })))
    );
    await assertRejects(() =>
      sendMessage(
        "test-project",
        "test-access",
        "device",
        () => Promise.resolve(new Response(body, { status: 200 })),
      )
    );
  }
});

Deno.test("OAuth and FCM requests carry an unaborted timeout signal and a fetch timeout rejects the call", async () => {
  const timeout = (_input: RequestInfo | URL, init?: RequestInit) => {
    assert(init?.signal instanceof AbortSignal);
    assert(!init.signal.aborted);
    return Promise.reject(new DOMException("Signal timed out.", "TimeoutError"));
  };
  const oauth = await assertRejects(() => accessToken(account(), now, timeout), DOMException);
  assertEquals(oauth.name, "TimeoutError");
  const fcm = await assertRejects(
    () => sendMessage("test-project", "test-access", "device", timeout),
    DOMException,
  );
  assertEquals(fcm.name, "TimeoutError");
});

Deno.test("FCM HTTP failure rejects with fcm_failed after one attempt and cancels the unread body", async () => {
  for (const status of [400, 401, 403, 404, 429, 500, 503]) {
    const { response, state } = cancellable(status);
    const f = countingFetch(() => response);
    await assertRejects(
      () => sendMessage("test-project", "test-access", "device", f.fetch),
      Error,
      "fcm_failed",
    );
    assertEquals(f.calls(), 1, String(status));
    assert(state.cancelled, String(status));
  }
});

Deno.test("FCM success without a message name rejects with fcm_invalid_response", async () => {
  for (const body of [{}, { name: "" }, { name: 1 }, { name: null }, { error: { code: 200 } }]) {
    await assertRejects(
      () =>
        sendMessage(
          "test-project",
          "test-access",
          "device",
          () => Promise.resolve(Response.json(body)),
        ),
      Error,
      "fcm_invalid_response",
      JSON.stringify(body),
    );
  }
});

Deno.test("FCM project id is URL-encoded and the device token travels only in the JSON body", async () => {
  const device = 'dev:ice/token?x=1&y="z"';
  await sendMessage("a/b c", "test-access", device, async (input, init) => {
    const request = new Request(input, init);
    assertEquals(request.method, "POST");
    assertEquals(request.url, "https://fcm.googleapis.com/v1/projects/a%2Fb%20c/messages:send");
    assertEquals(request.headers.get("content-type"), "application/json");
    assertEquals(await request.json(), message(device));
    return Response.json({ name: "projects/a/messages/1" });
  });
});
