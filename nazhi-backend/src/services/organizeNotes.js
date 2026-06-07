export function organizeNotesMock({ requestId, date, notes, options }) {
  const maxDrafts = clampNumber(options?.maxDrafts, 1, 20, 10);
  const drafts = notes.slice(0, maxDrafts).map((note, index) => {
    const content = String(note.content || "").trim();
    const firstLine = content.split(/\r?\n/).find((line) => line.trim()) || "未命名知识";
    return {
      id: `draft-${date}-${index + 1}`,
      title: note.title || firstLine.slice(0, 32),
      summary: firstLine.slice(0, 80),
      content,
      intentType: inferIntentType(content),
      tags: inferTags(content),
      sourceNoteIds: [note.id],
      evidenceQuotes: [firstLine.slice(0, 60)],
      insight: "",
      confidence: 0.72,
      needsReview: false
    };
  });

  return {
    requestId,
    date,
    drafts,
    usage: {
      inputTokens: roughTokenCount(notes.map((note) => note.content).join("\n")),
      outputTokens: roughTokenCount(JSON.stringify(drafts))
    }
  };
}

export function buildOrganizePrompt({ date, language, notes, options }) {
  const notePayload = notes.map((note) => ({
    id: note.id,
    title: note.title || "",
    sourceType: note.sourceType || "",
    createdAt: note.createdAt || null,
    content: note.content || ""
  }));

  return `
你是纳知的知识整理助手。你的任务不是创作新文章，而是把用户在 ${date} 保存的原始笔记整理成少量可复用的知识条目草稿。

整理规则：
1. 只基于用户提供的 notes 整理，不要引入外部事实。
2. 可以合并主题相近的 notes，但每个草稿必须保留 sourceNoteIds。
3. content 必须忠实表达原始内容，不要夸大、补造事实。
4. tags 使用 1-5 个中文短标签。
5. intentType 只能是 READ_LATER、QUOTABLE、INSPIRATION。
6. 如果加入推断或启发，必须放入 insight 字段，不要混入 content。
7. 如果信息不足或含义不明确，needsReview 设为 true，confidence 低于 0.7。
8. evidenceQuotes 放 0-3 条来自原文的短引用。
9. 尽量合并主题相近的 notes：${options?.mergeSimilar !== false ? "是" : "否"}。
10. 最多输出 ${options?.maxDrafts || 10} 条，语言为 ${language || "zh-CN"}。
11. 只输出 JSON，不要输出 Markdown，不要解释。

输出格式：
{
  "drafts": [
    {
      "id": "draft-id",
      "title": "标题",
      "summary": "一句话摘要",
      "content": "整理后的知识正文",
      "intentType": "READ_LATER",
      "tags": ["标签"],
      "sourceNoteIds": ["note-id"],
      "evidenceQuotes": ["短引用"],
      "insight": "可选推断",
      "confidence": 0.86,
      "needsReview": false
    }
  ]
}

原始 notes：
${JSON.stringify(notePayload, null, 2)}
`.trim();
}

export function parseOrganizeDraftsFromText(text) {
  const cleaned = String(text || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/```json/gi, "```")
    .trim();

  for (const jsonText of extractJsonCandidates(cleaned)) {
    try {
      const parsed = JSON.parse(jsonText);
      if (Array.isArray(parsed)) {
        return parsed;
      }
      if (Array.isArray(parsed.drafts)) {
        return parsed.drafts;
      }
      if (isDraftLike(parsed)) {
        return [parsed];
      }
    } catch {
      // Try the next candidate.
    }
  }

  return [];
}

function inferIntentType(content) {
  if (/想法|灵感|idea|启发/i.test(content)) {
    return "INSPIRATION";
  }
  if (/引用|金句|摘录|quote/i.test(content)) {
    return "QUOTABLE";
  }
  return "READ_LATER";
}

function inferTags(content) {
  const tags = [];
  if (/产品|用户|需求/.test(content)) tags.push("产品");
  if (/知识|笔记|学习/.test(content)) tags.push("知识管理");
  if (/AI|大模型|模型|embedding/i.test(content)) tags.push("AI");
  return tags.slice(0, 3);
}

function roughTokenCount(text) {
  return Math.ceil(String(text || "").length / 2);
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return fallback;
  }
  return Math.max(min, Math.min(max, number));
}

function isDraftLike(value) {
  return value && typeof value === "object" && typeof value.content === "string";
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

  const starts = [];
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] === "{" || text[index] === "[") {
      starts.push(index);
    }
  }

  for (const start of starts) {
    const closingChar = text[start] === "{" ? "}" : "]";
    for (let end = text.lastIndexOf(closingChar); end > start; end = text.lastIndexOf(closingChar, end - 1)) {
      candidates.push(text.slice(start, end + 1).trim());
    }
  }

  return candidates.length ? candidates : [text];
}
