import { createClient } from "npm:@supabase/supabase-js@2";
import { type FedStore, runRound } from "../_shared/fedavg.ts";
import { createFedStore } from "../_shared/fedavg-store.ts";
import { isServiceRoleBearer } from "../_shared/auth.ts";

export async function handler(request: Request, injected?: FedStore): Promise<Response> {
  if (request.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405 });
  }
  const authorization = request.headers.get("authorization");
  // verify_jwt=true verifies the signature at the gateway. PostgREST checks it again below.
  if (!isServiceRoleBearer(authorization)) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  try {
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length) {
      throw new Error();
    }
  } catch {
    return Response.json({ error: "invalid_request" }, { status: 400 });
  }
  try {
    const store = injected ?? createFedStore(createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
      {
        auth: { persistSession: false, autoRefreshToken: false },
        global: {
          // Forward the actual caller JWT, not an internally elevated bearer. A forged
          // role payload can never use the server key to bypass PostgREST signature checks.
          headers: { Authorization: authorization! },
          fetch: (input, init) => {
            const req = new Request(input, init);
            return fetch(req, {
              signal: AbortSignal.any([req.signal, AbortSignal.timeout(15_000)]),
            });
          },
        },
      },
    ));
    return Response.json(await runRound(store));
  } catch {
    return Response.json({ error: "fed_unavailable" }, { status: 503 });
  }
}
if (import.meta.main) Deno.serve((request) => handler(request));
