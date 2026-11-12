// Runtime app configuration served from Cloudflare, not from Firebase Remote Config.
//
// Firebase is authentication only in this product, so the emergency switches live here: flipping a
// Worker variable changes the app's behaviour without an app update and without a Firebase console.
const json = (body, status = 200) => Response.json(body, {
  status, headers: {
    // Short-lived on purpose: an admin flip should reach open apps within minutes, and every value
    // here is a public boolean - no uid, no data, no secret.
    'Cache-Control': 'public, max-age=300', 'X-Content-Type-Options': 'nosniff',
  },
});

export function appConfig(request, env) {
  if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
  return json({
    adsEnabled: env.ADS_ENABLED !== 'false',
    presence: env.REALTIME_WS_ENABLED !== 'false',
    cloudflareData: true,
    firebase: 'auth-only',
  });
}
