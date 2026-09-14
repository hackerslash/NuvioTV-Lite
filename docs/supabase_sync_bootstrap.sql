-- Supabase SELF-HOST sync-engine bootstrap for NuvioTV-Lite-TG.
--
-- Companion to docs/supabase_qr_rpc_bootstrap.sql (TV QR login). That file
-- covers sign-in only; THIS file covers everything the app syncs afterwards:
-- profiles + PIN locks, addons/plugins, settings blob, provider credentials,
-- collections, home catalog, library, watch progress, watched items,
-- sync codes / linked devices / device registration.
--
-- HOW TO APPLY: Supabase dashboard -> SQL editor -> New query -> paste this
-- whole file -> Run. Safe to re-run (CREATE TABLE IF NOT EXISTS +
-- CREATE OR REPLACE everywhere). Apply AFTER the QR bootstrap (independent,
-- but the QR one must exist for sign-in to work at all).
--
-- CONTRACT SOURCE: derived from the app call sites (all JWT RPCs go through
-- postgrest.rpc with the user session; QR RPCs use the anon key). Field
-- names below match data/remote/supabase/SupabaseModels.kt EXACTLY
-- (kotlinx decoders fail on missing non-optional fields; extra columns are
-- never selected).
--
-- DESIGN ASSUMPTIONS (documented because there is no upstream reference):
--  1. Owner identity = auth.uid(). get_sync_owner() returns the linked
--     household owner when the user claimed a sync code, else auth.uid().
--  2. All profile-scoped data is keyed (owner_id, profile_id). Direct table
--     reads issued by the app (addons, plugins, linked_devices) get an
--     OWNER-READ RLS policy; everything else is RPC-only (deny by default).
--  3. Push RPCs upsert; sync_push_addons REPLACES the profile set (matches
--     the client reconcile); sync_delete_profile_data wipes all scoped data.
--  4. Delta surfaces (watch progress, watched items, library) use event log
--     tables fed by the push/delete RPCs (no triggers): cursor = max event,
--     delta = events since id, snapshot = base table.
--  5. Membership/avatar catalog RPCs are stubbed empty (self-host has no
--     premium tiers); the app tolerates empty catalogs (color avatars).
--  6. p_origin_client_id is accepted and ignored (loop prevention is
--     client-side via signatures).
--  7. addons/plugins keep the pre-existing upstream shape (id + user_id);
--     pre-existing policies and push functions are preserved, unknown shapes
--     get an owner read policy. New tables use (owner_id, profile_id) keys.
--  7. PINs are stored with pgcrypto crypt(); no brute-force lockout
--     (verify returns retry_after_seconds = 0).

create extension if not exists pgcrypto;

-- ===========================================================================
-- 0. Helpers (defined after §1 tables: SQL functions validate relations
--    at creation time, so sync_effective_owner() lives below)
-- ===========================================================================

-- ===========================================================================
-- 1. Account links + sync codes + linked devices
-- ===========================================================================

create table if not exists public.account_links (
  user_id uuid primary key,
  owner_id uuid not null,
  created_at timestamptz not null default now()
);

create table if not exists public.sync_codes (
  code text primary key,
  owner_id uuid not null,
  pin_hash text not null,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.linked_devices (
  device_user_id text primary key,
  owner_id uuid not null,
  device_name text,
  linked_at timestamptz not null default now()
);

alter table public.account_links enable row level security;
alter table public.sync_codes enable row level security;
alter table public.linked_devices enable row level security;

drop policy if exists linked_devices_owner_read on public.linked_devices;
create policy linked_devices_owner_read on public.linked_devices
  for select to authenticated using (owner_id = auth.uid());

create or replace function public.sync_effective_owner()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    (select owner_id from public.account_links where user_id = auth.uid()),
    auth.uid()
  );
$$;

-- 6-char codes, same alphabet as the TV login flow.
create or replace function public.generate_sync_code_value()
returns text
language plpgsql
as $$
declare
  v_alphabet text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  v_code text := '';
  v_i int;
begin
  for v_i in 1..6 loop
    v_code := v_code || substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::int, 1);
  end loop;
  return v_code;
end;
$$;

create or replace function public.generate_sync_code(p_pin text)
returns table(code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_code text;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  if coalesce(trim(p_pin), '') = '' then raise exception 'PIN is required'; end if;
  delete from public.sync_codes where owner_id = v_owner;
  loop
    v_code := public.generate_sync_code_value();
    begin
      insert into public.sync_codes(code, owner_id, pin_hash, expires_at)
      values (v_code, v_owner, crypt(p_pin, gen_salt('bf')), now() + interval '10 minutes');
      exit;
    exception when unique_violation then
      -- retry with a fresh code
    end;
  end loop;
  return query select v_code;
end;
$$;
grant execute on function public.generate_sync_code(text) to anon, authenticated;

create or replace function public.get_sync_code(p_pin text)
returns table(code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_row public.sync_codes%rowtype;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  if coalesce(trim(p_pin), '') = '' then raise exception 'PIN is required'; end if;
  select * into v_row from public.sync_codes
  where owner_id = v_owner and used_at is null and expires_at > now()
  order by created_at desc limit 1;
  if not found then raise exception 'No sync code found'; end if;
  if v_row.pin_hash <> crypt(p_pin, v_row.pin_hash) then raise exception 'incorrect pin'; end if;
  return query select v_row.code;
end;
$$;
grant execute on function public.get_sync_code(text) to anon, authenticated;

create or replace function public.claim_sync_code(p_code text, p_pin text, p_device_name text default null)
returns table(result_owner_id uuid, success boolean, message text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_me uuid := auth.uid();
  v_row public.sync_codes%rowtype;
  v_code text := upper(trim(coalesce(p_code, '')));
begin
  if v_me is null then
    return query select null::uuid, false, 'Not authenticated';
    return;
  end if;
  select * into v_row from public.sync_codes
  where code = v_code and used_at is null and expires_at > now();
  if not found then
    return query select null::uuid, false, 'Invalid or expired code';
    return;
  end if;
  if v_row.pin_hash <> crypt(p_pin, v_row.pin_hash) then
    return query select null::uuid, false, 'Incorrect PIN';
    return;
  end if;
  update public.sync_codes set used_at = now() where code = v_code;
  if v_me <> v_row.owner_id then
    insert into public.account_links(user_id, owner_id)
    values (v_me, v_row.owner_id)
    on conflict (user_id) do update set owner_id = excluded.owner_id;
  end if;
  return query select v_row.owner_id, true, 'Device linked';
end;
$$;
grant execute on function public.claim_sync_code(text, text, text) to anon, authenticated;

create or replace function public.get_sync_owner()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select public.sync_effective_owner()::text;
$$;
grant execute on function public.get_sync_owner() to authenticated;

create or replace function public.register_current_device(
  p_installation_id text,
  p_client_name text,
  p_client_version text,
  p_platform text,
  p_device_name text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  if coalesce(trim(p_installation_id), '') = '' then raise exception 'installation id is required'; end if;
  insert into public.linked_devices(device_user_id, owner_id, device_name, linked_at)
  values (p_installation_id, v_owner, nullif(trim(coalesce(p_device_name, '')), ''), now())
  on conflict (device_user_id) do update set
    owner_id = excluded.owner_id,
    device_name = excluded.device_name,
    linked_at = now();
end;
$$;
grant execute on function public.register_current_device(text, text, text, text, text) to authenticated;

create or replace function public.unlink_device(p_device_user_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  delete from public.linked_devices
  where device_user_id = p_device_user_id and owner_id = v_owner;
end;
$$;
grant execute on function public.unlink_device(text) to authenticated;

-- ===========================================================================
-- 2. Profiles + PIN locks
-- ===========================================================================

create table if not exists public.profiles (
  owner_id uuid not null,
  profile_index int not null,
  name text not null default '',
  avatar_color_hex text not null default '#1E88E5',
  uses_primary_addons boolean not null default false,
  uses_primary_plugins boolean not null default false,
  avatar_id text,
  avatar_url text,
  profile_background_id text,
  profile_background_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_index)
);

create table if not exists public.profile_pins (
  owner_id uuid not null,
  profile_index int not null,
  pin_hash text not null,
  locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_index)
);

alter table public.profiles enable row level security;
alter table public.profile_pins enable row level security;

create or replace function public.sync_push_profiles(
  p_client_max_profiles int,
  p_profiles jsonb,
  p_origin_client_id text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb)) loop
    insert into public.profiles(
      owner_id, profile_index, name, avatar_color_hex,
      uses_primary_addons, uses_primary_plugins,
      avatar_id, avatar_url, profile_background_id, profile_background_url, updated_at
    ) values (
      v_owner,
      (v_item->>'profile_index')::int,
      coalesce(v_item->>'name', ''),
      coalesce(v_item->>'avatar_color_hex', '#1E88E5'),
      coalesce((v_item->>'uses_primary_addons')::boolean, false),
      coalesce((v_item->>'uses_primary_plugins')::boolean, false),
      v_item->>'avatar_id',
      v_item->>'avatar_url',
      v_item->>'profile_background_id',
      v_item->>'profile_background_url',
      now()
    )
    on conflict (owner_id, profile_index) do update set
      name = excluded.name,
      avatar_color_hex = excluded.avatar_color_hex,
      uses_primary_addons = excluded.uses_primary_addons,
      uses_primary_plugins = excluded.uses_primary_plugins,
      avatar_id = excluded.avatar_id,
      avatar_url = excluded.avatar_url,
      profile_background_id = excluded.profile_background_id,
      profile_background_url = excluded.profile_background_url,
      updated_at = now();
  end loop;
end;
$$;
grant execute on function public.sync_push_profiles(int, jsonb, text) to authenticated;

create or replace function public.sync_pull_profiles()
returns table(
  id text, user_id text, profile_index int, name text, avatar_color_hex text,
  uses_primary_addons boolean, uses_primary_plugins boolean,
  avatar_id text, avatar_url text, profile_background_id text, profile_background_url text,
  created_at timestamptz, updated_at timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
  select null::text, owner_id::text, profile_index, name, avatar_color_hex,
    uses_primary_addons, uses_primary_plugins,
    avatar_id, avatar_url, profile_background_id, profile_background_url,
    created_at, updated_at
  from public.profiles
  where owner_id = public.sync_effective_owner()
  order by profile_index;
$$;
grant execute on function public.sync_pull_profiles() to authenticated;

create or replace function public.sync_delete_profile_data(p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  delete from public.profile_pins where owner_id = v_owner and profile_index = p_profile_id;
  delete from public.profile_settings where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.provider_credentials where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.collections where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.home_catalog_settings where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.addons where user_id = v_owner and profile_id = p_profile_id;
  delete from public.plugins where user_id = v_owner and profile_id = p_profile_id;
  delete from public.watch_progress where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.watch_progress_events where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.watched_items where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.watched_item_events where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.library_items where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.library_events where owner_id = v_owner and profile_id = p_profile_id;
  delete from public.profiles where owner_id = v_owner and profile_index = p_profile_id;
end;
$$;
grant execute on function public.sync_delete_profile_data(int, text) to authenticated;

create or replace function public.sync_pull_profile_locks()
returns table(profile_index int, pin_enabled boolean, pin_locked_until timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select p.profile_index, (pp.pin_hash is not null), pp.locked_until
  from public.profiles p
  left join public.profile_pins pp
    on pp.owner_id = p.owner_id and pp.profile_index = p.profile_index
  where p.owner_id = public.sync_effective_owner()
  order by p.profile_index;
$$;
grant execute on function public.sync_pull_profile_locks() to authenticated;

create or replace function public.set_profile_pin(p_profile_id int, p_pin text, p_current_pin text default null)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_hash text;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  if coalesce(trim(p_pin), '') = '' then raise exception 'PIN is required'; end if;
  select pin_hash into v_hash from public.profile_pins
  where owner_id = v_owner and profile_index = p_profile_id;
  if v_hash is not null then
    if coalesce(trim(coalesce(p_current_pin, '')), '') = '' then
      raise exception 'Current PIN is required';
    end if;
    if v_hash <> crypt(p_current_pin, v_hash) then raise exception 'incorrect pin'; end if;
  end if;
  insert into public.profile_pins(owner_id, profile_index, pin_hash, locked_until, updated_at)
  values (v_owner, p_profile_id, crypt(p_pin, gen_salt('bf')), null, now())
  on conflict (owner_id, profile_index) do update set
    pin_hash = excluded.pin_hash, locked_until = null, updated_at = now();
end;
$$;
grant execute on function public.set_profile_pin(int, text, text) to authenticated;

create or replace function public.clear_profile_pin(p_profile_id int, p_current_pin text default null)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_hash text;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  select pin_hash into v_hash from public.profile_pins
  where owner_id = v_owner and profile_index = p_profile_id;
  if v_hash is not null then
    if coalesce(trim(coalesce(p_current_pin, '')), '') = '' then
      raise exception 'Current PIN is required';
    end if;
    if v_hash <> crypt(p_current_pin, v_hash) then raise exception 'incorrect pin'; end if;
  end if;
  delete from public.profile_pins where owner_id = v_owner and profile_index = p_profile_id;
end;
$$;
grant execute on function public.clear_profile_pin(int, text) to authenticated;

create or replace function public.verify_profile_pin(p_profile_id int, p_pin text)
returns table(unlocked boolean, retry_after_seconds int)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_hash text;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  select pin_hash into v_hash from public.profile_pins
  where owner_id = v_owner and profile_index = p_profile_id;
  if v_hash is null then
    return query select true, 0;
    return;
  end if;
  if v_hash = crypt(p_pin, v_hash) then
    return query select true, 0;
  else
    return query select false, 0;
  end if;
end;
$$;
grant execute on function public.verify_profile_pin(int, text) to authenticated;

-- ===========================================================================
-- 3. Addons / plugins (push RPC + direct owner reads)
-- ===========================================================================

-- NOTE: addons/plugins keep the pre-existing upstream shape (id + user_id),
-- which the app's direct reads (eq user_id) and DTOs expect.
create table if not exists public.addons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  profile_id int not null default 1,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists uq_addons_user_profile_url
  on public.addons(user_id, profile_id, url);

create table if not exists public.plugins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  profile_id int not null default 1,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order int not null default 0,
  repo_type text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists uq_plugins_user_profile_url
  on public.plugins(user_id, profile_id, url);

alter table public.addons enable row level security;
alter table public.plugins enable row level security;

-- Owner-read policies, adapted to whichever shape the tables have: pre-existing
-- projects keep their own policies; fresh projects get a user_id read policy.
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'addons' and column_name = 'owner_id')
     and not exists (select 1 from pg_policies
                     where schemaname = 'public' and tablename = 'addons'
                     and policyname = 'addons_owner_read') then
    create policy addons_owner_read on public.addons
      for select to authenticated using (owner_id = auth.uid());
  end if;
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'addons' and column_name = 'user_id')
     and not exists (select 1 from pg_policies
                     where schemaname = 'public' and tablename = 'addons'
                     and policyname in ('addons_owner_rw', 'addons_owner_read')) then
    create policy addons_owner_read on public.addons
      for select to authenticated using (user_id = auth.uid());
  end if;
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'plugins' and column_name = 'owner_id')
     and not exists (select 1 from pg_policies
                     where schemaname = 'public' and tablename = 'plugins'
                     and policyname = 'plugins_owner_read') then
    create policy plugins_owner_read on public.plugins
      for select to authenticated using (owner_id = auth.uid());
  end if;
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'plugins' and column_name = 'user_id')
     and not exists (select 1 from pg_policies
                     where schemaname = 'public' and tablename = 'plugins'
                     and policyname in ('plugins_owner_rw', 'plugins_owner_read')) then
    create policy plugins_owner_read on public.plugins
      for select to authenticated using (user_id = auth.uid());
  end if;
end
$$;

create or replace function public.sync_push_addons(p_addons jsonb, p_profile_id int default 1, p_origin_client_id text default null)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
  v_seen text[] := '{}';
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) loop
    v_seen := v_seen || (v_item->>'url');
    insert into public.addons(user_id, profile_id, url, name, enabled, sort_order, updated_at)
    values (
      v_owner, p_profile_id, v_item->>'url', v_item->>'name',
      coalesce((v_item->>'enabled')::boolean, true),
      coalesce((v_item->>'sort_order')::int, 0), now()
    )
    on conflict (user_id, profile_id, url) do update set
      name = excluded.name, enabled = excluded.enabled,
      sort_order = excluded.sort_order, updated_at = now();
  end loop;
  delete from public.addons
  where user_id = v_owner and profile_id = p_profile_id and url <> all (v_seen);
end;
$$;
grant execute on function public.sync_push_addons(jsonb, int, text) to authenticated;

create or replace function public.sync_push_plugins(p_plugins jsonb, p_profile_id int default 1, p_origin_client_id text default null)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
  v_seen text[] := '{}';
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_plugins, '[]'::jsonb)) loop
    v_seen := v_seen || (v_item->>'url');
    insert into public.plugins(user_id, profile_id, url, name, enabled, sort_order, repo_type, updated_at)
    values (
      v_owner, p_profile_id, v_item->>'url', v_item->>'name',
      coalesce((v_item->>'enabled')::boolean, true),
      coalesce((v_item->>'sort_order')::int, 0),
      v_item->>'repo_type', now()
    )
    on conflict (user_id, profile_id, url) do update set
      name = excluded.name, enabled = excluded.enabled,
      sort_order = excluded.sort_order, repo_type = excluded.repo_type, updated_at = now();
  end loop;
  delete from public.plugins
  where user_id = v_owner and profile_id = p_profile_id and url <> all (v_seen);
end;
$$;
grant execute on function public.sync_push_plugins(jsonb, int, text) to authenticated;

-- ===========================================================================
-- 4. Settings blob / provider credentials / collections / home catalog
-- ===========================================================================

create table if not exists public.profile_settings (
  owner_id uuid not null,
  profile_id int not null,
  settings_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id)
);

create table if not exists public.provider_credentials (
  owner_id uuid not null,
  profile_id int not null,
  provider text not null,
  credential_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id, provider)
);

create table if not exists public.collections (
  owner_id uuid not null,
  profile_id int not null,
  collections_json json not null default '[]',
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id)
);

create table if not exists public.home_catalog_settings (
  owner_id uuid not null,
  profile_id int not null,
  settings_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id)
);

alter table public.profile_settings enable row level security;
alter table public.provider_credentials enable row level security;
alter table public.collections enable row level security;
alter table public.home_catalog_settings enable row level security;

create or replace function public.sync_push_profile_settings_blob(
  p_profile_id int, p_settings_json jsonb, p_platform text, p_origin_client_id text
)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.profile_settings(owner_id, profile_id, settings_json, updated_at)
  values (public.sync_effective_owner(), p_profile_id, coalesce(p_settings_json, '{}'::jsonb), now())
  on conflict (owner_id, profile_id) do update set
    settings_json = excluded.settings_json, updated_at = now();
$$;
grant execute on function public.sync_push_profile_settings_blob(int, jsonb, text, text) to authenticated;

create or replace function public.sync_pull_profile_settings_blob(p_profile_id int, p_platform text)
returns table(profile_id int, settings_json jsonb, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select profile_id, settings_json, updated_at from public.profile_settings
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_pull_profile_settings_blob(int, text) to authenticated;

create or replace function public.sync_push_provider_credentials(p_profile_id int, p_credentials jsonb, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_credentials, '[]'::jsonb)) loop
    insert into public.provider_credentials(owner_id, profile_id, provider, credential_json, updated_at)
    values (v_owner, p_profile_id, v_item->>'provider', coalesce(v_item->'credential_json', '{}'::jsonb), now())
    on conflict (owner_id, profile_id, provider) do update set
      credential_json = excluded.credential_json, updated_at = now();
  end loop;
end;
$$;
grant execute on function public.sync_push_provider_credentials(int, jsonb, text) to authenticated;

create or replace function public.sync_seed_provider_credentials(p_profile_id int, p_credentials jsonb, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_credentials, '[]'::jsonb)) loop
    insert into public.provider_credentials(owner_id, profile_id, provider, credential_json, updated_at)
    values (v_owner, p_profile_id, v_item->>'provider', coalesce(v_item->'credential_json', '{}'::jsonb), now())
    on conflict (owner_id, profile_id, provider) do nothing;
  end loop;
end;
$$;
grant execute on function public.sync_seed_provider_credentials(int, jsonb, text) to authenticated;

create or replace function public.sync_pull_provider_credentials(p_profile_id int)
returns table(provider text, credential_json jsonb, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select provider, credential_json, updated_at from public.provider_credentials
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id
  order by provider;
$$;
grant execute on function public.sync_pull_provider_credentials(int) to authenticated;

create or replace function public.sync_copy_profile_setup(
  p_source_profile_id int, p_target_profile_id int,
  p_copy_tv boolean, p_copy_mobile boolean, p_copy_desktop boolean,
  p_copy_provider_credentials boolean, p_replace_provider_credentials boolean,
  p_origin_client_id text
)
returns table(
  source_profile_id int, target_profile_id int,
  tv_status text, provider_credentials_status text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_source jsonb;
  v_target jsonb;
  v_tv_status text := 'unchanged';
  v_pc_status text := 'unchanged';
  v_source_count int;
  v_target_count int;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  select settings_json into v_source from public.profile_settings
  where owner_id = v_owner and profile_id = p_source_profile_id;
  select settings_json into v_target from public.profile_settings
  where owner_id = v_owner and profile_id = p_target_profile_id;
  if v_source is not null and v_source is distinct from v_target then
    insert into public.profile_settings(owner_id, profile_id, settings_json, updated_at)
    values (v_owner, p_target_profile_id, v_source, now())
    on conflict (owner_id, profile_id) do update set
      settings_json = excluded.settings_json, updated_at = now();
    v_tv_status := 'copied';
  end if;
  select count(*) into v_source_count from public.provider_credentials
  where owner_id = v_owner and profile_id = p_source_profile_id;
  select count(*) into v_target_count from public.provider_credentials
  where owner_id = v_owner and profile_id = p_target_profile_id;
  if not p_copy_provider_credentials then
    v_pc_status := 'kept_existing';
  elsif v_source_count = 0 then
    v_pc_status := 'source_missing';
  elsif v_target_count > 0 and not p_replace_provider_credentials then
    v_pc_status := 'kept_existing';
  else
    delete from public.provider_credentials
    where owner_id = v_owner and profile_id = p_target_profile_id;
    insert into public.provider_credentials(owner_id, profile_id, provider, credential_json, updated_at)
    select v_owner, p_target_profile_id, provider, credential_json, now()
    from public.provider_credentials
    where owner_id = v_owner and profile_id = p_source_profile_id;
    v_pc_status := 'copied';
  end if;
  return query select p_source_profile_id, p_target_profile_id, v_tv_status, v_pc_status;
end;
$$;
grant execute on function public.sync_copy_profile_setup(int, int, boolean, boolean, boolean, boolean, boolean, text) to authenticated;

create or replace function public.sync_push_collections(p_profile_id int, p_collections_json json, p_origin_client_id text)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.collections(owner_id, profile_id, collections_json, updated_at)
  values (public.sync_effective_owner(), p_profile_id, coalesce(p_collections_json, '[]'), now())
  on conflict (owner_id, profile_id) do update set
    collections_json = excluded.collections_json, updated_at = now();
$$;
grant execute on function public.sync_push_collections(int, json, text) to authenticated;

create or replace function public.sync_pull_collections(p_profile_id int)
returns table(profile_id int, collections_json json, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select profile_id, collections_json, updated_at from public.collections
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_pull_collections(int) to authenticated;

create or replace function public.sync_push_home_catalog_settings(
  p_profile_id int, p_settings_json jsonb, p_platform text, p_origin_client_id text
)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.home_catalog_settings(owner_id, profile_id, settings_json, updated_at)
  values (public.sync_effective_owner(), p_profile_id, coalesce(p_settings_json, '{}'::jsonb), now())
  on conflict (owner_id, profile_id) do update set
    settings_json = excluded.settings_json, updated_at = now();
$$;
grant execute on function public.sync_push_home_catalog_settings(int, jsonb, text, text) to authenticated;

create or replace function public.sync_pull_home_catalog_settings(p_profile_id int, p_platform text)
returns table(profile_id int, settings_json jsonb, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select profile_id, settings_json, updated_at from public.home_catalog_settings
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_pull_home_catalog_settings(int, text) to authenticated;

-- ===========================================================================
-- 5. Watch progress (base + events)
-- ===========================================================================

create table if not exists public.watch_progress (
  owner_id uuid not null,
  profile_id int not null,
  progress_key text not null,
  content_id text not null,
  content_type text not null,
  video_id text not null,
  season int,
  episode int,
  "position" bigint not null,
  duration bigint not null,
  last_watched bigint not null,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id, progress_key)
);

create table if not exists public.watch_progress_events (
  event_id bigserial primary key,
  owner_id uuid not null,
  profile_id int not null,
  operation text not null,
  progress_key text not null,
  content_id text not null,
  content_type text not null,
  video_id text not null default '',
  season int,
  episode int,
  "position" bigint not null default 0,
  duration bigint not null default 0,
  last_watched bigint not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists idx_watch_progress_events_owner_profile_id
  on public.watch_progress_events(owner_id, profile_id, event_id);

alter table public.watch_progress enable row level security;
alter table public.watch_progress_events enable row level security;

create or replace function public.sync_push_watch_progress(p_entries jsonb, p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_entries, '[]'::jsonb)) loop
    insert into public.watch_progress(
      owner_id, profile_id, progress_key, content_id, content_type, video_id,
  season, episode, "position", duration, last_watched, updated_at
    ) values (
      v_owner, p_profile_id,
      v_item->>'progress_key', v_item->>'content_id', v_item->>'content_type',
      coalesce(v_item->>'video_id', ''),
      nullif(v_item->>'season', '')::int, nullif(v_item->>'episode', '')::int,
      coalesce((v_item->>'position')::bigint, 0), coalesce((v_item->>'duration')::bigint, 0),
      coalesce((v_item->>'last_watched')::bigint, 0), now()
    )
    on conflict (owner_id, profile_id, progress_key) do update set
      content_id = excluded.content_id, content_type = excluded.content_type,
      video_id = excluded.video_id, season = excluded.season, episode = excluded.episode,
      "position" = excluded."position", duration = excluded.duration,
      last_watched = excluded.last_watched, updated_at = now();
    insert into public.watch_progress_events(
      owner_id, profile_id, operation, progress_key, content_id, content_type, video_id,
  season, episode, "position", duration, last_watched
    ) values (
      v_owner, p_profile_id, 'upsert',
      v_item->>'progress_key', v_item->>'content_id', v_item->>'content_type',
      coalesce(v_item->>'video_id', ''),
      nullif(v_item->>'season', '')::int, nullif(v_item->>'episode', '')::int,
      coalesce((v_item->>'position')::bigint, 0), coalesce((v_item->>'duration')::bigint, 0),
      coalesce((v_item->>'last_watched')::bigint, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_push_watch_progress(jsonb, int, text) to authenticated;

create or replace function public.sync_delete_watch_progress(p_keys jsonb, p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_key text;
  v_row public.watch_progress%rowtype;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_key in select jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb)) loop
    select * into v_row from public.watch_progress
    where owner_id = v_owner and profile_id = p_profile_id and progress_key = v_key;
    delete from public.watch_progress
    where owner_id = v_owner and profile_id = p_profile_id and progress_key = v_key;
    insert into public.watch_progress_events(
      owner_id, profile_id, operation, progress_key, content_id, content_type, video_id,
  season, episode, "position", duration, last_watched
    ) values (
      v_owner, p_profile_id, 'delete', v_key,
      coalesce(v_row.content_id, ''), coalesce(v_row.content_type, ''),
      coalesce(v_row.video_id, ''), v_row.season, v_row.episode,
      coalesce(v_row."position", 0), coalesce(v_row.duration, 0), coalesce(v_row.last_watched, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_delete_watch_progress(jsonb, int, text) to authenticated;

create or replace function public.sync_get_watch_progress_delta_cursor(p_profile_id int)
returns bigint
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(max(event_id), 0) from public.watch_progress_events
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_get_watch_progress_delta_cursor(int) to authenticated;

create or replace function public.sync_pull_watch_progress_delta(p_profile_id int, p_since_event_id bigint, p_limit int)
returns table(
  event_id bigint, operation text, progress_key text,
  content_id text, content_type text, video_id text,
  season int, episode int, "position" bigint, duration bigint, last_watched bigint
)
language sql
stable
security definer
set search_path = public
as $$
  select event_id, operation, progress_key, content_id, content_type, video_id,
    season, episode, "position", duration, last_watched
  from public.watch_progress_events
  where owner_id = public.sync_effective_owner()
    and profile_id = p_profile_id and event_id > p_since_event_id
  order by event_id limit p_limit;
$$;
grant execute on function public.sync_pull_watch_progress_delta(int, bigint, int) to authenticated;

create or replace function public.sync_pull_watch_progress(p_profile_id int, p_since_last_watched bigint default null, p_limit int default null)
returns table(
  id text, user_id text, content_id text, content_type text, video_id text,
  season int, episode int, "position" bigint, duration bigint, last_watched bigint,
  progress_key text, profile_id int
)
language sql
stable
security definer
set search_path = public
as $$
  select null::text, owner_id::text, content_id, content_type, video_id,
    season, episode, "position", duration, last_watched, progress_key, profile_id
  from public.watch_progress
  where owner_id = public.sync_effective_owner()
    and profile_id = p_profile_id
    and (p_since_last_watched is null or last_watched > p_since_last_watched)
  order by last_watched desc limit p_limit;
$$;
grant execute on function public.sync_pull_watch_progress(int, bigint, int) to authenticated;

-- ===========================================================================
-- 6. Watched items (base + events)
-- ===========================================================================

create table if not exists public.watched_items (
  owner_id uuid not null,
  profile_id int not null,
  content_id text not null,
  content_type text not null default '',
  title text not null default '',
  season int,
  episode int,
  watched_at bigint not null,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id, content_id, content_type)
);

create table if not exists public.watched_item_events (
  event_id bigserial primary key,
  owner_id uuid not null,
  profile_id int not null,
  operation text not null,
  content_id text not null,
  content_type text not null default '',
  title text not null default '',
  season int,
  episode int,
  watched_at bigint not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists idx_watched_item_events_owner_profile_id
  on public.watched_item_events(owner_id, profile_id, event_id);

alter table public.watched_items enable row level security;
alter table public.watched_item_events enable row level security;

create or replace function public.sync_push_watched_items(p_items jsonb, p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
  v_content_type text;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
    v_content_type := coalesce(v_item->>'content_type', '');
    insert into public.watched_items(
      owner_id, profile_id, content_id, content_type, title, season, episode, watched_at, updated_at
    ) values (
      v_owner, p_profile_id, v_item->>'content_id', v_content_type,
      coalesce(v_item->>'title', ''),
      nullif(v_item->>'season', '')::int, nullif(v_item->>'episode', '')::int,
      coalesce((v_item->>'watched_at')::bigint, 0), now()
    )
    on conflict (owner_id, profile_id, content_id, content_type) do update set
      title = excluded.title, season = excluded.season, episode = excluded.episode,
      watched_at = excluded.watched_at, updated_at = now();
    insert into public.watched_item_events(
      owner_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at
    ) values (
      v_owner, p_profile_id, 'upsert', v_item->>'content_id', v_content_type,
      coalesce(v_item->>'title', ''),
      nullif(v_item->>'season', '')::int, nullif(v_item->>'episode', '')::int,
      coalesce((v_item->>'watched_at')::bigint, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_push_watched_items(jsonb, int, text) to authenticated;

create or replace function public.sync_pull_watched_items(p_profile_id int, p_page int, p_page_size int)
returns table(
  id text, user_id text, content_id text, content_type text, title text,
  season int, episode int, watched_at bigint, profile_id int
)
language sql
stable
security definer
set search_path = public
as $$
  select null::text, owner_id::text, content_id, content_type, title,
    season, episode, watched_at, profile_id
  from public.watched_items
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id
  order by watched_at desc limit p_page_size offset (p_page * p_page_size);
$$;
grant execute on function public.sync_pull_watched_items(int, int, int) to authenticated;

create or replace function public.sync_get_watched_items_delta_cursor(p_profile_id int)
returns bigint
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(max(event_id), 0) from public.watched_item_events
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_get_watched_items_delta_cursor(int) to authenticated;

create or replace function public.sync_pull_watched_items_delta(p_profile_id int, p_since_event_id bigint, p_limit int)
returns table(
  event_id bigint, operation text, content_id text, content_type text, title text,
  season int, episode int, watched_at bigint
)
language sql
stable
security definer
set search_path = public
as $$
  select event_id, operation, content_id, content_type, title, season, episode, watched_at
  from public.watched_item_events
  where owner_id = public.sync_effective_owner()
    and profile_id = p_profile_id and event_id > p_since_event_id
  order by event_id limit p_limit;
$$;
grant execute on function public.sync_pull_watched_items_delta(int, bigint, int) to authenticated;

create or replace function public.sync_delete_watched_items(p_profile_id int, p_keys jsonb, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
  v_row public.watched_items%rowtype;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb)) loop
    select * into v_row from public.watched_items
    where owner_id = v_owner and profile_id = p_profile_id
      and content_id = v_item->>'content_id'
      and (v_item->>'season' is null or season is not distinct from nullif(v_item->>'season', '')::int)
      and (v_item->>'episode' is null or episode is not distinct from nullif(v_item->>'episode', '')::int);
    delete from public.watched_items
    where owner_id = v_owner and profile_id = p_profile_id
      and content_id = v_item->>'content_id'
      and (v_item->>'season' is null or season is not distinct from nullif(v_item->>'season', '')::int)
      and (v_item->>'episode' is null or episode is not distinct from nullif(v_item->>'episode', '')::int);
    insert into public.watched_item_events(
      owner_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at
    ) values (
      v_owner, p_profile_id, 'delete', v_item->>'content_id',
      coalesce(v_row.content_type, ''), coalesce(v_row.title, ''),
      coalesce(nullif(v_item->>'season', '')::int, v_row.season),
      coalesce(nullif(v_item->>'episode', '')::int, v_row.episode),
      coalesce(v_row.watched_at, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_delete_watched_items(int, jsonb, text) to authenticated;

-- ===========================================================================
-- 7. Library (base + events)
-- ===========================================================================

create table if not exists public.library_items (
  owner_id uuid not null,
  profile_id int not null,
  content_id text not null,
  content_type text not null,
  name text not null default '',
  poster text,
  poster_shape text not null default '',
  background text,
  description text,
  release_info text,
  imdb_rating real,
  genres text[] not null default '{}',
  addon_base_url text,
  added_at bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (owner_id, profile_id, content_id, content_type)
);

create table if not exists public.library_events (
  event_id bigserial primary key,
  owner_id uuid not null,
  profile_id int not null,
  operation text not null,
  content_id text not null,
  content_type text not null,
  name text not null default '',
  poster text,
  poster_shape text not null default '',
  background text,
  description text,
  release_info text,
  imdb_rating real,
  genres text[] not null default '{}',
  addon_base_url text,
  added_at bigint not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists idx_library_events_owner_profile_id
  on public.library_events(owner_id, profile_id, event_id);

alter table public.library_items enable row level security;
alter table public.library_events enable row level security;

create or replace function public.sync_push_library_items(p_items jsonb, p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
    insert into public.library_items(
      owner_id, profile_id, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url,
      added_at, updated_at
    ) values (
      v_owner, p_profile_id, v_item->>'content_id', v_item->>'content_type',
      coalesce(v_item->>'name', ''), v_item->>'poster', coalesce(v_item->>'poster_shape', ''),
      v_item->>'background', v_item->>'description', v_item->>'release_info',
      nullif(v_item->>'imdb_rating', '')::real,
      coalesce((select array_agg(x) from jsonb_array_elements_text(coalesce(v_item->'genres', '[]'::jsonb)) x), '{}'),
      v_item->>'addon_base_url',
      coalesce((v_item->>'added_at')::bigint, 0), now()
    )
    on conflict (owner_id, profile_id, content_id, content_type) do update set
      name = excluded.name, poster = excluded.poster, poster_shape = excluded.poster_shape,
      background = excluded.background, description = excluded.description,
      release_info = excluded.release_info, imdb_rating = excluded.imdb_rating,
      genres = excluded.genres, addon_base_url = excluded.addon_base_url,
      added_at = excluded.added_at, updated_at = now();
    insert into public.library_events(
      owner_id, profile_id, operation, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at
    ) values (
      v_owner, p_profile_id, 'upsert', v_item->>'content_id', v_item->>'content_type',
      coalesce(v_item->>'name', ''), v_item->>'poster', coalesce(v_item->>'poster_shape', ''),
      v_item->>'background', v_item->>'description', v_item->>'release_info',
      nullif(v_item->>'imdb_rating', '')::real,
      coalesce((select array_agg(x) from jsonb_array_elements_text(coalesce(v_item->'genres', '[]'::jsonb)) x), '{}'),
      v_item->>'addon_base_url',
      coalesce((v_item->>'added_at')::bigint, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_push_library_items(jsonb, int, text) to authenticated;

create or replace function public.sync_delete_library_items(p_keys jsonb, p_profile_id int, p_origin_client_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_item jsonb;
  v_row public.library_items%rowtype;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for v_item in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb)) loop
    select * into v_row from public.library_items
    where owner_id = v_owner and profile_id = p_profile_id
      and content_id = v_item->>'content_id' and content_type = v_item->>'content_type';
    delete from public.library_items
    where owner_id = v_owner and profile_id = p_profile_id
      and content_id = v_item->>'content_id' and content_type = v_item->>'content_type';
    insert into public.library_events(
      owner_id, profile_id, operation, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at
    ) values (
      v_owner, p_profile_id, 'delete', v_item->>'content_id', v_item->>'content_type',
      coalesce(v_row.name, ''), v_row.poster, coalesce(v_row.poster_shape, ''),
      v_row.background, v_row.description, v_row.release_info, v_row.imdb_rating,
      coalesce(v_row.genres, '{}'), v_row.addon_base_url, coalesce(v_row.added_at, 0)
    );
  end loop;
end;
$$;
grant execute on function public.sync_delete_library_items(jsonb, int, text) to authenticated;

create or replace function public.sync_pull_library(p_profile_id int, p_limit int default 500, p_offset int default 0)
returns table(
  id text, user_id text, content_id text, content_type text, name text,
  poster text, poster_shape text, background text, description text,
  release_info text, imdb_rating real, genres text[], addon_base_url text,
  added_at bigint, profile_id int
)
language sql
stable
security definer
set search_path = public
as $$
  select null::text, owner_id::text, content_id, content_type, name,
    poster, poster_shape, background, description,
    release_info, imdb_rating, genres, addon_base_url,
    added_at, profile_id
  from public.library_items
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id
  order by added_at desc limit p_limit offset p_offset;
$$;
grant execute on function public.sync_pull_library(int, int, int) to authenticated;

create or replace function public.sync_get_library_delta_cursor(p_profile_id int)
returns bigint
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(max(event_id), 0) from public.library_events
  where owner_id = public.sync_effective_owner() and profile_id = p_profile_id;
$$;
grant execute on function public.sync_get_library_delta_cursor(int) to authenticated;

create or replace function public.sync_pull_library_delta(p_profile_id int, p_since_event_id bigint, p_limit int)
returns table(
  event_id bigint, operation text, content_id text, content_type text, name text,
  poster text, poster_shape text, background text, description text,
  release_info text, imdb_rating real, genres text[], addon_base_url text,
  added_at bigint
)
language sql
stable
security definer
set search_path = public
as $$
  select event_id, operation, content_id, content_type, name,
    poster, poster_shape, background, description,
    release_info, imdb_rating, genres, addon_base_url,
    added_at
  from public.library_events
  where owner_id = public.sync_effective_owner()
    and profile_id = p_profile_id and event_id > p_since_event_id
  order by event_id limit p_limit;
$$;
grant execute on function public.sync_pull_library_delta(int, bigint, int) to authenticated;

-- ===========================================================================
-- 8. Sync overview (single object, NOT a row: the app decodes one object)
-- ===========================================================================

create or replace function public.get_sync_overview()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_owner uuid := public.sync_effective_owner();
  v_addons jsonb := '{}'::jsonb;
  v_plugins jsonb := '{}'::jsonb;
  v_library jsonb := '{}'::jsonb;
  v_progress jsonb := '{}'::jsonb;
  v_watched jsonb := '{}'::jsonb;
  v_profiles jsonb := '{}'::jsonb;
  r record;
begin
  if v_owner is null then raise exception 'not authenticated'; end if;
  for r in select profile_id, count(*) as c from public.addons
           where user_id = v_owner group by profile_id loop
    v_addons := v_addons || jsonb_build_object(r.profile_id::text, r.c);
  end loop;
  for r in select profile_id, count(*) as c from public.plugins
           where user_id = v_owner group by profile_id loop
    v_plugins := v_plugins || jsonb_build_object(r.profile_id::text, r.c);
  end loop;
  for r in select profile_id, count(*) as c from public.library_items
           where owner_id = v_owner group by profile_id loop
    v_library := v_library || jsonb_build_object(r.profile_id::text, r.c);
  end loop;
  for r in select profile_id, count(*) as c from public.watch_progress
           where owner_id = v_owner group by profile_id loop
    v_progress := v_progress || jsonb_build_object(r.profile_id::text, r.c);
  end loop;
  for r in select profile_id, count(*) as c from public.watched_items
           where owner_id = v_owner group by profile_id loop
    v_watched := v_watched || jsonb_build_object(r.profile_id::text, r.c);
  end loop;
  for r in select profile_index, name, avatar_color_hex from public.profiles
           where owner_id = v_owner loop
    v_profiles := v_profiles || jsonb_build_object(
      r.profile_index::text,
      jsonb_build_object('name', r.name, 'color', r.avatar_color_hex));
  end loop;
  return jsonb_build_object(
    'addons', v_addons, 'plugins', v_plugins,
    'library_items', v_library, 'watch_progress', v_progress,
    'watched_items', v_watched, 'profiles', v_profiles);
end;
$$;
grant execute on function public.get_sync_overview() to authenticated;

-- ===========================================================================
-- 9. Membership / avatar catalog stubs (self-host has no premium tiers;
--    the app tolerates empty catalogs and falls back to color avatars)
-- ===========================================================================

create or replace function public.get_my_membership_overview()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select '[]'::jsonb;
$$;
grant execute on function public.get_my_membership_overview() to authenticated;

create or replace function public.get_my_member_access()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select '[]'::jsonb;
$$;
grant execute on function public.get_my_member_access() to authenticated;

create or replace function public.get_avatar_catalog()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select '[]'::jsonb;
$$;
grant execute on function public.get_avatar_catalog() to anon, authenticated;

create or replace function public.get_member_profile_avatar_catalog()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select '[]'::jsonb;
$$;
grant execute on function public.get_member_profile_avatar_catalog() to authenticated;

create or replace function public.get_member_profile_background_catalog()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select '[]'::jsonb;
$$;
grant execute on function public.get_member_profile_background_catalog() to authenticated;

-- ===========================================================================
-- 10. Post-apply verification (run these SELECTs after applying)
-- ===========================================================================
-- select proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--   where n.nspname = 'public' and proname like 'sync\_%' order by 1;
--   -- expect ~30 rows: push/pull/delete/copy/cursor/delta/get/overview/pin/code/owner/device fns
-- select tablename from pg_tables where schemaname = 'public'
--   and tablename in ('account_links','sync_codes','linked_devices','profiles',
--     'profile_pins','addons','plugins','profile_settings','provider_credentials',
--     'collections','home_catalog_settings','watch_progress','watch_progress_events',
--     'watched_items','watched_item_events','library_items','library_events');
--   -- expect 17 rows
-- On the TV afterwards: same Nuvio account on both devices -> install or
-- toggle an addon on one, restart the other, confirm it arrives. Toggle a
-- "Busqueda TG" switch on one, confirm on the other (PR #17 build+).
