export interface QueueItem {
  id: number;
  driver_id: string;
  family_id: string;
  month: string;
  due_at: string;
  sent_at: string | null;
  cancelled_at: string | null;
}
export interface DeliveryState {
  queue: QueueItem | null;
  link: { revoked_at: string | null; delay_days: number } | null;
  summary: { created_at: string; stage: string } | null;
}
export interface DispatchStore {
  claim(q: QueueItem): Promise<boolean>;
  state(q: QueueItem): Promise<DeliveryState>;
  tokens(q: QueueItem): Promise<string[]>;
  send(token: string): Promise<void>;
  complete(q: QueueItem): Promise<void>;
  release(q: QueueItem): Promise<void>;
}
export function canSend(state: DeliveryState, now: Date): boolean {
  const { queue, link, summary } = state;
  if (
    !queue || !link || !summary || queue.sent_at || queue.cancelled_at || link.revoked_at ||
    ![0, 3, 7, 14].includes(link.delay_days) || !["watch", "talk"].includes(summary.stage)
  ) return false;
  const currentDue = Date.parse(summary.created_at) + link.delay_days * 86_400_000;
  return Date.parse(queue.due_at) <= now.getTime() && currentDue <= now.getTime();
}

export async function dispatchOne(
  q: QueueItem,
  store: DispatchStore,
  now: () => Date = () => new Date(),
): Promise<boolean> {
  if (!await store.claim(q)) return false;
  try {
    if (!canSend(await store.state(q), now())) return false;
    const tokens = [...new Set(await store.tokens(q))];
    if (!tokens.length) return false;
    for (const token of tokens) {
      // This read occurs AFTER token/OAuth preparation and immediately before each HTTP send.
      if (!canSend(await store.state(q), now())) return false;
      await store.send(token);
    }
    if (!canSend(await store.state(q), now())) return false;
    await store.complete(q);
    return true;
  } finally {
    await store.release(q);
  }
}
