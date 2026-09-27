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

  const body = await req.json().catch(() => ({}));
  const input = body?.input;
  if (typeof input !== "string" || !input.trim() || input.length > 500) {
    return json({ error: "bad_input" }, 400);
  }
  // Device-local date, so "kecha" resolves in the user's timezone.
  const today = /^\d{4}-\d{2}-\d{2}$/.test(body?.today ?? "")
    ? body.today
    : new Date().toISOString().slice(0, 10);
  const cats = Array.isArray(body?.categories)
    ? body.categories.filter((c: unknown) => typeof c === "string").slice(0, 50)
    : [];

  const prompt =
    "Extract one expense from the user text (usually Uzbek, maybe Russian or English). " +
    "Reply with ONLY strict JSON: " +
    '{"item": string, "amount": integer, "category": string, "date": "YYYY-MM-DD"}. ' +
    "amount: whole UZS as a plain integer; \"ming\" = x1000, \"mln\"/\"million\" = x1000000, " +
    "\"so'm\"/\"sum\" = UZS (e.g. \"10 ming so'm\" -> 10000). " +
    "item: short noun in the user's language. " +
    "category: pick the best fit from existing categories " +
    `${JSON.stringify(cats)}, copied exactly; only if none fits, a new concise ` +
    "reusable name in Uzbek. " +
    `date: today is ${today}; resolve words like "kecha" (yesterday) or weekday names ` +
    "to the most recent past date; if no date is mentioned use today. " +
    'Example: "kecha 10 ming so\'mga qurt oldim" -> ' +
    '{"item":"qurt","amount":10000,"category":"Oziq-ovqat","date":"<yesterday>"}. ' +
    "No prose, no markdown.\n\n" +
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
  if (!res.ok) {
    console.error("gemini", res.status, await res.text());
    return json({ error: "ai_failed", status: res.status }, 502);
  }
  const data = await res.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (typeof text !== "string") {
    console.error("gemini empty", JSON.stringify(data));
    return json({ error: "ai_empty" }, 502);
  }
  return json({ text });
});
