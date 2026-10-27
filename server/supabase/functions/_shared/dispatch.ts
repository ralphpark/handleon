import type { NotificationNote } from "./fcm.ts";
import { ruleSafe } from "./guard.ts";

export type NotificationKind = "stage" | "drive_done" | "drive_evening" | "weekly_change";
export interface QueueItem {
  id: number;
  driver_id: string;
  family_id: string;
  month: string;
  kind?: NotificationKind;
  ref?: string | null;
  due_at: string;
  sent_at: string | null;
  cancelled_at: string | null;
}
export interface DeliveryState {
  queue: QueueItem | null;
  link: { revoked_at: string | null; delay_days: number } | null;
  summary: { created_at: string; stage: string } | null;
  profile?: { display_name: string | null; sharing_paused_at: string | null } | null;
  options?: { drive_done: "each" | "evening" | null; weekly_change: boolean } | null;
  drive?: { ended_at: string; retracted_at: string | null } | null;
  evening_count?: number;
  weekly?: { created_at: string; flagged: boolean } | null;
}
export interface DispatchStore {
  claim(q: QueueItem): Promise<boolean>;
  state(q: QueueItem): Promise<DeliveryState>;
  tokens(q: QueueItem): Promise<string[]>;
  send(token: string, note: NotificationNote): Promise<void>;
  complete(q: QueueItem): Promise<void>;
  release(q: QueueItem): Promise<void>;
  /** 등록이 풀린 기기 토큰을 지운다. */
  forget(q: QueueItem, token: string): Promise<void>;
}
export function canSend(state: DeliveryState, now: Date): boolean {
  const { queue, link, summary, profile, options, drive, weekly } = state;
  const time = now.getTime();
  if (
    !queue || !link || queue.sent_at || queue.cancelled_at || link.revoked_at ||
    profile?.sharing_paused_at || !(Date.parse(queue.due_at) <= time)
  ) return false;
  switch (queue.kind ?? "stage") {
    case "drive_done":
      return options?.drive_done === "each" && !!drive && !drive.retracted_at &&
        Date.parse(drive.ended_at) >= time - 6 * 3_600_000 &&
        Date.parse(drive.ended_at) + 30 * 60_000 <= time;
    case "drive_evening":
      return options?.drive_done === "evening" && (state.evening_count ?? 0) > 0 &&
        Date.parse(queue.due_at) >= time - 6 * 3_600_000;
    case "weekly_change":
      return options?.weekly_change === true && weekly?.flagged === true &&
        [0, 3, 7, 14].includes(link.delay_days) &&
        Date.parse(weekly.created_at) + link.delay_days * 86_400_000 <= time;
    case "stage":
      return !!summary && [0, 3, 7, 14].includes(link.delay_days) &&
        ["watch", "talk"].includes(summary.stage) &&
        Date.parse(summary.created_at) + link.delay_days * 86_400_000 <= time;
    default:
      return false;
  }
}

export function notificationNote(state: DeliveryState): NotificationNote {
  const kind = state.queue?.kind ?? "stage";
  const candidate = state.profile?.display_name?.trim();
  // Display names are user input too; do not let them introduce forbidden push copy.
  const name = candidate && ruleSafe(candidate) ? candidate : "가족";
  const bodies = {
    stage: "새 운전 요약이 도착했어요",
    drive_done: `${name}님이 운전을 마치셨어요`,
    drive_evening: `${name}님의 오늘 운전 소식이 도착했어요`,
    weekly_change: `${name}님의 최근 몇 주 운전 소식이 도착했어요`,
  };
  return { title: "핸들온", body: bodies[kind], kind };
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
      const current = await store.state(q);
      if (!canSend(current, now())) return false;
      try {
        await store.send(token, notificationNote(current));
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
