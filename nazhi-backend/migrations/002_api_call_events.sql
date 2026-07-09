create table if not exists auth.api_call_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  auth_mode text not null,
  route text not null,
  request_id text,
  provider text,
  status text not null,
  status_code integer,
  elapsed_ms integer not null default 0,
  error_code text,
  created_at timestamptz not null default now()
);

create index if not exists idx_api_call_events_user_id on auth.api_call_events(user_id);
create index if not exists idx_api_call_events_route_created_at on auth.api_call_events(route, created_at);
create index if not exists idx_api_call_events_created_at on auth.api_call_events(created_at);
