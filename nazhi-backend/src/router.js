import { config } from "./config.js";
import {
  handleAuthChangePassword,
  handleAuthLogin,
  handleAuthLogout,
  handleAuthMe,
  handleAuthRefresh,
  handleAuthRegister
} from "./auth/handlers.js";
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
import { httpError, readJsonBody, readMultipartForm, requireAuth, sendJson } from "./http.js";

export async function route(request, response) {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (request.method === "GET" && url.pathname === "/health") {
    return sendJson(response, 200, {
      ok: true,
      service: "nazhi-backend",
      embeddingProvider: config.embeddingProvider,
      chatProvider: config.chatProvider,
      asrProvider: config.asrProvider
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
    requireAuth(request, config.devToken);
    return sendJson(response, 200, {
      ok: true,
      service: "nazhi-backend"
    });
  }

  if (request.method === "POST" && url.pathname === "/v1/embeddings") {
    requireAuth(request, config.devToken);
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleEmbeddings(body));
  }

  if (request.method === "POST" && url.pathname === "/v1/organize-notes") {
    requireAuth(request, config.devToken);
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleOrganizeNotes(body));
  }

  if (request.method === "POST" && url.pathname === "/v1/organize-notes/jobs") {
    requireAuth(request, config.devToken);
    const body = await readJsonBody(request);
    return sendJson(response, 202, await handleCreateOrganizeNotesJob(body));
  }

  if (request.method === "GET" && url.pathname.startsWith("/v1/tasks/")) {
    requireAuth(request, config.devToken);
    const taskId = decodeURIComponent(url.pathname.slice("/v1/tasks/".length));
    return sendJson(response, 200, handleTaskStatus(taskId));
  }

  if (request.method === "POST" && url.pathname === "/v1/audio-transcriptions/jobs") {
    requireAuth(request, config.devToken);
    const form = await readMultipartForm(request);
    return sendJson(response, 202, await handleCreateAudioTranscriptionJob(form));
  }

  if (request.method === "GET" && url.pathname.startsWith("/v1/audio-transcriptions/jobs/")) {
    requireAuth(request, config.devToken);
    const taskId = decodeURIComponent(url.pathname.slice("/v1/audio-transcriptions/jobs/".length));
    return sendJson(response, 200, handleAudioTranscriptionTaskStatus(taskId));
  }

  if (request.method === "POST" && url.pathname === "/v1/knowledge-chat") {
    requireAuth(request, config.devToken);
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleKnowledgeChat(body));
  }

  if (request.method === "POST" && url.pathname === "/v1/rewrite-question") {
    requireAuth(request, config.devToken);
    const body = await readJsonBody(request);
    return sendJson(response, 200, await handleRewriteQuestion(body));
  }

  throw httpError(404, "NOT_FOUND", "Route not found.");
}
