// Expense text -> Gemini -> raw model reply. The key never leaves the server;
// the app validates the reply (parseGeminiJson). Signed-in users only: the
// gateway verifies the JWT, and we reject non-user (anon) tokens here.

const MODEL = "gemini-3.6-flash"; // 2.0-flash shut down 2026-06-01
const KEY = Deno.env.get("GEMINI_API_KEY");

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (req) => {
  const token = req.headers.get("Authorization")?.replace("Bearer ", "") ?? "";
  let role = "";
  try {
    role = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).role;
  } catch { /* not a JWT */ }
  if (role !== "authenticated") return json({ error: "sign_in_required" }, 401);
  if (!KEY) return json({ error: "not_configured" }, 500);

  const input = (await req.json().catch(() => ({})))?.input;
  if (typeof input !== "string" || !input.trim() || input.length > 500) {
    return json({ error: "bad_input" }, 400);
  }

  const prompt =
    "Extract an expense from the user text. Reply with ONLY strict JSON: " +
    '{"item": string, "amount": integer whole UZS units, "category": string}. ' +
    "Pick a concise, reusable category name. No prose, no markdown.\n\n" +
    `User text: ${JSON.stringify(input.trim())}`;

  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": KEY },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { responseMimeType: "application/json" },
      }),
    },
  );
  if (!res.ok) return json({ error: "ai_failed", status: res.status }, 502);
  const data = await res.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  return typeof text === "string" ? json({ text }) : json({ error: "ai_empty" }, 502);
});
