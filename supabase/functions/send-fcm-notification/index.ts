import { proxyWorker } from '../_shared/cloudflare-proxy.ts';

// Compatibility entry point only. Auth verification, D1 event/device data, and FCM delivery
// are handled by the Cloudflare Worker; no Supabase reads or writes occur here.
export const handleRequest = (req: Request) => proxyWorker(req, '/functions/v1/send-fcm-notification');
if (import.meta.main) Deno.serve(handleRequest);
