import { assert, assertFalse } from "jsr:@std/assert@1";
import { isServiceRoleBearer } from "../supabase/functions/_shared/auth.ts";

const header = btoa(JSON.stringify({ alg: "HS256", typ: "JWT" })).replace(/=+$/, "");
const url = (standard: string) =>
  standard.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const bearer = (payload: string) => `Bearer ${header}.${payload}.sig`;

Deno.test("base64url payloads with - and _ characters decode at every padding length", () => {
  for (const pad of ["", "x", "xx"]) {
    const standard = btoa(JSON.stringify({ role: "service_role", n: "??>>??>>", pad }));
    // Precondition: the standard alphabet needs translating, so the - and _ handling is exercised.
    assert(standard.includes("+") && standard.includes("/"), standard);
    assert(isServiceRoleBearer(bearer(url(standard))), pad);
  }
});

Deno.test("role must be exactly service_role at the top level of a JSON object payload", () => {
  for (
    const payload of [
      { role: "SERVICE_ROLE" },
      { role: "service_role " },
      { role: ["service_role"] },
      { app_metadata: { role: "service_role" } },
      {},
      null,
      "service_role",
    ]
  ) {
    assertFalse(
      isServiceRoleBearer(bearer(url(btoa(JSON.stringify(payload))))),
      JSON.stringify(payload),
    );
  }
  assertFalse(isServiceRoleBearer(bearer(url(btoa("not json")))));
  assertFalse(isServiceRoleBearer(bearer("a")));
  assertFalse(isServiceRoleBearer(`Bearer ${header}..sig`));
  assertFalse(isServiceRoleBearer(""));
});

// Design (auth.ts comment): the gateway's verify_jwt checks the signature, so this helper only
// reads the role. The scheme match is case-sensitive, unlike monthly-letter's /i match; the only
// caller is the pg_cron job, which sends "Bearer ".
Deno.test("scheme is case-sensitive with one token, and the role is read without verifying the signature", () => {
  const payload = url(btoa(JSON.stringify({ role: "service_role" })));
  assert(isServiceRoleBearer(`Bearer \t ${header}.${payload}.sig`));
  assert(isServiceRoleBearer(`Bearer x.${payload}`));
  assert(isServiceRoleBearer(`Bearer x.${payload}.forged.extra`));
  assertFalse(isServiceRoleBearer(`bearer ${header}.${payload}.sig`));
  assertFalse(isServiceRoleBearer(`Bearer ${header}.${payload}.sig `));
  assertFalse(isServiceRoleBearer(`Bearer ${header}.${payload}.sig other`));
  assertFalse(isServiceRoleBearer(`Bearer${header}.${payload}.sig`));
});
