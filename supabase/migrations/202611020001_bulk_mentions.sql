create table if not exists public.bulk_mention_events (
  actor text not null,
  event_key text not null,
  audience jsonb not null check (jsonb_typeof(audience) = 'array' and jsonb_array_length(audience) <= 20),
  created_at timestamptz not null default now(),
  primary key (actor, event_key)
);
create index if not exists bulk_mention_actor_time on public.bulk_mention_events(actor, created_at desc);
alter table public.bulk_mention_events enable row level security;
revoke all on public.bulk_mention_events from anon, authenticated;
create or replace function public.reserve_bulk_mention(p_actor text, p_event text, p_audience jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare saved jsonb;
begin
  if p_actor is null or p_event is null or length(p_actor)>256 or length(p_event)>520
      or jsonb_typeof(p_audience) <> 'array' or jsonb_array_length(p_audience)>20 then
    raise exception 'invalid bulk reservation';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('bulk-mention:' || p_actor,0));
  select audience into saved from public.bulk_mention_events where actor=p_actor and event_key=p_event;
  if found then return jsonb_build_object('audience',saved,'retry',true); end if;
  if exists(select 1 from public.bulk_mention_events where actor=p_actor and created_at>now()-interval '24 hours') then
    return jsonb_build_object('limited',true);
  end if;
  insert into public.bulk_mention_events(actor,event_key,audience) values(p_actor,p_event,p_audience);
  return jsonb_build_object('audience',p_audience,'retry',false);
end $$;
revoke all on function public.reserve_bulk_mention(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.reserve_bulk_mention(text,text,jsonb) to service_role;
