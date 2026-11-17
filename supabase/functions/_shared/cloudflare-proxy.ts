const WORKER = (Deno.env.get('CLOUDFLARE_WORKER_URL') || 'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev').replace(/\/$/, '');

export async function proxyWorker(req: Request, workerPath: string): Promise<Response> {
  const incoming = new URL(req.url);
  const headers = new Headers(req.headers);
  headers.delete('host');
  headers.delete('content-length');
  try {
    const upstream = await fetch(`${WORKER}${workerPath}${incoming.search}`, {
      method: req.method,
      headers,
      body: ['GET', 'HEAD'].includes(req.method) ? undefined : await req.arrayBuffer(),
      redirect: 'manual',
      signal: AbortSignal.timeout(25000),
    });
    return new Response(upstream.body, { status: upstream.status, headers: upstream.headers });
  } catch {
    return Response.json({ error: 'CLOUDFLARE_D1_UNAVAILABLE' }, { status: 503 });
  }
}
