// Retired: push delivery is FCM-only and backed by the Cloudflare D1 gateway.
export function handleRequest(_req: Request): Response {
  return Response.json({ error: 'ONESIGNAL_RETIRED', replacement: '/functions/v1/send-fcm-notification' }, { status: 410 });
}
if (import.meta.main) Deno.serve(handleRequest);
