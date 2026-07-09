export function rewriteQuestionMock({
  requestId,
  currentQuestion,
  sessionMemory = "",
  lastUserQuestion = "",
  lastAssistantAnswerPreview = "",
  previousCitationTitles = []
}) {
  const question = normalizeText(currentQuestion).slice(0, 500);
  const memory = normalizeText(sessionMemory).slice(0, 240);
  const lastQuestion = normalizeText(lastUserQuestion).slice(0, 200);
  const lastAnswer = normalizeText(lastAssistantAnswerPreview).slice(0, 200);
  const titles = normalizeTitleList(previousCitationTitles);
  const hasPrior = Boolean(memory || lastQuestion || lastAnswer || titles.length);
  const followUp = hasPrior && looksLikeFollowUp(question);
  const source = [memory, lastQuestion, titles.join(" "), question].filter(Boolean).join(" ");

  return {
    requestId,
    isFollowUp: followUp,
    standaloneQuestion: followUp ? compactLength(source, 220) : question,
    retrievalQuery: followUp ? compactLength(source, 180) : question,
    shouldUsePreviousCitations: followUp && titles.length > 0,
    confidence: followUp ? 0.76 : 0.68
  };
}

export function buildQuestionRewritePrompt({
  currentQuestion,
  language = "zh-CN",
  sessionMemory = "",
  lastUserQuestion = "",
  lastAssistantAnswerPreview = "",
  previousCitationTitles = []
}) {
  const titles = normalizeTitleList(previousCitationTitles).slice(0, 5);
  return `
你是纳知的“追问识别与检索问题改写器”。你的任务不是回答问题，只能把用户当前问题改写为适合知识库向量检索的独立问题。

规则：
1. 只判断 currentQuestion 是否延续上一轮会话，不要回答 currentQuestion。
2. 如果 currentQuestion 是新的独立问题，isFollowUp=false，standaloneQuestion 和 retrievalQuery 都使用 currentQuestion。
3. 如果 currentQuestion 是追问、指代、展开、继续、举例、优化、总结或要求基于上一轮继续分析，isFollowUp=true。
4. standaloneQuestion 要补全指代，让它离开历史会话也能被理解。
5. retrievalQuery 用于向量检索，应保留核心名词、主题、动作和限制，避免客套话。
6. shouldUsePreviousCitations 只在当前问题明显依赖上一轮引用来源时为 true。
7. confidence 为 0 到 1；不确定时降低 confidence。
8. 使用 ${language}；只输出 JSON，不要 Markdown，不要解释。

输出格式：
{
  "isFollowUp": true,
  "standaloneQuestion": "补全后的独立问题",
  "retrievalQuery": "适合向量检索的短查询",
  "shouldUsePreviousCitations": true,
  "confidence": 0.82
}

currentQuestion:
${normalizeText(currentQuestion)}

sessionMemory:
${normalizeText(sessionMemory) || "无"}

lastUserQuestion:
${normalizeText(lastUserQuestion) || "无"}

lastAssistantAnswerPreview:
${normalizeText(lastAssistantAnswerPreview) || "无"}

previousCitationTitles:
${JSON.stringify(titles)}
`.trim();
}

export function parseQuestionRewriteFromText(text, currentQuestion) {
  const fallback = rewriteQuestionMock({
    requestId: "",
    currentQuestion,
    sessionMemory: "",
    lastUserQuestion: "",
    lastAssistantAnswerPreview: "",
    previousCitationTitles: []
  });

  const cleaned = String(text || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/```json/gi, "```")
    .trim();

  for (const jsonText of extractJsonCandidates(cleaned)) {
    try {
      return normalizeRewritePayload(JSON.parse(jsonText), currentQuestion);
    } catch {
      // Try next candidate.
    }
  }

  return fallback;
}

function normalizeRewritePayload(payload, currentQuestion) {
  const question = normalizeText(currentQuestion).slice(0, 500);
  const standaloneQuestion = normalizeText(payload?.standaloneQuestion).slice(0, 240) || question;
  const retrievalQuery = normalizeText(payload?.retrievalQuery).slice(0, 220) || standaloneQuestion;
  const confidence = clamp01(Number(payload?.confidence));
  return {
    isFollowUp: Boolean(payload?.isFollowUp),
    standaloneQuestion,
    retrievalQuery,
    shouldUsePreviousCitations: Boolean(payload?.shouldUsePreviousCitations),
    confidence: Number.isFinite(confidence) ? confidence : 0.5
  };
}

function extractJsonCandidates(text) {
  const candidates = [];
  const fenced = [...text.matchAll(/```([\s\S]*?)```/g)].map((match) => match[1].trim());
  candidates.push(...fenced);
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start >= 0 && end > start) {
    candidates.push(text.slice(start, end + 1));
  }
  candidates.push(text);
  return candidates.filter(Boolean);
}

function looksLikeFollowUp(question) {
  const normalized = normalizeText(question);
  if (!normalized) {
    return false;
  }
  const markers = [
    "这个",
    "刚才",
    "上面",
    "前面",
    "继续",
    "展开",
    "详细",
    "具体",
    "怎么",
    "如何",
    "例子",
    "应用",
    "建议",
    "优化",
    "方案",
    "步骤",
    "哪些",
    "为什么",
    "总结",
    "归纳",
    "分析",
    "这部分",
    "这一点",
    "它"
  ];
  return normalized.length <= 40 && markers.some((marker) => normalized.includes(marker));
}

function normalizeTitleList(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((item) => {
    if (typeof item === "string") {
      return normalizeText(item).slice(0, 80);
    }
    return normalizeText(item?.title || item?.summary || item?.id).slice(0, 80);
  }).filter(Boolean);
}

function normalizeText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function compactLength(text, maxLength) {
  const normalized = normalizeText(text);
  return normalized.length <= maxLength ? normalized : normalized.slice(0, maxLength);
}

function clamp01(value) {
  if (!Number.isFinite(value)) {
    return 0.5;
  }
  return Math.max(0, Math.min(1, value));
}
