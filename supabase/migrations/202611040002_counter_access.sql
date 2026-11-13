-- New private gateway state only. Does not enable counter production or touch source data.
create table public.counter_v1_access_window (
  uid text primary key,
  opened_at timestamptz not null,
  hits integer not null check(hits between 1 and 31)
);
alter table public.counter_v1_access_window enable row level security;
revoke all on public.counter_v1_access_window from public,anon,authenticated;
-- p_uid MUST come from verified Firebase session in the server-only HTTP adapter.
create function public.counter_v1_access(p_uid text,p_device text,p_action text,p_kind text default null,p_observed bigint default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare now_at timestamptz:=clock_timestamp(); attempts integer; counters jsonb; all_ready boolean;
begin
  if p_action is null or p_action not in ('snapshot','ack') or p_device is null or p_device='' then
    return jsonb_build_object('error','INVALID_REQUEST');
  end if;
  -- Bind to existing verified device registration; no arbitrary per-request ack rows.
  perform 1 from public.fcm_device_tokens where user_id=p_uid and device_id=p_device for share;
  if not found then return jsonb_build_object('error','DEVICE_NOT_REGISTERED'); end if;
  if p_action='ack' and (p_kind is null or p_kind not in ('notifications','requests','messages') or p_observed is null or p_observed<0) then
    return jsonb_build_object('error','INVALID_REQUEST');
  end if;
  insert into public.counter_v1_access_window values(p_uid,now_at,1)
    on conflict(uid) do update set
      opened_at=case when public.counter_v1_access_window.opened_at<=now_at-interval '1 minute' then now_at else public.counter_v1_access_window.opened_at end,
      hits=case when public.counter_v1_access_window.opened_at<=now_at-interval '1 minute' then 1 else least(31,public.counter_v1_access_window.hits+1) end
    returning hits into attempts;
  if attempts>30 then return jsonb_build_object('error','RATE_LIMITED'); end if;
  if p_action='ack' then
    if not exists(select 1 from public.counter_v1_state where uid=p_uid and kind=p_kind and ready) then
      return jsonb_build_object('error','COUNTER_NOT_READY');
    end if;
    perform public.counter_v1_acknowledge(p_uid,p_kind,p_device,p_observed);
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('kind',c.kind,'ready',c.ready,
      'unread_count',c.unread_count::text,'badge_count',c.badge_count::text,
      'arrival_total',c.arrival_total::text,'revision',c.revision::text) order by c.kind),'[]'::jsonb),
    count(*)=3 and coalesce(bool_and(c.ready),false) into counters,all_ready
    from public.counter_v1_snapshot(p_uid,p_device) c;
  return jsonb_build_object('ready',all_ready,'counters',counters);
end $$;
revoke all on function public.counter_v1_access(text,text,text,text,bigint) from public,anon,authenticated;
grant execute on function public.counter_v1_access(text,text,text,text,bigint) to service_role;
