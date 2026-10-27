import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { DispatchStore, QueueItem } from "./dispatch.ts";

export async function pendingNotifications(db: SupabaseClient, now: Date): Promise<QueueItem[]> {
  return data(
    await db.from("notification_queue").select("*").is("sent_at", null).is("cancelled_at", null)
      .lte("due_at", now.toISOString()).lte("next_attempt_at", now.toISOString())
      .order("next_attempt_at").order("due_at").limit(20),
  ) as QueueItem[];
}

type ResponseData<R> = R extends { error: null; data: infer T } ? T : never;
function data<R extends { data: unknown; error: unknown }>(result: R): ResponseData<R> {
  if (result.error) throw new Error("dispatch_database_failed");
  return result.data as ResponseData<R>;
}

export function createDispatchStore(
  db: SupabaseClient,
  send: (token: string) => Promise<void>,
): DispatchStore {
  const claim = crypto.randomUUID();
  return {
    async claim(q) {
      const now = new Date();
      const stale = new Date(now.getTime() - 5 * 60_000).toISOString();
      const claimed = data(
        await db.from("notification_queue").update({
          claim_token: claim,
          claimed_at: now.toISOString(),
        })
          .eq("id", q.id).is("sent_at", null).is("cancelled_at", null).lte(
            "due_at",
            now.toISOString(),
          ).lte("next_attempt_at", now.toISOString())
          .or(`claimed_at.is.null,claimed_at.lt.${stale}`).select("id"),
      );
      return claimed.length === 1;
    },
    async state(q) {
      // No cached link: read on every send, including after waiting for external OAuth.
      const queue = data(
        await db.from("notification_queue").select("*").eq("id", q.id).eq("claim_token", claim)
          .maybeSingle(),
      );
      const link = data(
        await db.from("family_links").select("revoked_at,delay_days")
          .eq("driver_id", q.driver_id).eq("family_id", q.family_id).maybeSingle(),
      );
      const summary = data(
        await db.from("monthly_summaries").select("created_at,stage")
          .eq("driver_id", q.driver_id).eq("month", q.month).maybeSingle(),
      );
      return { queue: queue as QueueItem | null, link, summary };
    },
    async tokens(q) {
      return data(await db.from("push_tokens").select("token").eq("user_id", q.family_id)).map((
        r,
      ) => r.token as string);
    },
    send,
    async complete(q) {
      data(
        await db.from("notification_queue").update({ sent_at: new Date().toISOString() })
          .eq("id", q.id).eq("claim_token", claim).is("sent_at", null).is("cancelled_at", null),
      );
    },
    async forget(q, token) {
      data(await db.from("push_tokens").delete().eq("user_id", q.family_id).eq("token", token));
    },
    async release(q) {
      data(
        await db.from("notification_queue").update({
          claim_token: null,
          claimed_at: null,
          next_attempt_at: new Date(Date.now() + 10 * 60_000).toISOString(),
        }).eq("id", q.id).eq("claim_token", claim),
      );
    },
  };
}
