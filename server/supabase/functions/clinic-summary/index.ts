import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import {
  type Authenticate,
  authenticateWith,
  bearer,
  errorResponse,
  opusText,
  serviceDb,
} from "../_shared/ai-runtime.ts";
import {
  buildClinicPrompt,
  capSummary,
  CLINIC_FALLBACK,
  clinicWindow,
  parseClinicSummary,
} from "../_shared/advice.ts";
import { guarded, haikuJudge, type Judge } from "../_shared/guard.ts";
export interface ClinicDeps {
  authenticate: Authenticate;
  load: (driver: string) => Promise<unknown>;
  generate: (context: unknown) => Promise<unknown>;
  judge: Judge;
  timeoutMs?: number;
  onTimeout?: () => void;
}
export async function loadClinicContext(
  db: SupabaseClient,
  driver: string,
  now = new Date(),
): Promise<unknown> {
  const w = clinicWindow(now);
  const results = await Promise.all([
    db.from("monthly_summaries").select("month,stage,domains,top").eq("driver_id", driver).gte(
      "month",
      w.monthFrom,
    ).lte("month", w.monthTo).order("month", { ascending: false }),
    db.from("weekly_habits").select(
      "week_start,trips,minutes,signal_kept,signal_total,hard_brakes,hard_accels,calm_trips,long_stretches,night_trips,promises_kept,promises_total",
    ).eq("driver_id", driver).gte("week_start", w.habitsFrom).lte("week_start", w.weekTo).order(
      "week_start",
      { ascending: false },
    ),
    db.from("weekly_changes").select("week_start,flagged,domains,top").eq("driver_id", driver).gte(
      "week_start",
      w.weeklyFrom,
    ).lte("week_start", w.weekTo).order("week_start", { ascending: false }),
  ]);
  if (results.some((r) => r.error)) throw new Error("clinic_unavailable");
  // Strip monthly top.text before the context leaves the DB adapter too.
  return JSON.parse(
    buildClinicPrompt({
      monthly: results[0].data,
      habits: results[1].data,
      weekly: results[2].data,
    }).user,
  );
}
function productionDeps(): ClinicDeps {
  const db = serviceDb();
  const apiKey = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
  const controller = new AbortController();
  return {
    authenticate: authenticateWith(db),
    load: (driver) => loadClinicContext(db, driver),
    generate: (context) =>
      opusText(buildClinicPrompt(context), { apiKey, signal: controller.signal }),
    judge: haikuJudge(apiKey, { signal: controller.signal }),
    onTimeout: () => controller.abort(),
  };
}
export async function handler(request: Request, injected?: ClinicDeps): Promise<Response> {
  if (request.method !== "POST") return errorResponse("method_not_allowed", 405);
  const jwt = bearer(request);
  if (!jwt) return errorResponse("unauthorized", 401);
  let deps: ClinicDeps;
  let caller;
  try {
    deps = injected ?? productionDeps();
    caller = await deps.authenticate(jwt);
  } catch {
    return errorResponse("unauthorized", 401);
  }
  if (!caller) return errorResponse("unauthorized", 401);
  if (caller.role !== "driver") return errorResponse("driver_required", 403);
  try {
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length) {
      throw new Error();
    }
  } catch {
    return errorResponse("invalid_request", 400);
  }
  try {
    const context = await deps.load(caller.id);
    const result = await guarded(
      async () => parseClinicSummary(await deps.generate(context)),
      (value) => [value.summary, ...value.questions],
      CLINIC_FALLBACK,
      deps.judge,
      { timeoutMs: deps.timeoutMs, onTimeout: deps.onTimeout },
    );
    return Response.json({ ...capSummary(result.value), source: result.source });
  } catch {
    return Response.json({ ...CLINIC_FALLBACK, source: "fallback" });
  }
}
if (import.meta.main) Deno.serve((request) => handler(request));
