import { createClient } from "npm:@supabase/supabase-js@2";
import { dispatchOne } from "../_shared/dispatch.ts";
import { createDispatchStore, pendingNotifications } from "../_shared/dispatch-store.ts";
import { accessToken, sendMessage, serviceAccount } from "../_shared/fcm.ts";
import { isServiceRoleBearer } from "../_shared/auth.ts";

export async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405 });
  }
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!key || !isServiceRoleBearer(request.headers.get("authorization"))) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  try {
    const db = createClient(Deno.env.get("SUPABASE_URL")!, key, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const queue = await pendingNotifications(db, new Date());
    if (!queue.length) return Response.json({ sent: 0, skipped: 0, failed: 0 });
    const json = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";
    const account = serviceAccount(json);
    // Obtain OAuth first, so the last link recheck is immediately before each FCM HTTP call.
    const token = await accessToken(json, new Date());
    const store = createDispatchStore(
      db,
      (device) => sendMessage(account.project_id, token, device),
    );
    let sent = 0, skipped = 0, failed = 0;
    for (const q of queue) {
      try {
        if (await dispatchOne(q, store)) sent++;
        else skipped++;
      } catch {
        failed++;
      }
    }
    return Response.json({ sent, skipped, failed }, { status: failed ? 503 : 200 });
  } catch {
    return Response.json({ error: "dispatch_unavailable" }, { status: 500 });
  }
}
if (import.meta.main) Deno.serve(handler);
