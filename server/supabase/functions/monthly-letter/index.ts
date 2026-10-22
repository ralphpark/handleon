import { createClient } from "npm:@supabase/supabase-js@2";
import { generateLetter, parseLetterRequest } from "../_shared/letter.ts";

export async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405 });
  }
  const jwt = request.headers.get("authorization")?.match(/^Bearer\s+(\S+)$/i)?.[1];
  if (!jwt) return Response.json({ error: "unauthorized" }, { status: 401 });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const db = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: auth, error: authError } = await db.auth.getUser(jwt);
    if (authError || !auth.user) return Response.json({ error: "unauthorized" }, { status: 401 });
    const { data: link, error: linkError } = await db.from("car_links").select("driver_id").eq(
      "car_id",
      auth.user.id,
    ).maybeSingle();
    if (linkError) throw new Error("database_error");
    if (!link) return Response.json({ error: "linked_car_required" }, { status: 403 });
    let body;
    try {
      body = parseLetterRequest(await request.json());
    } catch {
      return Response.json({ error: "invalid_request" }, { status: 400 });
    }
    const { data: summary, error: readError } = await db.from("monthly_summaries").select(
      "created_at,mode,stage",
    )
      .eq("driver_id", link.driver_id).eq("month", body.month).maybeSingle();
    if (readError) throw new Error("database_error");
    if (!summary) return Response.json({ error: "summary_not_found" }, { status: 404 });
    if (summary.mode !== body.mode || summary.stage !== body.stage) {
      return Response.json({ error: "summary_changed" }, { status: 409 });
    }
    const result = await generateLetter(body, { apiKey: Deno.env.get("ANTHROPIC_API_KEY") ?? "" });
    // Service-role write: verify/lock the car relationship and save in the SAME transaction.
    const { error: saveError } = await db.rpc("save_monthly_letter", {
      p_car_id: auth.user.id,
      p_driver_id: link.driver_id,
      p_month: body.month,
      p_created_at: summary.created_at,
      p_letter: result.letter,
    });
    if (saveError?.message === "linked_car_required") {
      return Response.json({ error: "linked_car_required" }, { status: 403 });
    }
    if (saveError?.message === "summary_changed") {
      return Response.json({ error: "summary_changed" }, { status: 409 });
    }
    if (saveError) throw new Error("database_error");
    return Response.json(result);
  } catch {
    return Response.json({ error: "letter_unavailable" }, { status: 500 });
  }
}
if (import.meta.main) Deno.serve(handler);
