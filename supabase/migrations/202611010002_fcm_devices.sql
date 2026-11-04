-- FCM tokens are private server-side data. No public/client database access.
create table if not exists public.fcm_device_tokens (
  device_id text primary key,
  user_id text not null,
  token text not null check (length(token) between 20 and 4096),
  updated_at timestamptz not null default now()
);
create index if not exists fcm_devices_user on public.fcm_device_tokens(user_id);
alter table public.fcm_device_tokens enable row level security;
revoke all on public.fcm_device_tokens from anon, authenticated;
grant all on public.fcm_device_tokens to service_role;
