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
  /** 등록이 풀린 기기 토큰을 지운다. */
  forget(q: QueueItem, token: string): Promise<void>;
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
    let delivered = 0;
    for (const token of tokens) {
      // This read occurs AFTER token/OAuth preparation and immediately before each HTTP send.
      if (!canSend(await store.state(q), now())) return false;
      try {
        await store.send(token);
        delivered++;
      } catch (e) {
        // 꺼진 기기의 토큰 하나 때문에 다른 기기에 같은 알림이 반복되지 않게, 지우고 다음 기기로 간다.
        if (!(e instanceof Error) || e.message !== "fcm_token_unregistered") throw e;
        await store.forget(q, token);
      }
    }
    // 모든 토큰이 풀렸으면 보낸 것으로 적지 않는다(새 기기가 등록되면 그때 보낸다).
    if (!delivered || !canSend(await store.state(q), now())) return false;
    await store.complete(q);
    return true;
  } finally {
    await store.release(q);
  }
}
