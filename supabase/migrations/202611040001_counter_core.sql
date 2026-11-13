-- Forward-only private projection. NO source-table writes, triggers, seed or deployment.
-- Only a trusted canonical-event adapter may call these functions (never a client token).
create table public.counter_v1_state (
  uid text not null check (uid <> ''),
  kind text not null check (kind in ('notifications','requests','messages')),
  ready boolean not null default false,
  cutoff numeric(30,0) not null default 0,
  balance bigint not null default 0,
  arrivals bigint not null default 0 check (arrivals >= 0),
  revision bigint not null default 0,
  primary key (uid,kind)
);
create table public.counter_v1_events (
  uid text not null, kind text not null,
  event_id text not null check (event_id <> ''),
  source_version numeric(30,0) not null check (source_version >= 0),
  delta bigint not null,
  arrival boolean not null,
  primary key (uid,kind,event_id),
  foreign key (uid,kind) references public.counter_v1_state(uid,kind)
);
create index counter_v1_replay on public.counter_v1_events(uid,kind,source_version);
create table public.counter_v1_ack (
  uid text not null, kind text not null, device text not null check (device <> ''),
  seen bigint not null check (seen >= 0),
  primary key (uid,kind,device),
  foreign key (uid,kind) references public.counter_v1_state(uid,kind)
);
alter table public.counter_v1_state enable row level security;
alter table public.counter_v1_events enable row level security;
alter table public.counter_v1_ack enable row level security;
revoke all on public.counter_v1_state, public.counter_v1_events, public.counter_v1_ack from public, anon, authenticated;

-- Server commit-time in integer nanoseconds, transported as decimal STRING, not JS Number.
-- Every event uses its before/after delta and stable event ID, including read/delete events.
create function public.counter_v1_apply(p_uid text,p_kind text,p_event text,p_version numeric,p_delta bigint,p_arrival boolean)
returns boolean language plpgsql security definer set search_path = '' as $$
declare s public.counter_v1_state; e public.counter_v1_events; inserted integer;
begin
  if p_version is null or p_version < 0 or p_version <> trunc(p_version) or p_delta is null or p_arrival is null then
    raise exception 'Invalid canonical event';
  end if;
  insert into public.counter_v1_state(uid,kind) values(p_uid,p_kind) on conflict do nothing;
  select * into strict s from public.counter_v1_state where uid=p_uid and kind=p_kind for update;
  insert into public.counter_v1_events values(p_uid,p_kind,p_event,p_version,p_delta,p_arrival) on conflict do nothing;
  get diagnostics inserted = row_count;
  if inserted=0 then
    select * into strict e from public.counter_v1_events where uid=p_uid and kind=p_kind and event_id=p_event;
    if (e.source_version,e.delta,e.arrival) is distinct from (p_version,p_delta,p_arrival) then
      raise exception 'Conflicting event replay';
    end if;
    return false;
  end if;
  if s.ready and p_version>s.cutoff then
    -- Signed balance is intentional: a read/delete arriving before its create must commute.
    -- Clamp only in the public snapshot, not here, or subsequent arrivals become overcounted.
    update public.counter_v1_state set balance=balance+p_delta,
      arrivals=arrivals+p_arrival::integer, revision=revision+1 where uid=p_uid and kind=p_kind;
  end if;
  return true;
end $$;

-- Explicit one-time baseline from a verified consistent read-only source snapshot.
-- Events can arrive before/during bootstrap: those newer than cutoff replay exactly once.
create function public.counter_v1_seed(p_uid text,p_kind text,p_cutoff numeric,p_unread bigint)
returns boolean language plpgsql security definer set search_path = '' as $$
declare s public.counter_v1_state; changes bigint; new_arrivals bigint;
begin
  if p_cutoff is null or p_cutoff<0 or p_cutoff<>trunc(p_cutoff) or p_unread is null or p_unread<0 then
    raise exception 'Invalid baseline';
  end if;
  insert into public.counter_v1_state(uid,kind) values(p_uid,p_kind) on conflict do nothing;
  select * into strict s from public.counter_v1_state where uid=p_uid and kind=p_kind for update;
  if s.ready then return false; end if;
  select coalesce(sum(delta),0),count(*) filter(where arrival) into changes,new_arrivals
    from public.counter_v1_events where uid=p_uid and kind=p_kind and source_version>p_cutoff;
  update public.counter_v1_state set ready=true,cutoff=p_cutoff,balance=p_unread+changes,
    arrivals=p_unread+new_arrivals,revision=revision+1 where uid=p_uid and kind=p_kind;
  return true;
end $$;

-- Acknowledge ONLY the observed badge total, never unread/source rows or later arrivals.
-- Adapter must derive p_uid from the VERIFIED session, not the HTTP request body.
create function public.counter_v1_acknowledge(p_uid text,p_kind text,p_device text,p_observed bigint)
returns void language plpgsql security definer set search_path = '' as $$
declare s public.counter_v1_state;
begin
  select * into strict s from public.counter_v1_state where uid=p_uid and kind=p_kind for update;
  if not s.ready or p_observed is null or p_observed<0 then raise exception 'Counter not ready / invalid acknowledgement'; end if;
  insert into public.counter_v1_ack values(p_uid,p_kind,p_device,least(s.arrivals,p_observed))
    on conflict(uid,kind,device) do update set seen=greatest(public.counter_v1_ack.seen,excluded.seen);
end $$;

create function public.counter_v1_snapshot(p_uid text,p_device text)
returns table(kind text,ready boolean,unread_count bigint,badge_count bigint,arrival_total bigint,revision bigint)
language sql security definer set search_path = '' as $$
  select s.kind,s.ready,
    case when s.ready then greatest(0,s.balance) end,
    case when s.ready then greatest(0,s.arrivals-coalesce(a.seen,0)) end,
    s.arrivals,s.revision
  from public.counter_v1_state s left join public.counter_v1_ack a
    on a.uid=s.uid and a.kind=s.kind and a.device=p_device where s.uid=p_uid
$$;
revoke all on function public.counter_v1_apply(text,text,text,numeric,bigint,boolean),
  public.counter_v1_seed(text,text,numeric,bigint),public.counter_v1_acknowledge(text,text,text,bigint),
  public.counter_v1_snapshot(text,text) from public,anon,authenticated;
grant execute on function public.counter_v1_apply(text,text,text,numeric,bigint,boolean),
  public.counter_v1_seed(text,text,numeric,bigint),public.counter_v1_acknowledge(text,text,text,bigint),
  public.counter_v1_snapshot(text,text) to service_role;
