create extension if not exists pgcrypto;

create schema if not exists auth;

create table if not exists auth.users (
  id uuid primary key default gen_random_uuid(),
  email text unique not null,
  username text not null,
  password_hash text not null,
  status text not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_login_at timestamptz
);

create table if not exists auth.refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  token_hash text unique not null,
  device_name text,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists auth.login_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  email text,
  ip text,
  user_agent text,
  success boolean not null,
  reason text,
  created_at timestamptz not null default now()
);

create index if not exists idx_refresh_tokens_user_id on auth.refresh_tokens(user_id);
create index if not exists idx_refresh_tokens_expires_at on auth.refresh_tokens(expires_at);
create index if not exists idx_login_events_user_id on auth.login_events(user_id);
create index if not exists idx_login_events_created_at on auth.login_events(created_at);
