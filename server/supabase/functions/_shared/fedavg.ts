export interface FedUpdate {
  delta: number[];
  n: number;
}
export interface FedResult {
  advanced: boolean;
  round: number;
  clients: number;
  reason?: "insufficient_clients" | "round_changed" | "inputs_changed";
}
export interface FedStore {
  snapshot(): Promise<unknown>;
  publish(round: number, inputHash: string, weights: number[]): Promise<FedResult>;
}
export const SIZE = 15;
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("invalid_fed_data");
  }
  return value as Record<string, unknown>;
}
function vector(value: unknown, clipped: boolean): asserts value is number[] {
  if (!Array.isArray(value) || value.length !== SIZE) throw new Error("invalid_fed_vector");
  // for..of visits sparse slots too; Array.every would silently skip them.
  for (const x of value) {
    if (typeof x !== "number" || !Number.isFinite(x) || (clipped && Math.abs(x) > 1)) {
      throw new Error("invalid_fed_vector");
    }
  }
}
function update(value: unknown): FedUpdate {
  const r = record(value);
  vector(r.delta, true);
  if (typeof r.n !== "number" || !Number.isInteger(r.n) || r.n < 1 || r.n > 520) {
    throw new Error("invalid_sample_count");
  }
  return { delta: r.delta, n: r.n };
}
export function average(updates: FedUpdate[]): number[] {
  if (!Array.isArray(updates) || !updates.length) throw new Error("updates_required");
  const sums = Array<number>(SIZE).fill(0);
  let total = 0;
  for (const raw of updates) {
    const { delta, n } = update(raw);
    total += n;
    if (!Number.isSafeInteger(total)) throw new Error("sample_count_overflow");
    for (let i = 0; i < SIZE; i++) sums[i] += delta[i] * n;
  }
  return sums.map((x) => x / total);
}
export async function runRound(store: FedStore): Promise<FedResult> {
  const r = record(await store.snapshot());
  if (
    typeof r.round !== "number" || !Number.isInteger(r.round) || r.round < 0 ||
    r.round > 2_147_483_647 ||
    typeof r.input_hash !== "string" || !/^[0-9a-f]{64}$/.test(r.input_hash) ||
    !Array.isArray(r.updates)
  ) throw new Error("invalid_fed_snapshot");
  vector(r.weights, false);
  const clients = new Set<string>();
  const updates: FedUpdate[] = [];
  for (const raw of r.updates) {
    const u = record(raw);
    if (
      typeof u.driver_id !== "string" ||
      !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(u.driver_id) ||
      typeof u.client_tag !== "string" || !["self", "virtual-1", "virtual-2"].includes(u.client_tag)
    ) throw new Error("invalid_fed_client");
    const identity = `${u.driver_id}:${u.client_tag}`;
    if (clients.has(identity)) throw new Error("duplicate_fed_client");
    clients.add(identity);
    updates.push(update(u));
  }
  if (clients.size < 2) {
    return {
      advanced: false,
      round: r.round,
      clients: clients.size,
      reason: "insufficient_clients",
    };
  }
  const delta = average(updates);
  const weights = r.weights.map((w, i) => w + delta[i]);
  vector(weights, false);
  // No automatic retry onto a different round: one invocation publishes at most once.
  return await store.publish(r.round, r.input_hash, weights);
}
