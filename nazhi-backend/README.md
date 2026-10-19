# Nazhi Backend V2.1

Lightweight Node.js backend proxy for Nazhi AI features.

The backend proxies AI requests, provides account authentication and owner-scoped async jobs, and returns Android-friendly JSON. Android keeps notes, knowledge entries, embeddings, and local retrieval state.

For the full API contract, see [API.md](./API.md).

## Requirements

- Node.js >= 22 (the short-audio provider uses the built-in WebSocket)
- PostgreSQL for V2 account data
- Redis for V2 auth rate limiting and short-lived auth state
- A `.env` file based on `.env.example`
- MiniMax API keys when `EMBEDDING_PROVIDER=minimax` or `CHAT_PROVIDER=minimax`

## Run Locally

```powershell
cd nazhi-backend
npm ci
Copy-Item .env.example .env
# Edit .env before starting; for mock-only development, leave DATABASE_URL and REDIS_URL empty.
npm run dev
```

Default URL:

```text
http://localhost:8787
```

The sample uses mock AI providers. For account-free local testing, keep `NAZHI_DEV_TOKEN`, cancel the Android login dialog, and set the app's service access token to the same value. Real AI and account setup are described below.

## Commands

```powershell
npm run dev
npm run start
npm run check
npm test
```

## Current Endpoints

- `GET /health`
- `GET /v1/auth-check`
- `POST /v2/auth/register`
- `POST /v2/auth/login`
- `POST /v2/auth/refresh`
- `POST /v2/auth/logout`
- `POST /v2/auth/change-password`
- `GET /v2/auth/me`
- `POST /v1/embeddings`
- `POST /v1/organize-notes`
- `POST /v1/organize-notes/jobs`
- `GET /v1/tasks/:taskId`
- `POST /v1/knowledge-chat`

## Auth

V2 account endpoints use user access tokens:

```http
Authorization: Bearer <accessToken>
```

`/v1/*` AI endpoints now prefer user access tokens, so backend calls can be attributed to a user. V1 development endpoints still support `NAZHI_DEV_TOKEN`. If it is set, development requests may include:

```http
Authorization: Bearer <NAZHI_DEV_TOKEN>
```

`/health` is public so deployment monitors can verify service status.

## V2 Account Setup

Apply the PostgreSQL migration before using `/v2/auth/*`:

Set `$env:DATABASE_URL` to your configured database connection first; `psql` does not load `.env` automatically. The Node.js service loads `.env` from the backend working directory.

```powershell
psql $env:DATABASE_URL -f migrations/001_auth.sql
psql $env:DATABASE_URL -f migrations/002_api_call_events.sql
```

Required auth environment variables:

```env
DATABASE_URL=postgres://nazhi:nazhi-password@127.0.0.1:5432/nazhi_prod
REDIS_URL=redis://127.0.0.1:6379
NAZHI_JWT_SECRET=replace-with-at-least-32-random-characters
ACCESS_TOKEN_TTL_SECONDS=900
REFRESH_TOKEN_TTL_DAYS=30
```

Install dependencies after pulling V2 changes:

```powershell
npm ci
```

## Provider Setup

Use `.env` for runtime configuration. Do not commit real API keys.

Recommended MiniMax settings:

```env
EMBEDDING_PROVIDER=minimax
CHAT_PROVIDER=minimax
MINIMAX_EMBEDDING_API=<embedding-key>
MINIMAX_API=<chat-key>
MINIMAX_EMBEDDING_MODEL=embo-01
MINIMAX_EMBEDDING_DIM=1536
MINIMAX_CHAT_MODEL=MiniMax-M2.7-highspeed
```

Mock mode remains available for local Android development:

```env
EMBEDDING_PROVIDER=mock
CHAT_PROVIDER=mock
```

Recommended Xfyun ASR settings:

```env
ASR_PROVIDER=xfyun
ASR_MAX_DURATION_MS=900000
ASR_SHORT_THRESHOLD_MS=58000
XFYUN_APP_ID=<server-only-app-id>
XFYUN_API_KEY=<server-only-api-key>
XFYUN_API_SECRET=<server-only-api-secret>
```

After configuring Xfyun, restart the backend and check `/health`. The response includes an `asr` object with `configured`, `shortAudio`, `longAudio`, and `status` fields. It never returns raw credentials or signatures.

Expected ASR smoke test:

1. Start the backend with `ASR_PROVIDER=xfyun`.
2. Open Android Settings and run the backend connection check.
3. Confirm the ASR row shows Xfyun ready, with short and long audio available.
4. Record a 10-30 second microphone sample from Android and verify it becomes a Today note.
5. Record a silent sample and verify it fails without saving an empty note.

## Deployment Notes

- Keep `.env` on the server only.
- Use `npm run check` before restarting the service.
- Current production service can run behind systemd with `npm run start` or `node src/server.js`.
- HTTPS reverse proxy is recommended before public release; current `http://IP:端口` mode is acceptable for controlled development testing only.

## Runtime Behavior

- Chat requests have a 45 second server-side timeout and return `MINIMAX_CHAT_TIMEOUT` on timeout.
- Embedding requests, including response-body reads, default to 30 seconds (`EMBEDDING_TIMEOUT_MS`) and return `MINIMAX_EMBEDDING_TIMEOUT` on timeout.
- Long ASR defaults to 30 seconds per request (`ASR_REQUEST_TIMEOUT_MS`) and 240 seconds for the whole task (`ASR_TASK_TIMEOUT_MS`). Timeout errors are `ASR_REQUEST_TIMEOUT` or `ASR_TIMEOUT`.
- AI organize supports async jobs so Android can show progress instead of waiting on a blank loading state.
- Async task state is in memory only; after backend restart Android should allow retry.
- Async task status is scoped by auth owner: user-token tasks can only be read by the same user, while development-token tasks stay in the development lane.
- Organize/audio job creation is idempotent for the same auth mode, user, task type and `requestId`. Repeating an identical payload returns the original task; changing the payload returns `409 IDEMPOTENCY_CONFLICT`. Explicitly retrying a failed task needs a new request ID.
- `GET /v1/tasks/by-request/:requestId?type=ORGANIZE_NOTES|AUDIO_TRANSCRIPTION` recovers an accepted job after a lost creation response. The request ID must be URL encoded. Lookup is owner scoped and never starts a model call.
- Running jobs are never evicted by TTL or capacity trimming. When all 100 slots are running, new jobs return `429 TASK_CAPACITY_REACHED` before invoking a provider. Terminal jobs can expire after 30 minutes or be removed under capacity pressure. Task identities and results still do not survive a server restart.
- Knowledge chat requires valid JSON and model-supplied citations. Unknown source IDs and fabricated excerpts are rejected; no citations are synthesized. Empty excerpts are allowed as explicit source pointers for paraphrases. Answers without valid sources return `MINIMAX_CHAT_UNGROUNDED`, except explicit insufficient-knowledge responses, which return an empty memory digest and do not add unsupported conclusions to conversation memory.
- Embedding requests accept `type=db|query`. Legacy query metadata remains supported; mixed or conflicting purposes return 400. Vectors must match the configured dimension and contain finite numbers. Mock responses use `model=mock-bow-v1`, independently of a requested real model.
- Historical mock vectors already labeled as a real model must be reindexed before using that real model. Their provenance cannot be recovered automatically from the old records.
- API call logs are written to `auth.api_call_events` when `DATABASE_URL` and the migration are available. Logs include route, auth mode, user id, request id, provider, status, latency and error code; they do not store note content, prompts, answers, audio, API keys or bearer tokens.
