const D1_API = (Deno.env.get('D1_API_URL') || 'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev').replace(/\/$/, '');

async function request(path: string, token: string, method = 'GET', body?: unknown): Promise<any> {
  const response = await fetch(`${D1_API}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(10000),
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error('D1_DATA_UNAVAILABLE');
  return response.json();
}

export async function getD1Document(path: string, token: string): Promise<Record<string, any> | null> {
  const data = await request(`/v1/firestore/document?path=${encodeURIComponent(path)}`, token);
  return data?.exists === false ? null : (data?.data && typeof data.data === 'object' ? data.data : null);
}

export async function queryD1Documents(parent: string, filters: Array<{field:string;op:string;value:unknown}>, limit: number, token: string) {
  const data = await request('/v1/firestore/query', token, 'POST', { parent, filters, limit: Math.max(1, Math.min(50, limit)) });
  return Array.isArray(data?.documents) ? data.documents : [];
}

export async function putD1Document(path: string, data: Record<string, unknown>, token: string, createOnly = false) {
  return request(`/v1/firestore/document?path=${encodeURIComponent(path)}`, token, 'PUT', { data, createOnly });
}
