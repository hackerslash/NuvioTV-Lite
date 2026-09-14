-- Bootstrap RPCs required by Nuvio TV QR auth flows.
-- Run in Supabase SQL editor on your project.

create extension if not exists pgcrypto;

create table if not exists public.tv_login_sessions (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  device_nonce text not null,
  device_name text,
  device_type text,
  redirect_base_url text not null,
  web_url text not null,
  status text not null default 'pending',
  requested_by_user_id uuid,
  approved_by_user_id uuid,
  expires_at timestamptz not null,
  poll_interval_seconds integer not null default 3,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_tv_login_sessions_nonce on public.tv_login_sessions(device_nonce);
create index if not exists idx_tv_login_sessions_status on public.tv_login_sessions(status);
create index if not exists idx_tv_login_sessions_expires on public.tv_login_sessions(expires_at);

create or replace function public.touch_tv_login_session_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists trg_tv_login_sessions_updated_at on public.tv_login_sessions;
create trigger trg_tv_login_sessions_updated_at
before update on public.tv_login_sessions
for each row execute function public.touch_tv_login_session_updated_at();

alter table public.tv_login_sessions enable row level security;

drop policy if exists tv_login_sessions_no_direct_read on public.tv_login_sessions;
create policy tv_login_sessions_no_direct_read
on public.tv_login_sessions
for select
to anon, authenticated
using (false);

drop policy if exists tv_login_sessions_no_direct_write on public.tv_login_sessions;
create policy tv_login_sessions_no_direct_write
on public.tv_login_sessions
for all
to anon, authenticated
using (false)
with check (false);

create or replace function public.generate_tv_login_code()
returns text
language plpgsql
as $$
declare
  chars text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  out_code text := '';
  i integer;
begin
  for i in 1..6 loop
    out_code := out_code || substr(chars, 1 + floor(random() * length(chars))::int, 1);
  end loop;
  return out_code;
end;
$$;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table (
  code text,
  web_url text,
  expires_at text,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code text;
  v_redirect text;
  v_web_url text;
  v_expires_at timestamptz;
  v_poll integer := 3;
begin
  if coalesce(trim(p_device_nonce), '') = '' then
    raise exception 'p_device_nonce is required';
  end if;
  if coalesce(trim(p_redirect_base_url), '') = '' then
    raise exception 'p_redirect_base_url is required';
  end if;

  v_redirect := trim(p_redirect_base_url);
  v_code := public.generate_tv_login_code();
  v_expires_at := now() + interval '10 minutes';
  v_web_url := v_redirect || case when strpos(v_redirect, '?') > 0 then '&' else '?' end || 'code=' || v_code;

  insert into public.tv_login_sessions (
    code,
    device_nonce,
    device_name,
    device_type,
    redirect_base_url,
    web_url,
    status,
    expires_at,
    poll_interval_seconds
  ) values (
    v_code,
    p_device_nonce,
    nullif(trim(p_device_name), ''),
    'tv',
    v_redirect,
    v_web_url,
    'pending',
    v_expires_at,
    v_poll
  );

  return query
  select
    v_code,
    v_web_url,
    to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    v_poll;
end;
$$;

create or replace function public.start_device_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_type text default 'tv',
  p_device_name text default null
)
returns table (
  device_code text,
  user_code text,
  verification_uri text,
  verification_uri_complete text,
  expires_at text,
  poll_interval_seconds integer,
  legacy boolean
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code text;
  v_redirect text;
  v_web_url text;
  v_expires_at timestamptz;
  v_poll integer := 3;
begin
  if coalesce(trim(p_device_nonce), '') = '' then
    raise exception 'p_device_nonce is required';
  end if;
  if coalesce(trim(p_redirect_base_url), '') = '' then
    raise exception 'p_redirect_base_url is required';
  end if;

  v_redirect := trim(p_redirect_base_url);
  v_code := public.generate_tv_login_code();
  v_expires_at := now() + interval '10 minutes';
  v_web_url := v_redirect ||
    case when strpos(v_redirect, '?') > 0 then '&' else '?' end ||
    'code=' || v_code || '&user_code=' || v_code || '&device_code=' || v_code;

  insert into public.tv_login_sessions (
    code,
    device_nonce,
    device_name,
    device_type,
    redirect_base_url,
    web_url,
    status,
    expires_at,
    poll_interval_seconds
  ) values (
    v_code,
    p_device_nonce,
    nullif(trim(p_device_name), ''),
    coalesce(nullif(trim(p_device_type), ''), 'tv'),
    v_redirect,
    v_web_url,
    'pending',
    v_expires_at,
    v_poll
  );

  return query
  select
    v_code,
    v_code,
    v_redirect,
    v_web_url,
    to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    v_poll,
    false;
end;
$$;

create or replace function public.poll_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table (
  status text,
  expires_at text,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row public.tv_login_sessions%rowtype;
  v_status text;
begin
  select *
  into v_row
  from public.tv_login_sessions
  where code = p_code
    and device_nonce = p_device_nonce
  order by created_at desc
  limit 1;

  if not found then
    raise exception 'session_not_found';
  end if;

  if now() > v_row.expires_at then
    if v_row.status = 'pending' then
      update public.tv_login_sessions
      set status = 'expired'
      where id = v_row.id;
    end if;
    v_status := 'expired';
  else
    v_status := v_row.status;
  end if;

  return query
  select
    v_status,
    to_char(v_row.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    v_row.poll_interval_seconds;
end;
$$;

-- Helper used by the /link web page.
-- Requires an authenticated Supabase user; stores auth.uid() as approver.
create or replace function public.approve_tv_login_session(
  p_code text
)
returns table (
  status text,
  code text,
  approved_by_user_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid;
begin
  v_uid := auth.uid();
  if v_uid is null then
    raise exception 'not_authenticated';
  end if;

  update public.tv_login_sessions as tls
  set
    status = 'approved',
    approved_by_user_id = v_uid
  where tls.code = p_code
    and tls.status = 'pending'
    and now() <= tls.expires_at;

  if not found then
    raise exception 'no_pending_session_for_code';
  end if;

  return query
  select 'approved'::text, p_code, v_uid;
end;
$$;

grant execute on function public.start_tv_login_session(text, text, text) to anon, authenticated;
grant execute on function public.start_device_login_session(text, text, text, text) to anon, authenticated;
grant execute on function public.poll_tv_login_session(text, text) to anon, authenticated;
grant execute on function public.approve_tv_login_session(text) to authenticated;

-- Optional, useful if PostgREST schema cache lags for a minute.
notify pgrst, 'reload schema';
