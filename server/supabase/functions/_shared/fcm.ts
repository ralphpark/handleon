const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const encoder = new TextEncoder();
const base64url = (data: Uint8Array) =>
  btoa(String.fromCharCode(...data)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");

export function serviceAccount(
  json: string,
): { client_email: string; private_key: string; project_id: string } {
  const data = JSON.parse(json);
  if (
    !data || typeof data.client_email !== "string" || typeof data.private_key !== "string" ||
    typeof data.project_id !== "string" || !data.client_email ||
    !/^[a-z0-9][a-z0-9-]+$/.test(data.project_id)
  ) {
    throw new Error("invalid_service_account");
  }
  return data;
}

export async function accessToken(
  serviceAccountJson: string,
  now: Date,
  fetchImpl: typeof fetch = fetch,
): Promise<string> {
  const account = serviceAccount(serviceAccountJson);
  const iat = Math.floor(now.getTime() / 1000);
  if (!Number.isFinite(iat)) throw new Error("invalid_time");
  const pem = account.private_key.replace(
    /-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,
    "",
  );
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const header = base64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = base64url(
    encoder.encode(
      JSON.stringify({
        iss: account.client_email,
        scope: SCOPE,
        aud: TOKEN_URL,
        iat,
        exp: iat + 3600,
      }),
    ),
  );
  const signingInput = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    encoder.encode(signingInput),
  );
  const response = await fetchImpl(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${signingInput}.${base64url(new Uint8Array(signature))}`,
    }),
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error("oauth_failed");
  }
  const body = await response.json();
  if (typeof body.access_token !== "string" || !body.access_token) {
    throw new Error("oauth_invalid_response");
  }
  return body.access_token;
}

export function message(token: string): object {
  return {
    message: { token, notification: { title: "핸들온", body: "새 운전 요약이 도착했어요" } },
  };
}

export async function sendMessage(
  project: string,
  access: string,
  token: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  const response = await fetchImpl(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(project)}/messages:send`,
    {
      method: "POST",
      headers: { "Authorization": `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify(message(token)),
      signal: AbortSignal.timeout(20_000),
    },
  );
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error("fcm_failed");
  }
  const body = await response.json();
  if (typeof body.name !== "string" || !body.name) throw new Error("fcm_invalid_response");
}
