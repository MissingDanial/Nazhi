import { httpError } from "../http.js";

export async function createMinimaxEmbeddings({ config, requestId, model, input, type = "db" }) {
  if (!config.minimaxEmbeddingApiKey) {
    throw httpError(500, "MINIMAX_NOT_CONFIGURED", "MiniMax embedding provider is not configured.");
  }

  const endpoint = config.minimaxEmbeddingEndpoint || "https://api.minimaxi.com/v1/embeddings";
  const providerModel = model || config.minimaxEmbeddingModel || "embo-01";
  const dimensions = config.minimaxEmbeddingDim || 1536;
  const controller = new AbortController();
  const timeoutMs = config.embeddingTimeoutMs ?? 30_000;
  let timeoutId;
  const deadline = new Promise((_, reject) => {
    timeoutId = setTimeout(() => {
      const error = httpError(504, "MINIMAX_EMBEDDING_TIMEOUT", "MiniMax embedding request timed out.");
      reject(error);
      controller.abort(error);
    }, timeoutMs);
  });

  let response;
  let payload;
  try {
    ({ response, payload } = await Promise.race([
      (async () => {
        const response = await fetch(buildEmbeddingEndpoint(endpoint, config.minimaxGroupId), {
          method: "POST",
          headers: {
            authorization: `Bearer ${config.minimaxEmbeddingApiKey}`,
            "content-type": "application/json"
          },
          body: JSON.stringify({
            texts: input.map((item) => item.text),
            model: providerModel,
            type
          }),
          signal: controller.signal
        });
        const payload = await response.json().catch(() => null);
        return { response, payload };
      })(),
      deadline
    ]));
  } catch (error) {
    if (controller.signal.aborted) {
      throw httpError(504, "MINIMAX_EMBEDDING_TIMEOUT", "MiniMax embedding request timed out.");
    }
    throw httpError(502, "MINIMAX_EMBEDDING_FAILED", "MiniMax embedding request failed.");
  } finally {
    clearTimeout(timeoutId);
  }

  if (payload?.base_resp && payload.base_resp.status_code !== 0) {
    throw httpError(
      502,
      "MINIMAX_EMBEDDING_PROVIDER_ERROR",
      payload.base_resp.status_msg || "MiniMax embedding provider returned an error."
    );
  }

  if (!response.ok) {
    throw httpError(
      response.status,
      "MINIMAX_EMBEDDING_FAILED",
      payload?.message || payload?.error?.message || "MiniMax embedding request failed."
    );
  }

  const vectors = payload?.vectors;
  if (
    !Array.isArray(vectors) ||
    vectors.length !== input.length ||
    vectors.some((vector) =>
      !Array.isArray(vector) || vector.length !== dimensions ||
      vector.some((value) => typeof value !== "number" || !Number.isFinite(value))
    )
  ) {
    throw httpError(
      502,
      "MINIMAX_EMBEDDING_SHAPE_UNSUPPORTED",
      "MiniMax embedding response shape does not match the input."
    );
  }

  return {
    requestId,
    provider: "minimax",
    model: providerModel,
    dimensions,
    items: input.map((item, index) => ({
      id: item.id,
      embedding: vectors[index],
      metadata: item.metadata || {}
    })),
    usage: {
      inputTokens: input.reduce((sum, item) => sum + Math.ceil(String(item.text || "").length / 2), 0)
    }
  };
}

function buildEmbeddingEndpoint(endpoint, groupId) {
  if (!groupId || !endpoint.includes("api.minimax.chat") || endpoint.includes("GroupId=")) {
    return endpoint;
  }

  const separator = endpoint.includes("?") ? "&" : "?";
  return `${endpoint}${separator}GroupId=${encodeURIComponent(groupId)}`;
}
