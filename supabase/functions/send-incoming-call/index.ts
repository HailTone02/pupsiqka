type DatabaseCall = {
  id: string;
  caller_user_id: string;
  callee_user_id: string;
  status: string;
};

type DatabaseWebhook = {
  type?: string;
  table?: string;
  schema?: string;
  record?: { id?: string; status?: string };
  old_record?: { status?: string };
};

type ServiceAccount = {
  client_email: string;
  private_key: string;
  project_id: string;
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CALL_STATUSES = new Set([
  "preparing",
  "ringing",
  "accepted",
  "connected",
  "completed",
  "declined",
  "cancelled",
  "missed",
  "failed",
]);

function base64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function constantTimeEqual(left: string, right: string): boolean {
  const encoder = new TextEncoder();
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  let difference = leftBytes.length ^ rightBytes.length;
  const length = Math.max(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index++) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}

async function createFcmAccessToken(account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const unsignedToken = `${header}.${claims}`;
  const privateKeyBytes = Uint8Array.from(
    atob(account.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")),
    (character) => character.charCodeAt(0),
  );
  const privateKey = await crypto.subtle.importKey(
    "pkcs8",
    privateKeyBytes,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(unsignedToken),
  ));
  const assertion = `${unsignedToken}.${base64Url(signature)}`;
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!tokenResponse.ok) throw new Error("FCM authorization unavailable");
  const tokenBody = await tokenResponse.json() as { access_token?: string };
  if (!tokenBody.access_token) throw new Error("FCM authorization unavailable");
  return tokenBody.access_token;
}

async function fetchCall(supabaseUrl: string, serviceKey: string, callId: string): Promise<DatabaseCall | null> {
  const url = new URL("/rest/v1/call_sessions", supabaseUrl);
  url.search = new URLSearchParams({ id: `eq.${callId}`, select: "id,caller_user_id,callee_user_id,status", limit: "1" }).toString();
  const response = await fetch(url, {
    headers: { apikey: serviceKey, authorization: `Bearer ${serviceKey}` },
  });
  if (!response.ok) throw new Error("Call verification unavailable");
  const rows = await response.json() as DatabaseCall[];
  return rows[0] ?? null;
}

async function fetchTokens(supabaseUrl: string, serviceKey: string, userId: string): Promise<string[]> {
  const url = new URL("/rest/v1/call_push_tokens", supabaseUrl);
  url.search = new URLSearchParams({ user_id: `eq.${userId}`, select: "fcm_token" }).toString();
  const response = await fetch(url, {
    headers: { apikey: serviceKey, authorization: `Bearer ${serviceKey}` },
  });
  if (!response.ok) throw new Error("Push recipients unavailable");
  const rows = await response.json() as Array<{ fcm_token?: string }>;
  return rows.map((row) => row.fcm_token).filter((token): token is string => typeof token === "string" && token.length > 0);
}

async function sendWakeup(projectId: string, accessToken: string, fcmToken: string, callId: string): Promise<void> {
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token: fcmToken,
        data: { call_id: callId },
        android: { priority: "HIGH", ttl: "60s" },
      },
    }),
  });
  if (!response.ok) throw new Error("FCM delivery failed");
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const webhookSecret = Deno.env.get("CALL_PUSH_WEBHOOK_SECRET") ?? "";
  const suppliedSecret = request.headers.get("x-call-push-webhook-secret") ?? "";
  if (!webhookSecret || !constantTimeEqual(suppliedSecret, webhookSecret)) {
    return new Response("Unauthorized", { status: 401 });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const serviceAccountJson = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";
  if (!supabaseUrl || !serviceKey || !serviceAccountJson) {
    return new Response("Push sender is not configured", { status: 503 });
  }

  let webhook: DatabaseWebhook;
  try {
    webhook = await request.json() as DatabaseWebhook;
  } catch {
    return new Response("Invalid webhook", { status: 400 });
  }
  const callId = webhook.record?.id;
  if (webhook.table !== "call_sessions" || webhook.schema !== "public" || webhook.type !== "UPDATE" ||
    !callId || !UUID_PATTERN.test(callId) || !CALL_STATUSES.has(webhook.record?.status ?? "")
  ) {
    return new Response("Ignored", { status: 202 });
  }

  try {
    const account = JSON.parse(serviceAccountJson) as ServiceAccount;
    if (!account.client_email || !account.private_key || !account.project_id) {
      return new Response("Push sender is not configured", { status: 503 });
    }
    const call = await fetchCall(supabaseUrl, serviceKey, callId);
    if (!call || call.id !== callId || !UUID_PATTERN.test(call.caller_user_id) ||
      !UUID_PATTERN.test(call.callee_user_id) || call.caller_user_id === call.callee_user_id ||
      !CALL_STATUSES.has(call.status)
    ) {
      return new Response("Ignored", { status: 202 });
    }
    const tokens = await fetchTokens(supabaseUrl, serviceKey, call.callee_user_id);
    if (tokens.length === 0) return new Response("No registered devices", { status: 202 });
    const accessToken = await createFcmAccessToken(account);
    await Promise.all(tokens.map((token) => sendWakeup(account.project_id, accessToken, token, call.id)));
    return new Response("Accepted", { status: 202 });
  } catch {
    return new Response("Push processing failed", { status: 503 });
  }
});