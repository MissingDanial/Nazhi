import assert from "node:assert/strict";
import test from "node:test";
import { config } from "./config.js";
import { handleEmbeddings } from "./handlers.js";

test("embedding queries forward the explicit request purpose and preserve item identity", async (t) => {
  useEmbeddingConfig(t, { embeddingProvider: "minimax" });
  let request;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    request = JSON.parse(init.body);
    return new Response(JSON.stringify({ vectors: [[1, 0, 0], [0, 1, 0]] }));
  });
  const input = [
    { id: "query-b", text: "知识库检索", metadata: { source: "b" } },
    { id: "query-a", text: "如何引用", metadata: { source: "a" } }
  ];

  const result = await handleEmbeddings({ requestId: "test-query", type: "query", input });

  assert.equal(request.type, "query");
  assert.deepEqual(request.texts, input.map((item) => item.text));
  assert.deepEqual(result.items.map((item) => item.id), input.map((item) => item.id));
  assert.deepEqual(result.items.map((item) => item.metadata), input.map((item) => item.metadata));
});

function useEmbeddingConfig(t, overrides = {}) {
  const originalConfig = { ...config };
  Object.assign(config, {
    embeddingProvider: "minimax",
    embeddingModel: "embo-01",
    embeddingDimensions: 3,
    minimaxEmbeddingApiKey: "test-key",
    minimaxEmbeddingEndpoint: "https://example.test/embeddings",
    minimaxEmbeddingDim: 3,
    minimaxEmbeddingModel: "embo-01",
    embeddingTimeoutMs: 1000,
    ...overrides
  });
  t.after(() => {
    for (const key of Object.keys(config)) {
      if (!(key in originalConfig)) delete config[key];
    }
    Object.assign(config, originalConfig);
  });
}

test("legacy embedding queries infer query purpose from all input metadata", async (t) => {
  useEmbeddingConfig(t);
  let request;
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    request = JSON.parse(init.body);
    return new Response(JSON.stringify({ vectors: [[1, 0, 0]] }));
  });

  const result = await handleEmbeddings({
    input: [{ id: "query", text: "如何检索", metadata: { type: "query" } }]
  });

  assert.equal(request.type, "query");
  assert.deepEqual(result.items[0].metadata, { type: "query" });
});

test("ambiguous or unsupported embedding purposes are rejected before contacting the provider", async (t) => {
  useEmbeddingConfig(t);
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    return new Response(JSON.stringify({ vectors: [[1, 0, 0], [0, 1, 0]] }));
  });
  const query = { id: "query", text: "查询", metadata: { type: "query" } };
  const document = { id: "entry", text: "知识", metadata: { type: "db" } };
  for (const body of [
    { input: [query, document] },
    { input: [query, { id: "entry", text: "知识" }] },
    { type: "db", input: [query] },
    { type: "query", input: [document] }
  ]) {
    await assert.rejects(() => handleEmbeddings(body), { statusCode: 400, code: "MIXED_EMBEDDING_TYPES" });
  }
  for (const body of [
    { type: "other", input: [document] },
    { type: null, input: [document] },
    { input: [{ ...document, metadata: { type: "other" } }] }
  ]) {
    await assert.rejects(() => handleEmbeddings(body), { statusCode: 400, code: "INVALID_EMBEDDING_TYPE" });
  }
  assert.equal(fetchMock.mock.callCount(), 0);
});

test("mock embeddings have a distinct model identity even when a real model was requested", async (t) => {
  useEmbeddingConfig(t, { embeddingProvider: "mock" });
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    throw new Error("Mock embeddings must not call a provider.");
  });
  const input = [{ id: "entry", text: "知识库", metadata: { ownerId: "entry" } }];

  const result = await handleEmbeddings({ model: "embo-01", input });

  assert.equal(result.provider, "mock");
  assert.equal(result.model, "mock-bow-v1");
  assert.equal(result.dimensions, 3);
  assert.equal(result.items[0].embedding.length, 3);
  assert.ok(result.items[0].embedding.every(Number.isFinite));
  assert.deepEqual(result.items[0].metadata, input[0].metadata);
  assert.equal(fetchMock.mock.callCount(), 0);
});

test("legacy database embeddings default to db and explicit db requests remain compatible", async (t) => {
  useEmbeddingConfig(t);
  const requests = [];
  t.mock.method(globalThis, "fetch", async (_url, init) => {
    requests.push(JSON.parse(init.body));
    return new Response(JSON.stringify({ vectors: [[1, 0, 0]] }));
  });
  const body = { input: [{ id: "entry", text: "知识库", metadata: { ownerType: "KNOWLEDGE_ENTRY" } }] };

  await handleEmbeddings(body);
  await handleEmbeddings({ ...body, type: "db" });

  assert.deepEqual(requests.map((request) => request.type), ["db", "db"]);
});
