import { proxyWorker } from '../_shared/cloudflare-proxy.ts';

// Compatibility entry point only. Recipient selection, D1 writes, limits, and FCM delivery
// are implemented by the Cloudflare Worker; this function never accesses Supabase storage.
export const handleRequest = (req: Request) => proxyWorker(req, '/functions/v1/bulk-mentions');
if (import.meta.main) Deno.serve(handleRequest);
