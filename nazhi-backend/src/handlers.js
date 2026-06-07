import { randomUUID } from "node:crypto";
import { config } from "./config.js";
import { httpError } from "./http.js";
import { createMinimaxEmbeddings } from "./providers/minimaxEmbeddingProvider.js";
import { knowledgeChatWithMinimax, organizeNotesWithMinimax, rewriteQuestionWithMinimax } from "./providers/minimaxChatProvider.js";
import { transcribeAudioWithXfyun } from "./providers/xfyunAsrProvider.js";
import { embedMock } from "./services/mockEmbedding.js";
import { knowledgeChatMock, normalizeKnowledgeChatContexts } from "./services/knowledgeChat.js";
import { organizeNotesMock } from "./services/organizeNotes.js";
import { rewriteQuestionMock } from "./services/questionRewrite.js";
import { buildAsrResult } from "./services/asrText.js";
import { completeTask, createTask, failTask, getTask, updateTask } from "./services/tasks.js";

export async function handleEmbeddings(body) {
  validateEmbeddingRequest(body);

  const requestId = body.requestId || randomUUID();
  const model = body.model || config.embeddingModel;
  const input = body.input;

  if (config.embeddingProvider === "minimax") {
    return createMinimaxEmbeddings({ config, requestId, model, input });
  }

  if (config.embeddingProvider !== "mock") {
    throw httpError(400, "UNSUPPORTED_EMBEDDING_PROVIDER", "Unsupported embedding provider.");
  }

  const dimensions = config.embeddingDimensions;
  return {
    requestId,
    model,
    dimensions,
    items: input.map((item) => ({
      id: item.id,
      embedding: embedMock(item.text, dimensions),
      metadata: item.metadata || {}
    })),
    usage: {
      inputTokens: input.reduce((sum, item) => sum + Math.ceil(String(item.text || "").length / 2), 0)
    }
  };
}

export async function handleOrganizeNotes(body) {
  validateOrganizeRequest(body);

  const requestId = body.requestId || randomUUID();
  const date = body.date;
  const language = body.language || "zh-CN";
  const notes = body.notes;
  const options = body.options || {};

  return runOrganizeNotes({ requestId, date, language, notes, options });
}

export async function handleCreateOrganizeNotesJob(body) {
  validateOrganizeRequest(body);

  const requestId = body.requestId || randomUUID();
  const date = body.date;
  const language = body.language || "zh-CN";
  const notes = body.notes;
  const options = body.options || {};
  const task = createTask({
    requestId,
    type: "ORGANIZE_NOTES",
    stage: "ACCEPTED",
    progress: 5,
    message: "已提交 AI 整理任务"
  });

  runOrganizeNotesJob({
    taskId: task.taskId,
    requestId,
    date,
    language,
    notes,
    options
  });

  return task;
}

export function handleTaskStatus(taskId) {
  const task = getTask(taskId);
  if (!task) {
    throw httpError(404, "TASK_NOT_FOUND", "Task not found or expired.");
  }
  return task;
}

export async function handleCreateAudioTranscriptionJob(form) {
  const requestId = form.fields.requestId || randomUUID();
  const durationMs = Number(form.fields.durationMs || 0);
  const language = form.fields.language || "zh-CN";
  const source = form.fields.source || "unknown";
  const audio = form.files.audio;
  validateAudioTranscriptionRequest({ audio, durationMs });

  const task = createTask({
    requestId,
    type: "AUDIO_TRANSCRIPTION",
    stage: "ACCEPTED",
    progress: 5,
    message: "已提交录音转写任务"
  });

  runAudioTranscriptionJob({
    taskId: task.taskId,
    requestId,
    audio,
    durationMs,
    language,
    source
  });

  return task;
}

export function handleAudioTranscriptionTaskStatus(taskId) {
  const task = getTask(taskId);
  if (!task) {
    throw httpError(404, "TASK_NOT_FOUND", "Audio transcription task not found or expired.");
  }
  if (task.type !== "AUDIO_TRANSCRIPTION") {
    throw httpError(404, "TASK_NOT_FOUND", "Audio transcription task not found or expired.");
  }
  return task;
}

function runOrganizeNotesJob({ taskId, requestId, date, language, notes, options }) {
  void (async () => {
    try {
      updateTask(taskId, {
        stage: "PREPARING_NOTES",
        progress: 15,
        message: "正在准备今日待整理笔记"
      });
      updateTask(taskId, {
        stage: "CALLING_MODEL",
        progress: 45,
        message: "AI 正在整理今日内容"
      });
      const result = await runOrganizeNotes({ requestId, date, language, notes, options });
      updateTask(taskId, {
        stage: "PARSING_RESULT",
        progress: 90,
        message: "正在校验整理结果"
      });
      completeTask(taskId, result, `已生成 ${result.drafts.length} 条 AI 草稿`);
    } catch (error) {
      failTask(taskId, error);
    }
  })();
}

function runAudioTranscriptionJob({ taskId, requestId, audio, durationMs, language, source }) {
  void (async () => {
    try {
      updateTask(taskId, {
        stage: "PREPARING_AUDIO",
        progress: 12,
        message: "正在准备录音文件"
      });
      const result = await runAudioTranscription({
        requestId,
        audio,
        durationMs,
        language,
        source,
        onProgress: (progress) => updateTask(taskId, progress)
      });
      if (!result.text?.trim()) {
        throw httpError(502, "EMPTY_TRANSCRIPT", "转写结果为空。");
      }
      completeTask(taskId, result, "转写完成");
    } catch (error) {
      failTask(taskId, error);
    }
  })();
}

async function runAudioTranscription({ requestId, audio, durationMs, language, source, onProgress }) {
  if (config.asrProvider === "xfyun") {
    return transcribeAudioWithXfyun({
      config,
      audio,
      durationMs,
      requestId,
      language,
      source,
      onProgress
    });
  }

  if (config.asrProvider !== "mock") {
    throw httpError(400, "UNSUPPORTED_ASR_PROVIDER", "Unsupported ASR provider.");
  }

  onProgress?.({
    stage: "CALLING_ASR",
    progress: 60,
    message: "mock ASR 正在生成转写结果"
  });
  await new Promise((resolve) => setTimeout(resolve, 500));
  return buildAsrResult({
    requestId,
    text: `（模拟转写）已收到一段 ${Math.max(1, Math.round(durationMs / 1000))} 秒录音。请在后端配置 ASR_PROVIDER=xfyun 后启用真实转写。`,
    durationMs,
    provider: "mock",
    mode: durationMs <= config.asrShortThresholdMs ? "short_mock" : "long_mock"
  });
}

async function runOrganizeNotes({ requestId, date, language, notes, options }) {
  if (config.chatProvider === "minimax") {
    return organizeNotesWithMinimax({
      config,
      requestId,
      date,
      language,
      notes,
      options
    });
  }

  if (config.chatProvider !== "mock") {
    throw httpError(400, "UNSUPPORTED_CHAT_PROVIDER", "Unsupported chat provider.");
  }

  return organizeNotesMock({
    requestId,
    date,
    language,
    notes,
    options
  });
}

export async function handleKnowledgeChat(body) {
  validateKnowledgeChatRequest(body);

  const requestId = body.requestId || randomUUID();
  const question = String(body.question || "").trim();
  const resolvedQuestion = typeof body.resolvedQuestion === "string" ? body.resolvedQuestion.trim().slice(0, 300) : "";
  const language = body.language || "zh-CN";
  const contexts = normalizeKnowledgeChatContexts(body.contexts);
  const sessionMemory = typeof body.sessionMemory === "string" ? body.sessionMemory.trim().slice(0, 300) : "";
  const previousCitationIds = Array.isArray(body.previousCitationIds)
    ? body.previousCitationIds.map(String).filter(Boolean).slice(0, 5)
    : [];

  if (contexts.length === 0) {
    return {
      requestId,
      answer: "当前知识库中没有足够信息回答这个问题。",
      citations: [],
      updatedMemoryDigest: "",
      usage: {
        inputTokens: Math.ceil(question.length / 2),
        outputTokens: 20
      }
    };
  }

  if (config.chatProvider === "minimax") {
    return knowledgeChatWithMinimax({
      config,
      requestId,
      question,
      resolvedQuestion,
      language,
      contexts,
      sessionMemory,
      previousCitationIds
    });
  }

  if (config.chatProvider !== "mock") {
    throw httpError(400, "UNSUPPORTED_CHAT_PROVIDER", "Unsupported chat provider.");
  }

  return knowledgeChatMock({
    requestId,
    question,
    resolvedQuestion,
    language,
    contexts,
    sessionMemory,
    previousCitationIds
  });
}

export async function handleRewriteQuestion(body) {
  validateRewriteQuestionRequest(body);

  const requestId = body.requestId || randomUUID();
  const currentQuestion = String(body.currentQuestion || "").trim();
  const language = body.language || "zh-CN";
  const sessionMemory = typeof body.sessionMemory === "string" ? body.sessionMemory.trim().slice(0, 300) : "";
  const lastUserQuestion = typeof body.lastUserQuestion === "string" ? body.lastUserQuestion.trim().slice(0, 300) : "";
  const lastAssistantAnswerPreview =
    typeof body.lastAssistantAnswerPreview === "string" ? body.lastAssistantAnswerPreview.trim().slice(0, 400) : "";
  const previousCitationTitles = Array.isArray(body.previousCitationTitles)
    ? body.previousCitationTitles.map(String).filter(Boolean).slice(0, 5)
    : [];

  if (config.chatProvider === "minimax") {
    return rewriteQuestionWithMinimax({
      config,
      requestId,
      currentQuestion,
      language,
      sessionMemory,
      lastUserQuestion,
      lastAssistantAnswerPreview,
      previousCitationTitles
    });
  }

  if (config.chatProvider !== "mock") {
    throw httpError(400, "UNSUPPORTED_CHAT_PROVIDER", "Unsupported chat provider.");
  }

  return rewriteQuestionMock({
    requestId,
    currentQuestion,
    sessionMemory,
    lastUserQuestion,
    lastAssistantAnswerPreview,
    previousCitationTitles
  });
}

function validateEmbeddingRequest(body) {
  if (!Array.isArray(body.input) || body.input.length === 0) {
    throw httpError(400, "INVALID_INPUT", "`input` must be a non-empty array.");
  }
  for (const item of body.input) {
    if (!item?.id || typeof item.text !== "string") {
      throw httpError(400, "INVALID_INPUT_ITEM", "Each input item needs `id` and `text`.");
    }
  }
}

function validateOrganizeRequest(body) {
  if (!body.date || typeof body.date !== "string") {
    throw httpError(400, "INVALID_DATE", "`date` is required.");
  }
  if (!Array.isArray(body.notes) || body.notes.length === 0) {
    throw httpError(400, "INVALID_NOTES", "`notes` must be a non-empty array.");
  }
  for (const note of body.notes) {
    if (!note?.id || typeof note.content !== "string") {
      throw httpError(400, "INVALID_NOTE_ITEM", "Each note needs `id` and `content`.");
    }
  }
}

function validateKnowledgeChatRequest(body) {
  if (!body.question || typeof body.question !== "string") {
    throw httpError(400, "INVALID_QUESTION", "`question` is required.");
  }
  if (!Array.isArray(body.contexts)) {
    throw httpError(400, "INVALID_CONTEXTS", "`contexts` must be an array.");
  }
  if (body.contexts.length > 8) {
    throw httpError(400, "TOO_MANY_CONTEXTS", "`contexts` supports at most 8 items.");
  }
}

function validateRewriteQuestionRequest(body) {
  if (!body.currentQuestion || typeof body.currentQuestion !== "string") {
    throw httpError(400, "INVALID_QUESTION", "`currentQuestion` is required.");
  }
}

function validateAudioTranscriptionRequest({ audio, durationMs }) {
  if (!audio?.data || audio.data.length === 0) {
    throw httpError(400, "INVALID_AUDIO", "`audio` file is required.");
  }
  if (!Number.isFinite(durationMs) || durationMs <= 0) {
    throw httpError(400, "INVALID_DURATION", "`durationMs` must be a positive number.");
  }
  if (durationMs > config.asrMaxDurationMs) {
    throw httpError(400, "AUDIO_TOO_LONG", "单次录音最长支持 15 分钟。");
  }
}
