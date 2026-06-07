import { httpError } from "../http.js";
import { buildKnowledgeChatPrompt, parseKnowledgeChatFromText } from "../services/knowledgeChat.js";
import { buildOrganizePrompt, organizeNotesMock, parseOrganizeDraftsFromText } from "../services/organizeNotes.js";
import { buildQuestionRewritePrompt, parseQuestionRewriteFromText } from "../services/questionRewrite.js";

const CHAT_TIMEOUT_MS = 45_000;
const DEFAULT_MINIMAX_CHAT_MODEL = "MiniMax-M2.7-highspeed";
const JSON_API_INSTRUCTION =
  "你是 JSON API。禁止输出 <think>、Markdown、解释、前后缀。输出必须以 { 开始，以 } 结束，只能返回一个合法 JSON 对象。";
const KNOWLEDGE_CHAT_JSON_INSTRUCTION =
  "你是 JSON API。禁止输出 <think>、解释、前后缀。输出必须以 { 开始，以 } 结束，只能返回一个合法 JSON 对象；answer 字段内部可以使用指定的轻量 Markdown。";

export async function organizeNotesWithMinimax({ config, requestId, date, language, notes, options }) {
  if (!config.minimaxApiKey) {
    throw httpError(500, "MINIMAX_NOT_CONFIGURED", "MiniMax chat provider is not configured.");
  }

  const endpoint = config.minimaxChatEndpoint || "https://api.minimaxi.com/v1/chat/completions";
  const model = config.minimaxChatModel || config.chatModel || DEFAULT_MINIMAX_CHAT_MODEL;
  const prompt = buildOrganizePrompt({ date, language, notes, options });

  const { payload, elapsedMs } = await requestChatCompletion({
    endpoint,
    apiKey: config.minimaxApiKey,
    model,
    messages: [
      {
        role: "system",
        content: `${JSON_API_INSTRUCTION}\n你是纳知的知识整理助手。只基于用户提供的原始笔记整理可复用知识条目。`
      },
      {
        role: "user",
        content: prompt
      }
    ],
    temperature: 0.3,
    maxCompletionTokens: 1536,
    jsonMode: config.minimaxJsonMode
  });

  const content = extractText(payload);
  let drafts = parseOrganizeDraftsFromText(content);
  let fallback = false;
  if (drafts.length === 0) {
    fallback = true;
    drafts = organizeNotesMock({ requestId, date, notes, options }).drafts.map((draft) => ({
      ...draft,
      confidence: Math.min(Number(draft.confidence) || 0.6, 0.62),
      needsReview: true,
      insight: draft.insight || "AI 返回格式异常，已生成可编辑草稿，请确认后入库。"
    }));
  }

  logProviderResult({
    route: "organize-notes",
    requestId,
    elapsedMs,
    itemCount: drafts.length,
    fallback
  });

  return {
    requestId,
    date,
    drafts,
    usage: normalizeUsage(payload?.usage)
  };
}

export async function knowledgeChatWithMinimax({
  config,
  requestId,
  question,
  resolvedQuestion = "",
  language,
  contexts,
  sessionMemory = "",
  previousCitationIds = []
}) {
  if (!config.minimaxApiKey) {
    throw httpError(500, "MINIMAX_NOT_CONFIGURED", "MiniMax chat provider is not configured.");
  }

  const endpoint = config.minimaxChatEndpoint || "https://api.minimaxi.com/v1/chat/completions";
  const model = config.minimaxChatModel || config.chatModel || DEFAULT_MINIMAX_CHAT_MODEL;
  const prompt = buildKnowledgeChatPrompt({
    question,
    resolvedQuestion,
    language,
    contexts,
    sessionMemory,
    previousCitationIds
  });

  const { payload, elapsedMs } = await requestChatCompletion({
    endpoint,
    apiKey: config.minimaxApiKey,
    model,
    messages: [
      {
        role: "system",
        content: `${KNOWLEDGE_CHAT_JSON_INSTRUCTION}\n你是纳知的个人知识库问答助手。只能基于用户提供的 contexts 回答。`
      },
      {
        role: "user",
        content: prompt
      }
    ],
    temperature: 0.2,
    maxCompletionTokens: 1024,
    jsonMode: config.minimaxJsonMode
  });

  const content = extractText(payload);
  const parsed = parseKnowledgeChatFromText(
    content,
    contexts.map((context) => context.id)
  );
  const citations = parsed.citations.length
    ? parsed.citations
    : fallbackCitations(parsed.answer, contexts);

  logProviderResult({
    route: "knowledge-chat",
    requestId,
    elapsedMs,
    itemCount: citations.length,
    fallback: parsed.citations.length === 0 && citations.length > 0
  });

  return {
    requestId,
    answer: parsed.answer,
    citations,
    updatedMemoryDigest: parsed.citations.length ? parsed.updatedMemoryDigest : "",
    usage: normalizeUsage(payload?.usage)
  };
}

export async function rewriteQuestionWithMinimax({
  config,
  requestId,
  currentQuestion,
  language,
  sessionMemory = "",
  lastUserQuestion = "",
  lastAssistantAnswerPreview = "",
  previousCitationTitles = []
}) {
  if (!config.minimaxApiKey) {
    throw httpError(500, "MINIMAX_NOT_CONFIGURED", "MiniMax chat provider is not configured.");
  }

  const endpoint = config.minimaxChatEndpoint || "https://api.minimaxi.com/v1/chat/completions";
  const model = config.minimaxChatModel || config.chatModel || DEFAULT_MINIMAX_CHAT_MODEL;
  const prompt = buildQuestionRewritePrompt({
    currentQuestion,
    language,
    sessionMemory,
    lastUserQuestion,
    lastAssistantAnswerPreview,
    previousCitationTitles
  });

  const { payload, elapsedMs } = await requestChatCompletion({
    endpoint,
    apiKey: config.minimaxApiKey,
    model,
    messages: [
      {
        role: "system",
        content: `${JSON_API_INSTRUCTION}\n你是纳知的追问识别与检索问题改写器。只返回 JSON。`
      },
      {
        role: "user",
        content: prompt
      }
    ],
    temperature: 0.1,
    maxCompletionTokens: 512,
    jsonMode: config.minimaxJsonMode
  });

  const content = extractText(payload);
  const parsed = parseQuestionRewriteFromText(content, currentQuestion);

  logProviderResult({
    route: "rewrite-question",
    requestId,
    elapsedMs,
    itemCount: parsed.isFollowUp ? 1 : 0,
    fallback: false
  });

  return {
    requestId,
    ...parsed,
    usage: normalizeUsage(payload?.usage)
  };
}

async function requestChatCompletion({ endpoint, apiKey, model, messages, temperature, maxCompletionTokens, jsonMode }) {
  const body = {
    model,
    messages,
    temperature,
    max_completion_tokens: maxCompletionTokens
  };

  if (jsonMode) {
    body.response_format = { type: "json_object" };
  }

  const firstAttempt = await postChatCompletion({ endpoint, apiKey, body });
  if (!jsonMode || firstAttempt.response.ok || !isUnsupportedResponseFormat(firstAttempt.payload)) {
    return ensureOk(firstAttempt);
  }

  const retryBody = { ...body };
  delete retryBody.response_format;
  return ensureOk(await postChatCompletion({ endpoint, apiKey, body: retryBody }));
}

async function postChatCompletion({ endpoint, apiKey, body }) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), CHAT_TIMEOUT_MS);
  const startedAt = Date.now();

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json"
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const payload = await response.json().catch(() => null);
    return {
      response,
      payload,
      elapsedMs: Date.now() - startedAt
    };
  } catch (error) {
    if (error?.name === "AbortError") {
      throw httpError(504, "MINIMAX_CHAT_TIMEOUT", "AI 请求超时，请稍后重试。");
    }
    throw httpError(502, "MINIMAX_CHAT_UNAVAILABLE", "AI 服务连接失败，请稍后重试。");
  } finally {
    clearTimeout(timeout);
  }
}

function ensureOk(result) {
  if (result.response.ok) {
    return {
      payload: result.payload,
      elapsedMs: result.elapsedMs
    };
  }

  throw httpError(
    result.response.status,
    "MINIMAX_CHAT_FAILED",
    result.payload?.message || result.payload?.error?.message || "MiniMax chat request failed."
  );
}

function isUnsupportedResponseFormat(payload) {
  const message = JSON.stringify(payload || "").toLowerCase();
  return (
    message.includes("response_format") &&
    (message.includes("unsupported") || message.includes("unknown") || message.includes("invalid"))
  );
}

function fallbackCitations(answer, contexts) {
  if (!answer || /没有足够|不足以|无法回答/.test(answer)) {
    return [];
  }
  return contexts.slice(0, 2).map((context) => ({
    contextId: context.id,
    quote: (context.summary || context.content || context.title || "").slice(0, 120),
    reason: "该条目是本次回答使用的本地检索上下文。"
  }));
}

function logProviderResult({ route, requestId, elapsedMs, itemCount, fallback }) {
  console.info(
    JSON.stringify({
      event: "minimax_chat_completed",
      route,
      requestId,
      elapsedMs,
      itemCount,
      fallback
    })
  );
}

function extractText(payload) {
  if (typeof payload?.choices?.[0]?.message?.content === "string") {
    return payload.choices[0].message.content;
  }
  if (typeof payload?.reply === "string") {
    return payload.reply;
  }
  if (typeof payload?.output?.text === "string") {
    return payload.output.text;
  }
  if (typeof payload?.text === "string") {
    return payload.text;
  }
  return "";
}

function normalizeUsage(usage) {
  return {
    inputTokens: usage?.prompt_tokens || usage?.input_tokens || 0,
    outputTokens: usage?.completion_tokens || usage?.output_tokens || 0
  };
}
