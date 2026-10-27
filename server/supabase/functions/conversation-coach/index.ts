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
  buildCoachPrompt,
  COACH_FALLBACK,
  type CoachAdvice,
  parseCoachAdvice,
} from "../_shared/advice.ts";
import { guarded, haikuJudge, type Judge } from "../_shared/guard.ts";
export type { CoachAdvice } from "../_shared/advice.ts";
type Result = { advice: CoachAdvice; source: "ai" | "fallback" };
type Reservation =
  | { status: "share_not_found" | "daily_limit" | "pending" }
  | { status: "cached"; result: Result }
  | { status: "generate"; context: unknown; day: string; input_hash: string };
type Finish = "ok" | "share_not_found" | "context_changed";
export interface CoachDeps {
  authenticate: Authenticate;
  begin: (family: string, driver: string) => Promise<Reservation>;
  finish: (
    family: string,
    driver: string,
    day: string,
    hash: string,
    result: Result,
  ) => Promise<Finish>;
  generate: (context: unknown) => Promise<unknown>;
  judge: Judge;
  timeoutMs?: number;
  onTimeout?: () => void;
}
export function createCoachStore(db: SupabaseClient): Pick<CoachDeps, "begin" | "finish"> {
  return {
    async begin(family, driver) {
      const { data, error } = await db.rpc("begin_coach_request", {
        p_family: family,
        p_driver: driver,
      });
      if (error || !data) throw new Error("coach_unavailable");
      return data;
    },
    async finish(family, driver, day, hash, result) {
      const { data, error } = await db.rpc("finish_coach_request", {
        p_family: family,
        p_driver: driver,
        p_day: day,
        p_input_hash: hash,
        p_result: result,
      });
      if (error || !data) throw new Error("coach_unavailable");
      return data;
    },
  };
}
function productionDeps(): CoachDeps {
  const db = serviceDb();
  const apiKey = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
  const controller = new AbortController();
  return {
    ...createCoachStore(db),
    authenticate: authenticateWith(db),
    generate: (context) =>
      opusText(buildCoachPrompt(context), { apiKey, signal: controller.signal }),
    judge: haikuJudge(apiKey, { signal: controller.signal }),
    onTimeout: () => controller.abort(),
  };
}
export async function handler(request: Request, injected?: CoachDeps): Promise<Response> {
  if (request.method !== "POST") return errorResponse("method_not_allowed", 405);
  const jwt = bearer(request);
  if (!jwt) return errorResponse("unauthorized", 401);
  let deps: CoachDeps;
  let caller;
  try {
    deps = injected ?? productionDeps();
    caller = await deps.authenticate(jwt);
  } catch {
    return errorResponse("unauthorized", 401);
  }
  if (!caller) return errorResponse("unauthorized", 401);
  if (caller.role !== "family") return errorResponse("family_required", 403);
  let driver: string;
  try {
    const body = await request.json();
    if (
      !body || Array.isArray(body) || Object.keys(body).length !== 1 ||
      typeof body.driver_id !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.driver_id)
    ) throw new Error();
    driver = body.driver_id;
  } catch {
    return errorResponse("invalid_request", 400);
  }
  const fallback: Result = { advice: COACH_FALLBACK, source: "fallback" };
  try {
    const reservation = await deps.begin(caller.id, driver);
    if (reservation.status === "share_not_found") return errorResponse("share_not_found", 404);
    if (reservation.status === "daily_limit") return errorResponse("daily_limit", 429);
    if (reservation.status === "cached") return Response.json(reservation.result);
    if (reservation.status !== "generate") return Response.json(fallback);
    const result = await guarded(
      async () => parseCoachAdvice(await deps.generate(reservation.context)),
      (advice) => [
        ...advice.openers,
        ...advice.avoid.map((t) => `피하면 좋은 말(avoid): ${t}`),
        advice.topic,
      ],
      COACH_FALLBACK,
      deps.judge,
      { timeoutMs: deps.timeoutMs, onTimeout: deps.onTimeout },
    );
    const response: Result = { advice: result.value, source: result.source };
    const status = await deps.finish(
      caller.id,
      driver,
      reservation.day,
      reservation.input_hash,
      response,
    );
    if (status === "share_not_found") return errorResponse("share_not_found", 404);
    return Response.json(status === "ok" ? response : fallback);
  } catch {
    return Response.json(fallback);
  }
}
if (import.meta.main) Deno.serve((request) => handler(request));
