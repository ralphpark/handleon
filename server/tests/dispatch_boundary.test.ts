import {
  assert,
  assertEquals,
  assertFalse,
  assertNotEquals,
  assertRejects,
} from "jsr:@std/assert@1";
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  canSend,
  type DeliveryState,
  dispatchOne,
  type DispatchStore,
  type QueueItem,
} from "../supabase/functions/_shared/dispatch.ts";
import {
  createDispatchStore,
  pendingNotifications,
} from "../supabase/functions/_shared/dispatch-store.ts";

const DAY = 86_400_000;
const now = new Date("2026-10-22T12:00:00Z");
const at = (ms: number) => new Date(ms);
const iso = (ms: number) => new Date(ms).toISOString();
const queue: QueueItem = {
  id: 1,
  driver_id: "driver",
  family_id: "family",
  month: "2026-10-01",
  due_at: now.toISOString(),
  sent_at: null,
  cancelled_at: null,
};

function driveState(): DeliveryState {
  return {
    queue: { ...queue, kind: "drive_done", ref: "1792670000000" },
    link: { revoked_at: null, delay_days: 14 },
    summary: null,
    profile: { display_name: "가족", sharing_paused_at: null },
    options: { drive_done: "each", weekly_change: false },
    drive: { ended_at: iso(now.getTime() - 30 * 60_000), retracted_at: null },
  };
}

Deno.test("drive_done ignores delay days", () => {
  assert(canSend(driveState(), now));
  assertFalse(canSend(driveState(), at(now.getTime() - 1)));
});
Deno.test("stale drive end is never sent", () => {
  const s = driveState();
  s.drive!.ended_at = iso(now.getTime() - 6 * 3_600_000);
  assert(canSend(s, now));
  s.drive!.ended_at = iso(now.getTime() - (6 * 60 + 1) * 60_000);
  assertFalse(canSend(s, now));
});
Deno.test("retracted drive is not sent", () => {
  const s = driveState();
  s.drive!.retracted_at = now.toISOString();
  assertFalse(canSend(s, now));
});
Deno.test("drive_evening needs at least one drive", () => {
  const s = driveState();
  s.queue = { ...queue, kind: "drive_evening", ref: "2026-10-22" };
  s.options!.drive_done = "evening";
  s.evening_count = 0;
  assertFalse(canSend(s, now));
  s.evening_count = 1;
  assert(canSend(s, now));
});
Deno.test("stage rules are unchanged", () => {
  const s = state();
  s.queue!.kind = "stage";
  assert(canSend(s, now));
  assertFalse(canSend(s, at(now.getTime() - 1)));
  s.summary!.stage = "stable";
  assertFalse(canSend(s, now));
});
Deno.test("all new kinds recheck pause, revocation and options between devices", async () => {
  for (const kind of ["drive_done", "drive_evening", "weekly_change"] as const) {
    const s = driveState();
    s.queue!.kind = kind;
    s.options!.drive_done = kind === "drive_evening" ? "evening" : "each";
    s.options!.weekly_change = true;
    s.evening_count = 1;
    s.weekly = { created_at: iso(now.getTime() - 14 * DAY), flagged: true };
    for (const change of ["pause", "revoke", "option"]) {
      const f = recordingStore(s);
      f.onSend = () => {
        const changed = structuredClone(s);
        if (change === "pause") changed.profile!.sharing_paused_at = now.toISOString();
        if (change === "revoke") changed.link!.revoked_at = now.toISOString();
        if (change === "option") changed.options = { drive_done: null, weekly_change: false };
        f.set(changed);
      };
      assertFalse(await dispatchOne(s.queue!, f.store, () => now));
      assertEquals(f.sends(), ["token-a"], `${kind} ${change}`);
    }
  }
});
Deno.test("weekly_change rechecks the current upload, flag and delay", () => {
  const s = driveState();
  s.queue!.kind = "weekly_change";
  s.options!.weekly_change = true;
  s.weekly = { created_at: iso(now.getTime() - 14 * DAY), flagged: true };
  assertFalse(canSend(s, at(now.getTime() - 1)));
  assert(canSend(s, now));
  s.weekly.flagged = false;
  assertFalse(canSend(s, now));
  s.weekly = { created_at: iso(now.getTime() - DAY), flagged: true };
  assertFalse(canSend(s, now));
});
function state(delay_days = 3, stage = "talk"): DeliveryState {
  return {
    queue: { ...queue },
    link: { revoked_at: null, delay_days },
    summary: { created_at: iso(now.getTime() - delay_days * DAY), stage },
  };
}

/** Fake store that logs every call in order; `onSend` may change the state the next read sees. */
function recordingStore(
  initial: DeliveryState,
  tokens: string[] = ["token-a", "token-b"],
  claim = true,
) {
  let current = initial;
  const log: string[] = [];
  const f = {
    log,
    set: (s: DeliveryState) => {
      current = s;
    },
    onSend: (_token: string): void | Promise<void> => {},
    store: {
      claim: () => {
        log.push("claim");
        return Promise.resolve(claim);
      },
      state: () => {
        log.push("state");
        return Promise.resolve(structuredClone(current));
      },
      tokens: () => {
        log.push("tokens");
        return Promise.resolve([...tokens]);
      },
      send: async (token: string) => {
        log.push(`send:${token}`);
        await f.onSend(token);
      },
      complete: () => {
        log.push("complete");
        return Promise.resolve();
      },
      release: () => {
        log.push("release");
        return Promise.resolve();
      },
      forget: (_q: QueueItem, token: string) => {
        log.push(`forget:${token}`);
        return Promise.resolve();
      },
    } satisfies DispatchStore as DispatchStore,
    sends: () => log.filter((entry) => entry.startsWith("send:")).map((entry) => entry.slice(5)),
  };
  return f;
}

Deno.test("every supported delay is exact to the millisecond at its own recomputed due time", () => {
  for (const delay of [0, 3, 7, 14]) {
    const s = state(delay);
    // An earlier queue due_at isolates the summary.created_at + delay_days term.
    s.queue!.due_at = s.summary!.created_at;
    assertFalse(canSend(s, at(now.getTime() - 1)), `delay ${delay}`);
    assert(canSend(s, now), `delay ${delay}`);
  }
});

Deno.test("queue due_at alone gates sending when the recomputed due time has already passed", () => {
  const s = state(0);
  s.summary!.created_at = "2026-09-01T00:00:00Z";
  assertFalse(canSend(s, at(now.getTime() - 1)));
  assert(canSend(s, now));
});

Deno.test("unsupported delay values refuse to send even when long overdue", () => {
  for (const delay of [1, 2, 5, 30, -3, 3.5, NaN, Infinity, "3" as unknown as number]) {
    const s = state(3);
    s.link!.delay_days = delay;
    s.summary!.created_at = "2020-01-01T00:00:00Z";
    s.queue!.due_at = "2020-01-01T00:00:00Z";
    assertFalse(canSend(s, now), String(delay));
  }
});

Deno.test("only watch and talk stages notify; other or differently-cased stages do not", () => {
  assert(canSend(state(3, "watch"), now));
  assert(canSend(state(3, "talk"), now));
  for (const stage of ["building", "stable", "", "Talk", "WATCH", " talk"]) {
    assertFalse(canSend(state(3, stage), now), JSON.stringify(stage));
  }
});

Deno.test("unparseable timestamps fail closed", () => {
  const late = at(Date.parse("2030-01-01T00:00:00Z"));
  const badSummary = state();
  badSummary.summary!.created_at = "not a date";
  const badQueue = state();
  badQueue.queue!.due_at = "";
  for (const s of [badSummary, badQueue]) assertFalse(canSend(s, late));
});

Deno.test("Postgres timestamptz strings with offsets and microseconds compare in absolute time", () => {
  const seoul = state(3);
  seoul.queue!.due_at = "2026-10-01T00:00:00Z";
  seoul.summary!.created_at = "2026-10-19T21:00:00+09:00";
  assertFalse(canSend(seoul, at(now.getTime() - 1)));
  assert(canSend(seoul, now));
  const micro = state(3);
  micro.summary!.created_at = "2026-10-19T12:00:00.123456+00:00";
  assertFalse(canSend(micro, at(now.getTime() + 122)));
  assert(canSend(micro, at(now.getTime() + 123)));
});

Deno.test("each send is immediately preceded by a fresh consent read and completion follows a final read", async () => {
  const f = recordingStore(state());
  assert(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.log, [
    "claim",
    "state",
    "tokens",
    "state",
    "send:token-a",
    "state",
    "send:token-b",
    "state",
    "complete",
    "release",
  ]);
});

Deno.test("lost claim returns false without reading state, sending or releasing", async () => {
  const f = recordingStore(state(), ["token-a"], false);
  assertFalse(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.log, ["claim"]);
});

Deno.test("unsendable state skips the token lookup and still releases the claim", async () => {
  const f = recordingStore(state(3, "stable"));
  assertFalse(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.log, ["claim", "state", "release"]);
});

Deno.test("due exactly at the injected clock sends and completes; 1 ms earlier sends nothing", async () => {
  const early = recordingStore(state());
  assertFalse(await dispatchOne(queue, early.store, () => at(now.getTime() - 1)));
  assertEquals(early.log, ["claim", "state", "release"]);
  const exact = recordingStore(state());
  assert(await dispatchOne(queue, exact.store, () => now));
  assertEquals(exact.sends(), ["token-a", "token-b"]);
  assert(exact.log.includes("complete"));
});

Deno.test("duplicate device tokens are sent once each in first-seen order", async () => {
  const f = recordingStore(state(), ["b", "a", "b", "a", "c"]);
  assert(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.sends(), ["b", "a", "c"]);
});

Deno.test("pause, competing completion, lost claim, stage downgrade, newer upload or longer delay after the first send stops the remaining devices", async () => {
  const changes: Record<string, DeliveryState> = {
    // set_sharing_paused and revoke_all_shares cancel every pending queue row.
    "paused (queue cancelled)": {
      ...state(),
      queue: { ...queue, cancelled_at: now.toISOString() },
    },
    "sent by another dispatcher": { ...state(), queue: { ...queue, sent_at: now.toISOString() } },
    // The real store reads the queue row filtered by this dispatcher's claim token.
    "claim taken over (queue row not visible)": { ...state(), queue: null },
    "summary re-uploaded as stable": state(3, "stable"),
    "newer summary upload restarts the delay": {
      ...state(),
      summary: { created_at: iso(now.getTime() - DAY), stage: "talk" },
    },
    "delay increased to 14 days": { ...state(), link: { revoked_at: null, delay_days: 14 } },
  };
  for (const [label, changed] of Object.entries(changes)) {
    const f = recordingStore(state(), ["token-a", "token-b", "token-c"]);
    f.onSend = () => f.set(changed);
    assertFalse(await dispatchOne(queue, f.store, () => now), label);
    assertEquals(f.sends(), ["token-a"], label);
    assertFalse(f.log.includes("complete"), label);
    assertEquals(f.log.at(-1), "release", label);
  }
});

Deno.test("change during the last send blocks completion but keeps the sends already made", async () => {
  const f = recordingStore(state(), ["token-a", "token-b"]);
  f.onSend = (token) => {
    if (token === "token-b") {
      f.set({ ...state(), queue: { ...queue, cancelled_at: now.toISOString() } });
    }
  };
  assertFalse(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.sends(), ["token-a", "token-b"]);
  assertEquals(f.log.slice(-2), ["state", "release"]);
});

Deno.test("an unregistered device token is forgotten and the other devices still get the notification", async () => {
  const f = recordingStore(state(), ["token-a", "token-b", "token-c"]);
  f.onSend = (token) => {
    if (token === "token-b") throw new Error("fcm_token_unregistered");
  };
  assert(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.sends(), ["token-a", "token-b", "token-c"]);
  assert(f.log.includes("forget:token-b"));
  assert(f.log.includes("complete"));
});

Deno.test("when every device token is unregistered the queue stays unsent for a later device", async () => {
  const f = recordingStore(state(), ["token-a", "token-b"]);
  f.onSend = () => {
    throw new Error("fcm_token_unregistered");
  };
  assertFalse(await dispatchOne(queue, f.store, () => now));
  assertEquals(f.log.filter((e) => e.startsWith("forget:")), ["forget:token-a", "forget:token-b"]);
  assertFalse(f.log.includes("complete"));
  assertEquals(f.log.at(-1), "release");
});

// README "남은 한계": a partial failure leaves the queue unsent, so the next attempt resends to
// devices that already received the notification. This pins that documented behaviour.
Deno.test("partial delivery failure stops later devices, stays unsent, and the next attempt resends every device", async () => {
  const first = recordingStore(state(), ["token-a", "token-b", "token-c"]);
  first.onSend = (token) => {
    if (token === "token-b") throw new Error("fcm_failed");
  };
  await assertRejects(() => dispatchOne(queue, first.store, () => now), Error, "fcm_failed");
  assertEquals(first.sends(), ["token-a", "token-b"]);
  assertFalse(first.log.includes("complete"));
  assertEquals(first.log.at(-1), "release");
  const retry = recordingStore(state(), ["token-a", "token-b", "token-c"]);
  assert(await dispatchOne(queue, retry.store, () => now));
  assertEquals(retry.sends(), ["token-a", "token-b", "token-c"]);
});

Deno.test("errors from state, tokens or complete propagate and still release the claim", async () => {
  const fail = () => Promise.reject(new Error("dispatch_database_failed"));
  const failing: Record<string, (store: DispatchStore) => void> = {
    state: (store) => {
      store.state = fail;
    },
    tokens: (store) => {
      store.tokens = fail;
    },
    complete: (store) => {
      store.complete = fail;
    },
  };
  for (const [label, breakIt] of Object.entries(failing)) {
    const f = recordingStore(state());
    breakIt(f.store);
    await assertRejects(
      () => dispatchOne(queue, f.store, () => now),
      Error,
      "dispatch_database_failed",
    );
    assertEquals(f.log.at(-1), "release", label);
  }
});

// Design consequence: release runs in `finally`, so a release error after a successful completion
// still rejects; the handler then counts the row as failed (503) although sent_at was written.
Deno.test("release failure after a successful completion still rejects", async () => {
  const f = recordingStore(state());
  f.store.release = () => Promise.reject(new Error("dispatch_database_failed"));
  await assertRejects(
    () => dispatchOne(queue, f.store, () => now),
    Error,
    "dispatch_database_failed",
  );
  assert(f.log.includes("complete"));
  assertEquals(f.sends(), ["token-a", "token-b"]);
});

function fakeDb(respond: (request: Request) => Response | Promise<Response>) {
  return createClient("https://example.invalid", "test-only", {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { fetch: (input, init) => Promise.resolve(respond(new Request(input, init))) },
  });
}

Deno.test("pending query returns an empty list for an empty queue and a generic error on database failure", async () => {
  assertEquals(await pendingNotifications(fakeDb(() => Response.json([])), now), []);
  const failure = await assertRejects(
    () =>
      pendingNotifications(
        fakeDb(() =>
          Response.json({ message: "secret detail", code: "42501", details: null, hint: null }, {
            status: 500,
          })
        ),
        now,
      ),
    Error,
  );
  assertEquals(failure.message, "dispatch_database_failed");
});

Deno.test("store claim needs exactly one unsent, uncancelled, due and unclaimed-or-stale row", async () => {
  for (
    const [rows, expected] of [[[], false], [[{ id: 1 }], true], [
      [{ id: 1 }, { id: 2 }],
      false,
    ]] as const
  ) {
    let request: Request | undefined;
    let body: Record<string, string> = {};
    const store = createDispatchStore(
      fakeDb(async (r) => {
        request = r;
        body = await r.json();
        return Response.json(rows);
      }),
      () => Promise.resolve(),
    );
    assertEquals(await store.claim(queue), expected, JSON.stringify(rows));
    const params = new URL(request!.url).searchParams;
    const claimedAt = body.claimed_at;
    assertEquals(request!.method, "PATCH");
    assertEquals(params.get("id"), "eq.1");
    assertEquals(params.get("sent_at"), "is.null");
    assertEquals(params.get("cancelled_at"), "is.null");
    assertEquals(params.get("due_at"), `lte.${claimedAt}`);
    assertEquals(params.get("next_attempt_at"), `lte.${claimedAt}`);
    assertEquals(
      params.get("or"),
      `(claimed_at.is.null,claimed_at.lt.${iso(Date.parse(claimedAt) - 5 * 60_000)})`,
    );
    assert(body.claim_token);
  }
});

Deno.test("store state, completion and release are scoped to this dispatcher's claim token and release defers ten minutes", async () => {
  const requests: { method: string; path: string; params: URLSearchParams; body: unknown }[] = [];
  const db = fakeDb(async (r) => {
    const url = new URL(r.url);
    requests.push({
      method: r.method,
      path: url.pathname,
      params: url.searchParams,
      body: r.method === "PATCH" ? await r.json() : null,
    });
    if (r.method === "PATCH") {
      return url.searchParams.has("select")
        ? Response.json([{ id: 1 }])
        : new Response(null, { status: 204 });
    }
    return Response.json([]);
  });
  const store = createDispatchStore(db, () => Promise.resolve());
  const other = createDispatchStore(db, () => Promise.resolve());
  await store.claim(queue);
  await other.claim(queue);
  const [mine, theirs] = requests.map((r) => (r.body as { claim_token: string }).claim_token);
  assertNotEquals(mine, theirs);
  requests.length = 0;

  const read = await store.state(queue);
  assertEquals(read, { queue: null, link: null, summary: null });
  assertFalse(canSend(read, now));
  assertEquals(requests[0].path, "/rest/v1/notification_queue");
  assertEquals(requests[0].params.get("claim_token"), `eq.${mine}`);
  requests.length = 0;

  await store.complete(queue);
  assertEquals(requests[0].params.get("claim_token"), `eq.${mine}`);
  assertEquals(requests[0].params.get("sent_at"), "is.null");
  assertEquals(requests[0].params.get("cancelled_at"), "is.null");
  requests.length = 0;

  const before = Date.now();
  await store.release(queue);
  const after = Date.now();
  const released = requests[0].body as Record<string, string | null>;
  assertEquals(requests[0].params.get("claim_token"), `eq.${mine}`);
  assertEquals([released.claim_token, released.claimed_at], [null, null]);
  const next = Date.parse(released.next_attempt_at!);
  assert(next >= before + 10 * 60_000 && next <= after + 10 * 60_000);
});

Deno.test("store reads the current kind and the KST evening bucket without a monthly summary", async () => {
  for (const kind of ["drive_done", "drive_evening", "weekly_change"] as const) {
    const current = { ...queue, kind, ref: kind === "drive_done" ? "1792670000000" : "2026-10-22" };
    const reads: string[] = [];
    const db = fakeDb((r) => {
      const url = new URL(r.url);
      const table = url.pathname.split("/").at(-1)!;
      reads.push(table);
      switch (table) {
        case "notification_queue":
          return Response.json(current);
        case "family_links":
          return Response.json({ revoked_at: null, delay_days: 14 });
        case "profiles":
          return Response.json({ display_name: "가족", sharing_paused_at: null });
        case "share_options":
          return Response.json({
            drive_done: kind === "drive_evening" ? "evening" : "each",
            weekly_change: true,
          });
        case "drive_events":
          assertEquals(url.searchParams.get("driver_id"), "eq.driver");
          if (kind === "drive_done") {
            assertEquals(url.searchParams.get("outing_key"), "eq.1792670000000");
            return Response.json({ ended_at: "2026-10-22T11:30:00Z", retracted_at: null });
          }
          assertEquals(r.method, "HEAD");
          assertEquals(url.searchParams.get("retracted_at"), "is.null");
          assertEquals(url.searchParams.getAll("ended_at"), [
            "gte.2026-10-21T11:00:00.000Z",
            "lt.2026-10-22T11:00:00.000Z",
          ]);
          return new Response(null, { headers: { "content-range": "0-0/1" } });
        case "weekly_changes":
          assertEquals(url.searchParams.get("week_start"), "eq.2026-10-22");
          return Response.json({ created_at: "2026-10-08T12:00:00Z", flagged: true });
        default:
          throw new Error(`unexpected table: ${table}`);
      }
    });
    const state = await createDispatchStore(db, () => Promise.resolve()).state(queue);
    assertEquals(state.queue!.kind, kind); // use current DB row, not stale caller's stage kind
    assert(canSend(state, now), kind);
    assertFalse(reads.includes("monthly_summaries"));
  }
});

Deno.test("an evening digest expires before a newer evening can overtake it", () => {
  const s = driveState();
  s.queue = { ...queue, kind: "drive_evening", ref: "2026-10-22", due_at: "2026-10-22T11:00:00Z" };
  s.options!.drive_done = "evening";
  s.evening_count = 1;
  assert(canSend(s, new Date("2026-10-22T17:00:00Z"))); // exactly six hours after scheduled 20h KST
  assertFalse(canSend(s, new Date("2026-10-22T17:00:00.001Z")));
  assertFalse(canSend(s, new Date("2026-10-23T11:00:00Z")));
});
