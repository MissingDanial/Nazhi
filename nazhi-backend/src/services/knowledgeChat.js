export function knowledgeChatMock({
  requestId,
  question,
  resolvedQuestion = "",
  contexts,
  sessionMemory = "",
  previousCitationIds = []
}) {
  const selectedContexts = contexts.slice(0, 3);
  const displayQuestion = resolvedQuestion || question;
  const answer = selectedContexts.length
    ? [
        `基于当前知识库，和“${displayQuestion}”最相关的内容主要有 ${selectedContexts.length} 条。`,
        ...selectedContexts.map((context, index) => {
          const title = context.title || `知识 ${index + 1}`;
          const summary = context.summary || context.content || "";
          return `${index + 1}. ${title}：${summary.slice(0, 120)}`;
        })
      ].join("\n")
    : "当前知识库中没有足够信息回答这个问题。";

  return {
    requestId,
    answer,
    citations: selectedContexts.map((context) => ({
      contextId: context.id,
      quote: (context.summary || context.content || "").slice(0, 80),
      reason: "该知识条目与问题语义相近。"
    })),
    updatedMemoryDigest: selectedContexts.length
      ? buildUpdatedMemoryDigest({ question: displayQuestion, contexts: selectedContexts, sessionMemory, previousCitationIds })
      : "",
    usage: {
      inputTokens: roughTokenCount(question + JSON.stringify(contexts)),
      outputTokens: roughTokenCount(answer)
    }
  };
}

export function buildKnowledgeChatPrompt({
  question,
  resolvedQuestion = "",
  language,
  contexts,
  sessionMemory = "",
  previousCitationIds = []
}) {
  const contextPayload = contexts.map((context) => ({
    id: context.id,
    title: context.title || "",
    summary: context.summary || "",
    content: context.content || "",
    tags: Array.isArray(context.tags) ? context.tags : [],
    sourceNoteIds: Array.isArray(context.sourceNoteIds) ? context.sourceNoteIds : [],
      score: Number.isFinite(Number(context.score)) ? Number(context.score) : 0
  }));
  const normalizedSessionMemory = String(sessionMemory || "").trim().slice(0, 300);
  const normalizedResolvedQuestion = String(resolvedQuestion || "").trim().slice(0, 300);
  const normalizedPreviousCitationIds = Array.isArray(previousCitationIds)
    ? previousCitationIds.map(String).filter(Boolean).slice(0, 5)
    : [];

  return `
你是纳知的个人知识库问答助手。请只基于用户提供的 contexts 回答问题。

回答规则：
1. 不要使用 contexts 之外的事实。
2. 如果 contexts 不足以回答，answer 必须明确说明“当前知识库中没有足够信息”。
3. contexts 数组非空时，不要说“没有上下文”或“未提供上下文”；只能判断这些 contexts 是否足以回答。
4. 不要编造引用，不要生成 contexts 中不存在的 contextId。
5. citations 只能引用 contexts[].id。
6. sessionMemory 只用于理解用户追问和指代关系，不能作为事实来源。
7. previousCitationIds 只表示用户可能在追问这些来源，不能作为独立事实来源。
8. 补全后的独立问题只用于理解追问和检索意图，不能作为事实来源。
9. quote 必须是对应 context 中能支撑回答的短句或摘要。
10. updatedMemoryDigest 是完整替换后的本会话摘要，最多 200 个中文字符，只总结主题、用户关注点和已基于 contexts 讨论过的结论。
11. 如果 contexts 不足、answer 表示知识不足、或 citations 为空，updatedMemoryDigest 必须为空字符串。
12. 回答使用 ${language || "zh-CN"}，保持简洁、可执行。
13. 只输出 JSON 对象，不要在 JSON 外输出 Markdown 或解释。
14. answer 字段内部使用轻量 Markdown，便于 Android 展示：
   - 可以使用 "# 标题"、"## 小标题"、"- 要点"、"1. 步骤" 和普通段落。
   - 优先给出一个简短标题，再用“核心结论 / 展开说明 / 可以继续追问”等小标题组织内容。
   - 如果 contexts 足以回答，必须在 answer 末尾输出 "## 可以继续追问"，下面给出 2-3 条 "- " 开头的追问建议。
   - 追问建议必须是用户可以直接继续提问的问题，不能是说明句。
   - 如果 contexts 不足以回答，不要输出“可以继续追问”段落。
   - 不要输出表格、代码块、HTML、图片链接或复杂嵌套列表。
   - 单段尽量不超过 3 行，避免长篇纯文本。

输出格式：
{
  "answer": "# 简短标题\n## 核心结论\n- 要点一\n- 要点二\n## 展开说明\n1. 步骤或说明一\n2. 步骤或说明二\n## 可以继续追问\n- 这个方案还有哪些风险？\n- 下一步应该怎么落地？",
  "citations": [
    {
      "contextId": "knowledge-id",
      "quote": "引用短句",
      "reason": "为什么引用这条知识"
    }
  ],
  "updatedMemoryDigest": "完整替换后的本会话摘要，最多200字"
}

用户问题：
${question}

补全后的独立问题：
${normalizedResolvedQuestion || "无"}

sessionMemory：
${normalizedSessionMemory || "无"}

previousCitationIds：
${JSON.stringify(normalizedPreviousCitationIds)}

contexts：
${JSON.stringify(contextPayload, null, 2)}
`.trim();
}

export function parseKnowledgeChatFromText(text, allowedContextIds) {
  const cleaned = String(text || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/```json/gi, "```")
    .trim();

  for (const jsonText of extractJsonCandidates(cleaned)) {
    try {
      const parsed = JSON.parse(jsonText);
      const answer = typeof parsed.answer === "string" ? parsed.answer.trim() : "";
      if (!answer) {
        continue;
      }
      return {
        answer,
        citations: normalizeCitations(parsed.citations, allowedContextIds),
        updatedMemoryDigest: normalizeMemoryDigest(parsed.updatedMemoryDigest)
      };
    } catch {
      // Try the next candidate.
    }
  }

  return {
    answer: cleaned || "当前知识库中没有足够信息回答这个问题。",
    citations: [],
    updatedMemoryDigest: ""
  };
}

export function normalizeKnowledgeChatContexts(contexts) {
  return contexts
    .map((context) => ({
      id: String(context.id || "").trim(),
      title: String(context.title || "").trim(),
      summary: String(context.summary || "").trim(),
      content: String(context.content || "").trim(),
      tags: Array.isArray(context.tags) ? context.tags.map(String).filter(Boolean).slice(0, 8) : [],
      sourceNoteIds: Array.isArray(context.sourceNoteIds)
        ? context.sourceNoteIds.map(String).filter(Boolean).slice(0, 20)
        : [],
      score: Number.isFinite(Number(context.score)) ? Number(context.score) : 0
    }))
    .filter((context) => context.id && (context.content || context.summary || context.title))
    .slice(0, 8);
}

function normalizeCitations(citations, allowedContextIds) {
  if (!Array.isArray(citations)) {
    return [];
  }
  const allowed = new Set(allowedContextIds);
  return citations
    .map((citation) => ({
      contextId: String(citation?.contextId || "").trim(),
      quote: String(citation?.quote || "").trim(),
      reason: String(citation?.reason || "").trim()
    }))
    .filter((citation) => allowed.has(citation.contextId))
    .slice(0, 5);
}

function normalizeMemoryDigest(value) {
  if (typeof value !== "string") {
    return "";
  }
  return value.trim().replace(/\s+/g, " ").slice(0, 200);
}

function buildUpdatedMemoryDigest({ question, contexts, sessionMemory }) {
  const topic = contexts
    .map((context) => context.title || context.summary || context.content)
    .filter(Boolean)
    .slice(0, 2)
    .join("；")
    .slice(0, 80);
  const previous = String(sessionMemory || "").trim();
  const digest = [
    previous ? `已有主题：${previous.slice(0, 70)}` : "",
    `当前关注：${String(question || "").slice(0, 60)}`,
    topic ? `已讨论依据：${topic}` : ""
  ]
    .filter(Boolean)
    .join("。");
  return digest.slice(0, 200);
}

function roughTokenCount(text) {
  return Math.ceil(String(text || "").length / 2);
}

function extractJsonCandidates(text) {
  const candidates = [];
  const fencedMatches = text.matchAll(/```([\s\S]*?)```/g);
  for (const match of fencedMatches) {
    const body = match[1].trim();
    if (body.startsWith("{") || body.startsWith("[")) {
      candidates.push(body);
    }
  }

  const firstObject = text.indexOf("{");
  const lastObject = text.lastIndexOf("}");
  if (firstObject >= 0 && lastObject > firstObject) {
    candidates.push(text.slice(firstObject, lastObject + 1).trim());
  }

  return candidates.length ? candidates : [text];
}
