// Sends one queued push (private.push_outbox) through FCM HTTP v1.
// Called only by pg_net from private.push() with {"id": <outbox id>}; no JWT
// (verify_jwt off). Safe to expose: the row is claimed (deleted) here with
// the service role, so a stranger can't choose recipients or text, and a
// guessed or replayed id sends nothing.
// Secret FCM_SERVICE_ACCOUNT = the Firebase service-account JSON (whole file).

const PROJECT = "xarajatlar-app";
const SA = Deno.env.get("FCM_SERVICE_ACCOUNT");
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

type Target = { token: string; user_id: string; data: Record<string, unknown> };

const ok = (msg: string) => {
  console.log(msg);
  return new Response(msg);
};

async function rpc(fn: string, args: unknown) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify(args),
  });
  if (!res.ok) throw new Error(`${fn}: ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

const b64url = (b: Uint8Array) =>
  btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const utf8 = (s: string) => new TextEncoder().encode(s);

// OAuth access token from a self-signed service-account JWT (RS256, Web Crypto).
// ponytail: one token per call, no cache; cache by `exp` if pushes get frequent.
async function accessToken(sa: { client_email: string; private_key: string }) {
  const now = Math.floor(Date.now() / 1000);
  const part = (o: unknown) => b64url(utf8(JSON.stringify(o)));
  const unsigned = part({ alg: "RS256", typ: "JWT" }) + "." + part({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  });
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(unsigned)));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token as string;
}

Deno.serve(async (req) => {
  const id = (await req.json().catch(() => ({})))?.id;
  if (typeof id !== "string" || !/^[0-9a-f-]{36}$/i.test(id)) return new Response("bad id", { status: 400 });

  // ponytail: at-most-once. The row is gone once claimed, so an OAuth/FCM
  // outage loses that push (no retry); add a retry column if that matters.
  const targets = (await rpc("claim_push", { p_id: id })) as Target[];
  if (!SA) return ok("no FCM secret: push dropped");
  if (!targets.length) return ok("nothing to send");

  const at = await accessToken(JSON.parse(SA));
  const stale: string[] = [];
  let sent = 0;
  await Promise.all(targets.map(async (t) => { try {
    // FCM data values must be strings. `uid` lets the app drop a push meant
    // for an account that's no longer signed in on that phone.
    const data = Object.fromEntries(Object.entries({ ...t.data, uid: t.user_id }).map(([k, v]) => [k, String(v)]));
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${at}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token: t.token, data, android: { priority: "HIGH" } } }),
    });
    if (res.ok) return void sent++;
    const err = (await res.json().catch(() => ({})))?.error;
    // Gone token = 404 NOT_FOUND carrying errorCode UNREGISTERED. A bare
    // NOT_FOUND (wrong project/config) must not wipe every token.
    const gone = (err?.details ?? []).some((d: { errorCode?: string }) => d.errorCode === "UNREGISTERED");
    if (gone) stale.push(t.token);
    else console.error(`fcm ${res.status}: ${JSON.stringify(err)}`);
  } catch (e) {
    console.error(`fcm send failed: ${e}`); // one target's network error mustn't skip the rest
  } }));
  if (stale.length) await rpc("drop_push_tokens", { p_tokens: stale });
  return ok(`sent ${sent}/${targets.length}, dropped ${stale.length}`);
});
