import { httpError } from "../http.js";

export async function createMinimaxEmbeddings({ config, requestId, model, input, type = "db" }) {
  if (!config.minimaxEmbeddingApiKey) {
    throw httpError(500, "MINIMAX_NOT_CONFIGURED", "MiniMax embedding provider is not configured.");
  }

  const endpoint = config.minimaxEmbeddingEndpoint || "https://api.minimaxi.com/v1/embeddings";
  const providerModel = model || config.minimaxEmbeddingModel || "embo-01";
  const dimensions = config.minimaxEmbeddingDim || 1536;

  const response = await fetch(buildEmbeddingEndpoint(endpoint, config.minimaxGroupId), {
    method: "POST",
    headers: {
      authorization: `Bearer ${config.minimaxEmbeddingApiKey}`,
      "content-type": "application/json"
    },
    body: JSON.stringify({
      texts: input.map(item => item.text),
      model: providerModel,
      type: type
    })
  });

  const payload = await response.json().catch(() => null);
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

  const vectors = payload?.vectors || [];
  if (vectors.length !== input.length) {
    throw httpError(
      502,
      "MINIMAX_EMBEDDING_SHAPE_UNSUPPORTED",
      "MiniMax embedding response shape does not match the input."
    );
  }

  return {
    requestId,
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
