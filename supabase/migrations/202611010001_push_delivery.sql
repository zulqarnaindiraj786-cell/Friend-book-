-- Durable replay protection + per-actor rate limit. Only the Edge Function service role can claim.
create table if not exists public.push_delivery_claims (
  event_key text primary key,
  actor_id text not null,
  created_at timestamptz not null default now()
);
alter table public.push_delivery_claims enable row level security;
revoke all on public.push_delivery_claims from anon, authenticated;
grant all on public.push_delivery_claims to service_role;
create or replace function public.claim_push(p_event text, p_actor text)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform pg_advisory_xact_lock(hashtext(p_actor));
  if (select count(*) from push_delivery_claims where actor_id = p_actor and created_at > now() - interval '1 minute') >= 60 then
    return false;
  end if;
  insert into push_delivery_claims(event_key, actor_id) values (p_event, p_actor) on conflict do nothing;
  return found;
end $$;
revoke all on function public.claim_push(text, text) from public, anon, authenticated;
grant execute on function public.claim_push(text, text) to service_role;
create index if not exists push_delivery_actor_time on public.push_delivery_claims(actor_id, created_at);
