import { proxyWorker } from '../_shared/cloudflare-proxy.ts';

// Legacy route forwards to Cloudflare; counter reads and acknowledgements are not stored in Supabase.
export const handleRequest = (req: Request) => proxyWorker(req, '/v1/views');
if (import.meta.main) Deno.serve(handleRequest);
