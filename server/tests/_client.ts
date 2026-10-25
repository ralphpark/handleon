import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";

// No dotenv/file reads. Missing environment configuration fails before client creation/network.
// Deno CLI loads the three values only when Claude explicitly supplies --env-file=.env.test.
const url = Deno.env.get("SUPABASE_URL");
const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!url || !anonKey || !serviceKey) {
  throw new Error(
    "Integration tests require --env-file=.env.test with all three SUPABASE_* values. Never run against production.",
  );
}
const options = {
  auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
};
export const admin: SupabaseClient = createClient(url, serviceKey, options);
export type Actor = { client: SupabaseClient; id: string };
export type Scope = "stage" | "trend" | "detail";
const users: string[] = [];

type ResponseData<R> = R extends { error: null; data: infer T } ? T : never;
export function ok<R extends { data: unknown; error: { message: string } | null }>(
  response: R,
): ResponseData<R> {
  if (response.error) throw new Error(response.error.message);
  return response.data as ResponseData<R>;
}
export async function newUser(role: "driver" | "family"): Promise<Actor> {
  const email = `handleon-${crypto.randomUUID()}@example.test`;
  const password = crypto.randomUUID() + "aA9!";
  const { user } = ok(await admin.auth.admin.createUser({ email, password, email_confirm: true }));
  if (!user) throw new Error("test_user_missing");
  users.push(user.id);
  const client = createClient(url!, anonKey!, options);
  ok(await client.auth.signInWithPassword({ email, password }));
  ok(await client.from("profiles").insert({ id: user.id, role, display_name: "시험 사용자" }));
  return { client, id: user.id };
}
export async function newCar(): Promise<Actor> {
  const client = createClient(url!, anonKey!, options);
  const { user } = ok(await client.auth.signInAnonymously());
  if (!user) throw new Error("test_car_missing");
  users.push(user.id);
  return { client, id: user.id };
}
export async function linkedPair() {
  const driver = await newUser("driver");
  const car = await newCar();
  const code = ok(await car.client.rpc("create_pairing_code"));
  ok(await driver.client.rpc("claim_pairing", { p_code: code }));
  return { driver, car };
}
export async function sharedPair(scope: Scope, delay: 0 | 3 | 7 | 14, pattern = false) {
  const pair = await linkedPair();
  const family = await newUser("family");
  const code = ok(
    await pair.driver.client.rpc("create_invite", { p_scope: scope, p_delay_days: delay, p_share_pattern: pattern }),
  );
  ok(await family.client.rpc("accept_invite", { p_code: code }));
  return { ...pair, family };
}
export function row(driverId: string, overrides: Record<string, unknown> = {}) {
  return {
    driver_id: driverId,
    month: "2026-10-01",
    mode: "maeum",
    stage: "stable",
    domains: { mobility: 0.2, wayfinding: 0.1 },
    top: [{ feature: "W3", text: "길을 찾는 흐름이 달라졌어요", grade: "C" }],
    source: "car",
    ...overrides,
  };
}
export async function seedMonth(
  driverId: string,
  opts: { month?: string; createdAt?: string; stage?: string } = {},
) {
  return ok(
    await admin.from("monthly_summaries").upsert(row(driverId, {
      month: opts.month ?? "2026-10-01",
      created_at: opts.createdAt ?? new Date().toISOString(),
      stage: opts.stage ?? "stable",
      letter: "이번 달도 함께 돌아보아요. 핸들온 드림",
    })).select().single(),
  );
}
export const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();
// Per-test cleanup even when an assertion fails; storage tests remove their objects first.
export function integrationTest(name: string, fn: () => Promise<void>) {
  Deno.test(name, async () => {
    try {
      await fn();
    } finally {
      const errors: string[] = [];
      for (const id of users.splice(0).reverse()) {
        const { error } = await admin.auth.admin.deleteUser(id);
        if (error) errors.push(error.message);
      }
      if (errors.length) throw new Error(`test_cleanup_failed: ${errors.join(", ")}`);
    }
  });
}
