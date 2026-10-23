import { assert, assertFalse } from "jsr:@std/assert@1";
import { isServiceRoleBearer } from "../supabase/functions/_shared/auth.ts";

const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const jwt = (payload: unknown) => `${b64({ alg: "HS256", typ: "JWT" })}.${b64(payload)}.sig`;

Deno.test("service role bearer is accepted", () => {
  assert(isServiceRoleBearer(`Bearer ${jwt({ role: "service_role", ref: "x" })}`));
});
Deno.test("anon, authenticated, missing or malformed bearer is refused", () => {
  assertFalse(isServiceRoleBearer(`Bearer ${jwt({ role: "anon" })}`));
  assertFalse(isServiceRoleBearer(`Bearer ${jwt({ role: "authenticated" })}`));
  assertFalse(isServiceRoleBearer(null));
  assertFalse(isServiceRoleBearer("Bearer not-a-jwt"));
  assertFalse(isServiceRoleBearer(`Basic ${jwt({ role: "service_role" })}`));
});
