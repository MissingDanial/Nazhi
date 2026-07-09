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
