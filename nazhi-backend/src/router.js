import { config } from "./config.js";
import {
  handleAuthChangePassword,
  handleAuthLogin,
  handleAuthLogout,
  handleAuthMe,
  handleAuthRefresh,
  handleAuthRegister
} from "./auth/handlers.js";
import { resolveApiAuth } from "./auth/apiAuth.js";
import {
  handleAudioTranscriptionTaskStatus,
  handleCreateAudioTranscriptionJob,
  handleCreateOrganizeNotesJob,
  handleEmbeddings,
  handleKnowledgeChat,
  handleOrganizeNotes,
  handleRewriteQuestion,
  handleTaskStatus
} from "./handlers.js";
import { httpError, readJsonBody, readMultipartForm, sendJson } from "./http.js";
import { recordApiCallEvent } from "./services/apiCallLog.js";

export async function route(request, response) {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (request.method === "GET" && url.pathname === "/health") {
    return sendJson(response, 200, {
      ok: true,
      service: "nazhi-backend",
      embeddingProvider: config.embeddingProvider,
      chatProvider: config.chatProvider,
      asrProvider: config.asrProvider,
      asr: buildAsrHealth(config)
    });
  }

  if (request.method === "POST" && url.pathname === "/v2/auth/register") {
    const body = await readJsonBody(request);
    return sendJson(response, 201, await handleAuthRegister(body, request));
  }

  if (request.method === "POST" && url.pathname === "/v2/auth/login") {
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleAuthLogin(body, request));
  }

  if (request.method === "POST" && url.pathname === "/v2/auth/refresh") {
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleAuthRefresh(body, request));
  }

  if (request.method === "POST" && url.pathname === "/v2/auth/logout") {
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleAuthLogout(body));
  }

  if (request.method === "GET" && url.pathname === "/v2/auth/me") {
    return sendJson(response, 200, await handleAuthMe(request));
  }

  if (request.method === "POST" && url.pathname === "/v2/auth/change-password") {
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleAuthChangePassword(request, body));
  }

  if (request.method === "GET" && url.pathname === "/v1/auth-check") {
    const auth = await resolveApiAuth(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.auth-check", provider: null, statusCode: 200 },
      async () => ({
        ok: true,
        service: "nazhi-backend",
        authMode: auth.mode,
        user: auth.user
      })
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/embeddings") {
    const auth = await resolveApiAuth(request);
    const body = await readJsonBody(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.embeddings", provider: config.embeddingProvider, requestId: body.requestId, statusCode: 200 },
      async () => handleEmbeddings(body)
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/organize-notes") {
    const auth = await resolveApiAuth(request);
    const body = await readJsonBody(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.organize-notes", provider: config.chatProvider, requestId: body.requestId, statusCode: 200 },
      async () => handleOrganizeNotes(body)
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/organize-notes/jobs") {
    const auth = await resolveApiAuth(request);
    const body = await readJsonBody(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.organize-notes.jobs", provider: config.chatProvider, requestId: body.requestId, statusCode: 202 },
      async () => handleCreateOrganizeNotesJob(body, auth)
    );
  }

  if (request.method === "GET" && url.pathname.startsWith("/v1/tasks/")) {
    const auth = await resolveApiAuth(request);
    const taskId = decodeURIComponent(url.pathname.slice("/v1/tasks/".length));
    return sendLoggedJson(
      response,
      { auth, route: "v1.tasks.status", provider: null, statusCode: 200 },
      async () => handleTaskStatus(taskId, auth)
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/audio-transcriptions/jobs") {
    const auth = await resolveApiAuth(request);
    const form = await readMultipartForm(request);
    return sendLoggedJson(
      response,
      {
        auth,
        route: "v1.audio-transcriptions.jobs",
        provider: config.asrProvider,
        requestId: form.fields.requestId,
        statusCode: 202
      },
      async () => handleCreateAudioTranscriptionJob(form, auth)
    );
  }

  if (request.method === "GET" && url.pathname.startsWith("/v1/audio-transcriptions/jobs/")) {
    const auth = await resolveApiAuth(request);
    const taskId = decodeURIComponent(url.pathname.slice("/v1/audio-transcriptions/jobs/".length));
    return sendLoggedJson(
      response,
      { auth, route: "v1.audio-transcriptions.status", provider: null, statusCode: 200 },
      async () => handleAudioTranscriptionTaskStatus(taskId, auth)
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/knowledge-chat") {
    const auth = await resolveApiAuth(request);
    const body = await readJsonBody(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.knowledge-chat", provider: config.chatProvider, requestId: body.requestId, statusCode: 200 },
      async () => handleKnowledgeChat(body)
    );
  }

  if (request.method === "POST" && url.pathname === "/v1/rewrite-question") {
    const auth = await resolveApiAuth(request);
    const body = await readJsonBody(request);
    return sendLoggedJson(
      response,
      { auth, route: "v1.rewrite-question", provider: config.chatProvider, requestId: body.requestId, statusCode: 200 },
      async () => handleRewriteQuestion(body)
    );
  }

  throw httpError(404, "NOT_FOUND", "Route not found.");
}

async function sendLoggedJson(response, options, handler) {
  const startedAt = Date.now();
  try {
    const body = await handler();
    await recordApiCallEvent({
      auth: options.auth,
      route: options.route,
      provider: options.provider,
      requestId: options.requestId || body?.requestId,
      status: "succeeded",
      statusCode: options.statusCode,
      elapsedMs: Date.now() - startedAt,
      errorCode: null
    });
    return sendJson(response, options.statusCode, body);
  } catch (error) {
    await recordApiCallEvent({
      auth: options.auth,
      route: options.route,
      provider: options.provider,
      requestId: options.requestId,
      status: "failed",
      statusCode: error?.statusCode || 500,
      elapsedMs: Date.now() - startedAt,
      errorCode: error?.code || "INTERNAL_ERROR"
    });
    throw error;
  }
}

function buildAsrHealth(config) {
  const provider = config.asrProvider || "mock";
  const base = {
    provider,
    configured: false,
    shortAudio: false,
    longAudio: false,
    maxDurationMs: config.asrMaxDurationMs,
    shortThresholdMs: config.asrShortThresholdMs,
    status: "unsupported_provider",
    message: "ASR provider is not supported."
  };

  if (provider === "mock") {
    return {
      ...base,
      configured: true,
      shortAudio: true,
      longAudio: true,
      status: "mock",
      message: "Mock ASR is enabled for local development."
    };
  }

  if (provider !== "xfyun") {
    return base;
  }

  const hasCredentials = Boolean(config.xfyunAppId && config.xfyunApiKey && config.xfyunApiSecret);
  const hasShortEndpoint = Boolean(config.xfyunIatEndpoint);
  const hasLongEndpoints = Boolean(
    config.xfyunSpeedUploadEndpoint &&
    config.xfyunSpeedCreateEndpoint &&
    config.xfyunSpeedQueryEndpoint
  );
  const hasWebSocket = typeof WebSocket === "function";
  const shortAudio = hasCredentials && hasShortEndpoint && hasWebSocket;
  const longAudio = hasCredentials && hasLongEndpoints;

  if (!hasCredentials) {
    return {
      ...base,
      status: "missing_credentials",
      message: "Xfyun ASR credentials are missing."
    };
  }

  if (!hasWebSocket) {
    return {
      ...base,
      shortAudio: false,
      longAudio,
      status: "websocket_unavailable",
      message: "Node.js WebSocket support is unavailable for short audio ASR."
    };
  }

  return {
    ...base,
    configured: shortAudio && longAudio,
    shortAudio,
    longAudio,
    status: shortAudio && longAudio ? "ready" : "missing_endpoint",
    message: shortAudio && longAudio
      ? "Xfyun ASR is configured."
      : "Xfyun ASR endpoint configuration is incomplete."
  };
}
