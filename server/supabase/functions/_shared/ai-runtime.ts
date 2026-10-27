import Anthropic from "npm:@anthropic-ai/sdk@1";
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
export interface Caller {
  id: string;
  role: string | null;
}
export type Authenticate = (jwt: string) => Promise<Caller | null>;
export function serviceDb(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: {
      fetch: (input, init) => {
        const request = new Request(input, init);
        return fetch(request, {
          signal: AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]),
        });
      },
    },
  });
}
export function authenticateWith(db: SupabaseClient): Authenticate {
  return async (jwt) => {
    const { data, error } = await db.auth.getUser(jwt);
    if (error || !data.user) return null;
    const profile = await db.from("profiles").select("role").eq("id", data.user.id).maybeSingle();
    if (profile.error) throw new Error("authentication_unavailable");
    return { id: data.user.id, role: profile.data?.role ?? null };
  };
}
export function opusText(
  prompt: { system: string; user: string },
  opts: { apiKey: string; fetch?: typeof fetch; signal?: AbortSignal },
): Promise<string> {
  // guarded owns the single retry and the total deadline; no nested SDK retries.
  const client = new Anthropic({
    apiKey: opts.apiKey,
    fetch: opts.fetch,
    timeout: 20_000,
    maxRetries: 0,
    // Only guard verdict codes may be logged, regardless of ANTHROPIC_LOG.
    logLevel: "off",
  });
  return client.beta.messages.create({
    model: "claude-opus-5-5",
    max_tokens: 16000,
    output_config: { effort: "low" },
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    system: prompt.system,
    messages: [{ role: "user", content: prompt.user }],
  }, { signal: opts.signal }).then((response) => {
    if (response.stop_reason !== "end_turn") throw new Error("incomplete_generation");
    return response.content.filter((block) => block.type === "text").map((block) => block.text)
      .join("").trim();
  });
}
export const errorResponse = (error: string, status: number) =>
  Response.json({ error }, { status });
export function bearer(request: Request): string | undefined {
  return request.headers.get("authorization")?.match(/^Bearer\s+(\S+)$/i)?.[1];
}
