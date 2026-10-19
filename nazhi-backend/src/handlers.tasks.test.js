import assert from "node:assert/strict";
import test from "node:test";
import { config } from "./config.js";
import { handleCreateAudioTranscriptionJob, handleCreateOrganizeNotesJob, handleTaskStatus } from "./handlers.js";
import { createTask, failTask } from "./services/tasks.js";

const owner = { mode: "user_token", userId: "task-test-owner" };

test("retrying an organize submission runs the provider once and returns the original result", async (t) => {
  useTaskConfig(t);
  let release;
  const pending = new Promise((resolve) => { release = resolve; });
  t.after(() => release());
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    await pending;
    return organizeResponse();
  });
  const body = organizeBody("retry-running-organize");

  const original = await handleCreateOrganizeNotesJob(body, owner);
  const repeated = await handleCreateOrganizeNotesJob(body, owner);

  assert.equal(repeated.taskId, original.taskId);
  assert.equal(fetchMock.mock.callCount(), 1);
  release();
  const completed = await waitForTask(original.taskId, owner);
  const finalRetry = await handleCreateOrganizeNotesJob(body, owner);
  assert.equal(completed.status, "SUCCEEDED");
  assert.deepEqual(finalRetry, completed);
  assert.equal(fetchMock.mock.callCount(), 1);
  assert.equal("owner" in finalRetry, false);
  assert.equal("payloadFingerprint" in finalRetry, false);
});

test("an organize request ID cannot be reused for changed input and key order does not cause a conflict", async (t) => {
  useTaskConfig(t);
  const fetchMock = t.mock.method(globalThis, "fetch", async () => organizeResponse());
  const body = organizeBody("conflicting-organize-input");
  const original = await handleCreateOrganizeNotesJob(body, owner);
  const reordered = {
    ...body,
    notes: body.notes.map(({ id, title, content }) => ({ content, title, id })),
    options: { mergeSimilar: true, maxDrafts: 3 },
    language: "zh-CN"
  };
  assert.equal((await handleCreateOrganizeNotesJob(reordered, owner)).taskId, original.taskId);

  for (const changed of [
    { ...body, date: "2026-10-14" },
    { ...body, language: "en-US" },
    { ...body, options: { ...body.options, maxDrafts: 1 } },
    { ...body, notes: [{ ...body.notes[0], content: "不同内容" }] }
  ]) {
    await assert.rejects(() => handleCreateOrganizeNotesJob(changed, owner), {
      statusCode: 409, code: "IDEMPOTENCY_CONFLICT"
    });
  }
  await waitForTask(original.taskId, owner);
  assert.equal(fetchMock.mock.callCount(), 1);
});

test("audio retries reuse one provider transcription and changed audio or options conflict", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: Date.now() });
  useTaskConfig(t, {
    asrProvider: "xfyun",
    xfyunAppId: "test-app", xfyunApiKey: "test-key", xfyunApiSecret: "test-secret",
    xfyunSpeedUploadEndpoint: "https://example.test/upload",
    xfyunSpeedCreateEndpoint: "https://example.test/create",
    xfyunSpeedQueryEndpoint: "https://example.test/query",
    asrRequestTimeoutMs: 1000, asrTaskTimeoutMs: 10_000, asrShortThresholdMs: 58_000
  });
  const fetchMock = t.mock.method(globalThis, "fetch", async (url) => {
    const data = url.endsWith("/upload") ? { url: "https://example.test/audio.wav" }
      : url.endsWith("/create") ? { task_id: "provider-task-1" }
        : { task_status: "3", result: "转写完成" };
    return new Response(JSON.stringify({ code: 0, data }));
  });
  const form = audioForm("retry-audio-provider");
  const original = await handleCreateAudioTranscriptionJob(form, owner);
  const duplicate = await handleCreateAudioTranscriptionJob({
    ...form, files: { audio: { ...form.files.audio, filename: "renamed.wav" } }
  }, owner);
  assert.equal(duplicate.taskId, original.taskId);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(fetchMock.mock.callCount(), 2);
  for (const changed of [
    { ...form, fields: { ...form.fields, durationMs: "60001" } },
    { ...form, fields: { ...form.fields, language: "en-US" } },
    { ...form, fields: { ...form.fields, source: "overlay" } },
    { ...form, files: { audio: { ...form.files.audio, data: Buffer.from("other audio") } } }
  ]) {
    await assert.rejects(() => handleCreateAudioTranscriptionJob(changed, owner), {
      statusCode: 409, code: "IDEMPOTENCY_CONFLICT"
    });
  }
  t.mock.timers.tick(3000);
  const completed = await waitForTask(original.taskId, owner);
  assert.equal(completed.status, "SUCCEEDED");
  assert.equal(completed.result.text, "转写完成");
  assert.deepEqual(await handleCreateAudioTranscriptionJob(form, owner), completed);
  assert.equal(fetchMock.mock.callCount(), 3);
});

test("invalid task request IDs are rejected before contacting providers", async (t) => {
  useTaskConfig(t);
  const fetchMock = t.mock.method(globalThis, "fetch", async () => organizeResponse());
  for (const requestId of ["", "   ", null, 7, {}, "x".repeat(201)]) {
    await assert.rejects(() => handleCreateOrganizeNotesJob(organizeBody(requestId), owner), {
      statusCode: 400, code: "INVALID_REQUEST_ID"
    });
    await assert.rejects(() => handleCreateAudioTranscriptionJob(audioForm(requestId), owner), {
      statusCode: 400, code: "INVALID_REQUEST_ID"
    });
  }
  assert.equal(fetchMock.mock.callCount(), 0);
});

test("full running capacity rejects organize and audio submissions without provider calls", async (t) => {
  useTaskConfig(t, { asrProvider: "xfyun" });
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    assert.fail("Rejected submissions must not contact a provider.");
  });
  const running = Array.from({ length: 100 }, (_, index) => createTask({
    requestId: `handler-capacity-${index}`, type: "ORGANIZE_NOTES",
    stage: "ACCEPTED", progress: 5, message: "accepted", auth: owner
  }));
  t.after(() => running.forEach((task) => failTask(task.taskId, { code: "TEST_CLEANUP" })));

  await assert.rejects(() => handleCreateOrganizeNotesJob(organizeBody("handler-overflow-organize"), owner), {
    statusCode: 429, code: "TASK_CAPACITY_REACHED"
  });
  await assert.rejects(() => handleCreateAudioTranscriptionJob(audioForm("handler-overflow-audio"), owner), {
    statusCode: 429, code: "TASK_CAPACITY_REACHED"
  });
  assert.equal(fetchMock.mock.callCount(), 0);
  assert.ok(running.every((task) => handleTaskStatus(task.taskId, owner).status === "RUNNING"));
});

test("replaying a failed request returns its failure and explicit new request ID starts a new attempt", async (t) => {
  useTaskConfig(t);
  const fetchMock = t.mock.method(globalThis, "fetch", async () => new Response("provider unavailable", { status: 503 }));
  const body = organizeBody("failed-organize-attempt");
  const original = await handleCreateOrganizeNotesJob(body, owner);
  const failed = await waitForTask(original.taskId, owner);

  assert.equal(failed.status, "FAILED");
  assert.deepEqual(await handleCreateOrganizeNotesJob(body, owner), failed);
  assert.equal(fetchMock.mock.callCount(), 1);
  const explicitRetry = await handleCreateOrganizeNotesJob({ ...body, requestId: "failed-organize-new-attempt" }, owner);
  assert.notEqual(explicitRetry.taskId, original.taskId);
  assert.equal((await waitForTask(explicitRetry.taskId, owner)).status, "FAILED");
  assert.equal(fetchMock.mock.callCount(), 2);
});

function audioForm(requestId) {
  return {
    fields: { requestId, durationMs: "60000", language: "zh-CN", source: "app" },
    files: { audio: { filename: "test.wav", contentType: "audio/wav", data: Buffer.from("test audio") } }
  };
}

function useTaskConfig(t, overrides = {}) {
  const saved = { ...config };
  Object.assign(config, {
    chatProvider: "minimax",
    minimaxApiKey: "test-key",
    minimaxChatEndpoint: "https://example.test/chat",
    minimaxChatModel: "test-model",
    minimaxJsonMode: false,
    ...overrides
  });
  t.after(() => Object.assign(config, saved));
}

function organizeBody(requestId) {
  return {
    requestId,
    date: "2026-10-13",
    notes: [{ id: "note-1", title: "测试", content: "任务恢复需要保存稳定请求 ID。" }],
    options: { maxDrafts: 3, mergeSimilar: true }
  };
}

function organizeResponse() {
  return new Response(JSON.stringify({
    choices: [{ message: { content: JSON.stringify({ drafts: [{
      title: "任务恢复", content: "任务恢复需要保存稳定请求 ID。", sourceNoteIds: ["note-1"]
    }] }) } }],
    usage: { prompt_tokens: 12, completion_tokens: 8 }
  }));
}

async function waitForTask(taskId, auth) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const task = handleTaskStatus(taskId, auth);
    if (task.status !== "RUNNING") return task;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail("Task did not finish after provider response.");
}
