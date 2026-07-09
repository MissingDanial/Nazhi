import { config } from "../config.js";
import { createApiCallEvent } from "../auth/repository.js";

export async function recordApiCallEvent(event) {
  if (!config.databaseUrl) {
    return;
  }
  try {
    await createApiCallEvent({
      userId: event.auth?.userId || null,
      authMode: event.auth?.mode || "unknown",
      route: event.route,
      requestId: event.requestId || null,
      provider: event.provider || null,
      status: event.status,
      statusCode: event.statusCode || null,
      elapsedMs: event.elapsedMs || 0,
      errorCode: event.errorCode || null
    });
  } catch (error) {
    console.warn(
      JSON.stringify({
        event: "api_call_log_failed",
        route: event.route,
        code: error?.code || "API_CALL_LOG_FAILED",
        message: error?.publicMessage || error?.message || "Failed to record API call."
      })
    );
  }
}
