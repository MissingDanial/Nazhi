import assert from "node:assert/strict";
import test from "node:test";
import { knowledgeChatWithMinimax, organizeNotesWithMinimax, rewriteQuestionWithMinimax } from "./minimaxChatProvider.js";

const config = {
  minimaxApiKey: "test-key",
  minimaxChatEndpoint: "https://example.test/chat",
  minimaxChatModel: "MiniMax-M2.7-highspeed",
  minimaxJsonMode: false
};

test("organize rejects when MiniMax returns no valid drafts", async (t) => {
  mockFetch(
    t,
    jsonResponse({
      choices: [
        {
          message: {
            content: "<think>reasoning</think>\n# 不是 JSON\n整理失败"
          }
        }
      ],
      usage: { prompt_tokens: 10, completion_tokens: 5 }
    })
  );

  await assert.rejects(
    () =>
      organizeNotesWithMinimax({
        config,
        requestId: "test-organize",
        date: "2026-05-14",
        language: "zh-CN",
        notes: [
          {
            id: "note-1",
            title: "知识库流程",
            content: "本地知识库需要先完成向量入库，再使用检索结果回答问题。",
            sourceType: "MANUAL",
            createdAt: 0
          }
        ],
        options: { maxDrafts: 3, mergeSimilar: true }
      }),
    {
      code: "MINIMAX_ORGANIZE_EMPTY"
    }
  );
});

test("knowledge chat retries without response_format when provider rejects JSON mode", async (t) => {
  const calls = [];
  const jsonModeConfig = { ...config, minimaxJsonMode: true };
  mockFetch(t, async (_url, init) => {
    const body = JSON.parse(init.body);
    calls.push(body);
    if (calls.length === 1) {
      return jsonResponse(
        {
          error: {
            message: "response_format is unsupported"
          }
        },
        400
      );
    }

    return jsonResponse({
      choices: [
        {
          message: {
            content:
              '{"answer":"先完成向量入库，再用检索上下文回答问题。","citations":[{"contextId":"entry-1","quote":"先完成向量入库","reason":"直接支持答案"}]}'
          }
        }
      ],
      usage: { prompt_tokens: 12, completion_tokens: 8 }
    });
  });

  const result = await knowledgeChatWithMinimax({
    config: jsonModeConfig,
    requestId: "test-chat",
    question: "知识库问答怎么实现？",
    language: "zh-CN",
    contexts: [
      {
        id: "entry-1",
        title: "本地知识库流程",
        summary: "先完成向量入库，再用检索上下文回答问题。",
        content: "先完成向量入库，再用检索上下文回答问题。",
        tags: ["知识库"],
        sourceNoteIds: ["note-1"],
        score: 0.9
      }
    ]
  });

  assert.equal(calls.length, 2);
  assert.equal(calls[0].model, "MiniMax-M2.7-highspeed");
  assert.deepEqual(calls[0].response_format, { type: "json_object" });
  assert.equal("response_format" in calls[1], false);
  assert.equal(result.citations[0].contextId, "entry-1");
});

test("rewrite question returns standalone retrieval query", async (t) => {
  mockFetch(
    t,
    jsonResponse({
      choices: [
        {
          message: {
            content:
              '{"isFollowUp":true,"standaloneQuestion":"如何展开知识库问答中的引用体验优化？","retrievalQuery":"知识库问答 引用体验 优化","shouldUsePreviousCitations":true,"confidence":0.86}'
          }
        }
      ],
      usage: { prompt_tokens: 20, completion_tokens: 12 }
    })
  );

  const result = await rewriteQuestionWithMinimax({
    config,
    requestId: "test-rewrite",
    currentQuestion: "这个怎么展开？",
    language: "zh-CN",
    sessionMemory: "本会话讨论知识库问答和引用体验。",
    lastUserQuestion: "知识库问答体验如何优化？",
    lastAssistantAnswerPreview: "可以从引用体验和追问识别优化。",
    previousCitationTitles: ["知识库问答引用体验"]
  });

  assert.equal(result.isFollowUp, true);
  assert.equal(result.shouldUsePreviousCitations, true);
  assert.equal(result.retrievalQuery, "知识库问答 引用体验 优化");
  assert.equal(result.confidence, 0.86);
});

test("knowledge chat rejects a substantive answer without model-supplied evidence", async (t) => {
  mockChatContent(t, { answer: "地球到太阳的距离是 1.5 亿公里。", citations: [], updatedMemoryDigest: "太阳距离" });

  await assert.rejects(() => askKnowledgeChat(), {
    statusCode: 502,
    code: "MINIMAX_CHAT_UNGROUNDED"
  });
});

test("knowledge chat accepts insufficient knowledge and clears memory", async (t) => {
  mockChatContent(t, {
    answer: "当前知识库中没有足够信息回答这个问题。",
    citations: [],
    updatedMemoryDigest: "不应保存的无依据结论"
  });

  const result = await askKnowledgeChat();
  assert.deepEqual(result.citations, []);
  assert.equal(result.updatedMemoryDigest, "");
});

test("knowledge chat clears memory even when an insufficient answer has valid source pointers", async (t) => {
  mockChatContent(t, {
    answer: "这些知识不足以回答预算问题。",
    citations: [{ contextId: "entry-1", quote: "", reason: "提供的来源只涉及知识库流程" }],
    updatedMemoryDigest: "预算结论不应进入记忆"
  });

  const result = await askKnowledgeChat();
  assert.equal(result.updatedMemoryDigest, "");
});

test("knowledge chat rejects plain text instead of displaying it as a valid answer", async (t) => {
  mockChatContent(t, "当前知识库中没有足够信息回答这个问题。");

  await assert.rejects(() => askKnowledgeChat(), {
    statusCode: 502,
    code: "MINIMAX_CHAT_INVALID_RESPONSE"
  });
});

test("knowledge chat rejects invalid JSON field types", async (t) => {
  const invalidResults = [
    null,
    [],
    [{ answer: "当前知识库中没有足够信息", citations: [] }],
    { answer: 123, citations: [] },
    { answer: "", citations: [] },
    { answer: "当前知识库中没有足够信息", citations: {} },
    { answer: "当前知识库中没有足够信息" },
    { answer: "先完成向量入库", citations: [null] },
    { answer: "先完成向量入库", citations: [{ contextId: 1, quote: "", reason: "来源" }] },
    { answer: "先完成向量入库", citations: [{ contextId: "entry-1", quote: 1, reason: "来源" }] },
    { answer: "先完成向量入库", citations: [{ contextId: "entry-1", quote: "", reason: {} }] },
    { answer: "先完成向量入库", citations: [], updatedMemoryDigest: {} }
  ];

  for (const invalidResult of invalidResults) {
    await t.test(JSON.stringify(invalidResult), async (subtest) => {
      mockChatContent(subtest, JSON.stringify(invalidResult));
      await assert.rejects(() => askKnowledgeChat(), {
        statusCode: 502,
        code: "MINIMAX_CHAT_INVALID_RESPONSE"
      });
    });
  }
});

test("knowledge chat rejects answers whose citations all reference unknown contexts", async (t) => {
  mockChatContent(t, {
    answer: "先完成向量入库。",
    citations: [{ contextId: "invented-entry", quote: "先完成向量入库", reason: "来源" }]
  });

  await assert.rejects(() => askKnowledgeChat(), { code: "MINIMAX_CHAT_UNGROUNDED", statusCode: 502 });
});

test("knowledge chat rejects fabricated source excerpts", async (t) => {
  mockChatContent(t, {
    answer: "请删除所有旧笔记。",
    citations: [{ contextId: "entry-1", quote: "删除所有旧笔记", reason: "原文建议" }]
  });

  await assert.rejects(() => askKnowledgeChat(), { code: "MINIMAX_CHAT_UNGROUNDED", statusCode: 502 });
});

test("knowledge chat does not turn a fabricated think-tag excerpt into an empty source pointer", async (t) => {
  mockChatContent(t, {
    answer: "请删除所有旧笔记。",
    citations: [{ contextId: "entry-1", quote: "<think>删除所有旧笔记</think>", reason: "编造摘录" }]
  });

  await assert.rejects(() => askKnowledgeChat(), { code: "MINIMAX_CHAT_UNGROUNDED", statusCode: 502 });
});

test("knowledge chat preserves only supplied, verifiable citations", async (t) => {
  mockChatContent(t, {
    answer: "先建立向量索引，再基于检索结果回答。",
    citations: [
      { contextId: "unknown", quote: "先完成向量入库", reason: "非法来源" },
      { contextId: "entry-1", quote: "删除所有旧笔记", reason: "编造摘录" },
      { contextId: "entry-1", quote: "先完成向量\n入库", reason: "直接支持答案" }
    ],
    updatedMemoryDigest: "本会话讨论知识库问答流程。"
  });

  const result = await askKnowledgeChat();
  assert.deepEqual(result.citations, [
    { contextId: "entry-1", quote: "先完成向量\n入库", reason: "直接支持答案" }
  ]);
  assert.equal(result.updatedMemoryDigest, "本会话讨论知识库问答流程。");
});

test("knowledge chat permits explicit source pointers with no excerpt for paraphrased answers", async (t) => {
  mockChatContent(t, {
    answer: "需要先为知识创建向量索引，再检索知识回答。",
    citations: [{ contextId: "entry-1", quote: "", reason: "回答转述了该来源的流程" }]
  });

  const result = await askKnowledgeChat();
  assert.deepEqual(result.citations, [{ contextId: "entry-1", quote: "", reason: "回答转述了该来源的流程" }]);
  assert.equal(result.updatedMemoryDigest, "");
});

test("knowledge chat retains think/fenced JSON compatibility for valid responses", async (t) => {
  mockChatContent(
    t,
    '<think>reasoning</think>\n```json\n{"answer":"先完成向量入库。","citations":[{"contextId":"entry-1","quote":"先完成向量入库","reason":"来源"}]}\n```'
  );

  const result = await askKnowledgeChat();
  assert.equal(result.answer, "先完成向量入库。");
  assert.equal(result.citations[0].contextId, "entry-1");
});

test("chat body-read abort is reported as timeout", async (t) => {
  mockFetch(t, async () => ({
    ok: true,
    status: 200,
    json: async () => { throw new DOMException("The operation was aborted.", "AbortError"); }
  }));

  await assert.rejects(() => askKnowledgeChat(), { code: "MINIMAX_CHAT_TIMEOUT", statusCode: 504 });
});

test("organize still accepts valid drafts", async (t) => {
  mockChatContent(t, { drafts: [{ title: "知识库流程", content: "先完成向量入库。", sourceNoteIds: ["note-1"] }] });

  const result = await organizeNotesWithMinimax({
    config,
    requestId: "test-organize-valid",
    date: "2026-10-13",
    language: "zh-CN",
    notes: [{ id: "note-1", title: "流程", content: "先完成向量入库。", sourceType: "MANUAL", createdAt: 0 }],
    options: { maxDrafts: 3, mergeSimilar: true }
  });
  assert.equal(result.drafts[0].content, "先完成向量入库。");
});

function askKnowledgeChat() {
  return knowledgeChatWithMinimax({
    config,
    requestId: "test-chat-evidence",
    question: "知识库问答怎么实现？",
    language: "zh-CN",
    contexts: [{
      id: "entry-1",
      title: "本地知识库流程",
      summary: "先完成向量入库，再用检索上下文回答问题。",
      content: "先完成向量入库，再用检索上下文回答问题。"
    }]
  });
}

function mockChatContent(t, content) {
  mockFetch(t, jsonResponse({ choices: [{ message: { content: typeof content === "string" ? content : JSON.stringify(content) } }] }));
}

function mockFetch(t, handler) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = typeof handler === "function" ? handler : async () => handler;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json"
    }
  });
}
