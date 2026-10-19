import assert from "node:assert/strict";
import test from "node:test";
import { createMinimaxEmbeddings } from "./minimaxEmbeddingProvider.js";

const config = {
  minimaxEmbeddingApiKey: "test-key",
  minimaxEmbeddingEndpoint: "https://example.test/embeddings",
  minimaxEmbeddingModel: "embo-01",
  minimaxEmbeddingDim: 3,
  embeddingTimeoutMs: 1000
};
const input = [{ id: "entry", text: "知识库", metadata: { ownerId: "entry" } }];

test("provider vectors must have one finite numeric vector of the configured dimension per input", async (t) => {
  for (const vectors of [
    [],
    [[1, 0, 0], [0, 1, 0]],
    [[1, 0]],
    [[1, 0, 0, 0]],
    [null],
    ["123"],
    [["1", 0, 0]],
    [[null, 0, 0]],
    [[NaN, 0, 0]],
    [[Infinity, 0, 0]],
    [[-Infinity, 0, 0]],
    { 0: [1, 0, 0], length: 1 }
  ]) {
    t.mock.method(globalThis, "fetch", async () => ({ ok: true, json: async () => ({ vectors }) }));
    await assert.rejects(
      () => createMinimaxEmbeddings({ config, requestId: "shape", input }),
      { statusCode: 502, code: "MINIMAX_EMBEDDING_SHAPE_UNSUPPORTED" }
    );
    t.mock.restoreAll();
  }
});

test("embedding deadline also covers a response body that never finishes", { timeout: 1000 }, async (t) => {
  let signal;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    signal = init.signal;
    return { ok: true, json: () => new Promise(() => {}) };
  });

  await assert.rejects(
    () => createMinimaxEmbeddings({ config: { ...config, embeddingTimeoutMs: 20 }, requestId: "timeout", input }),
    { statusCode: 504, code: "MINIMAX_EMBEDDING_TIMEOUT" }
  );
  assert.equal(signal.aborted, true);
});

test("valid vectors preserve model, identity and metadata and leave no pending abort", async (t) => {
  let request;
  let signal;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    signal = init.signal;
    request = JSON.parse(init.body);
    return new Response(JSON.stringify({ vectors: [[1, 0, 0], [0, 1, 0]] }));
  });
  const items = [input[0], { id: "query", text: "检索", metadata: { type: "query", source: "test" } }];

  const result = await createMinimaxEmbeddings({
    config: { ...config, embeddingTimeoutMs: 100 },
    requestId: "valid",
    model: "test-model",
    input: items,
    type: "query"
  });

  assert.equal(result.requestId, "valid");
  assert.equal(result.provider, "minimax");
  assert.equal(result.model, "test-model");
  assert.equal(result.dimensions, 3);
  assert.equal(request.type, "query");
  assert.equal(request.model, "test-model");
  assert.deepEqual(result.items, items.map((item, index) => ({
    id: item.id,
    embedding: index === 0 ? [1, 0, 0] : [0, 1, 0],
    metadata: item.metadata
  })));
  await new Promise((resolve) => setTimeout(resolve, 120));
  assert.equal(signal.aborted, false);
});

test("embedding deadline terminates a request that never returns headers", { timeout: 1000 }, async (t) => {
  let signal;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    signal = init.signal;
    return new Promise(() => {});
  });

  await assert.rejects(
    () => createMinimaxEmbeddings({ config: { ...config, embeddingTimeoutMs: 20 }, requestId: "timeout", input }),
    { statusCode: 504, code: "MINIMAX_EMBEDDING_TIMEOUT" }
  );
  assert.equal(signal.aborted, true);
});

test("provider connection errors have a stable public error and leave no pending abort", async (t) => {
  let signal;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    signal = init.signal;
    throw new TypeError("Private transport diagnostics.");
  });

  await assert.rejects(
    () => createMinimaxEmbeddings({ config: { ...config, embeddingTimeoutMs: 30 }, requestId: "failure", input }),
    { statusCode: 502, code: "MINIMAX_EMBEDDING_FAILED", publicMessage: "MiniMax embedding request failed." }
  );
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(signal.aborted, false);
});

test("malformed provider JSON and overflowing JSON numbers cannot become usable embeddings", async (t) => {
  for (const payload of ["invalid json", '{"vectors":[[1e400,0,0]]}']) {
    t.mock.method(globalThis, "fetch", async () => new Response(payload));
    await assert.rejects(
      () => createMinimaxEmbeddings({ config, requestId: "json", input }),
      { statusCode: 502, code: "MINIMAX_EMBEDDING_SHAPE_UNSUPPORTED" }
    );
    t.mock.restoreAll();
  }
});
