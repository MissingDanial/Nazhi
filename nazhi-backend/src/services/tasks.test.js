import assert from "node:assert/strict";
import test from "node:test";
import { completeTask, createTask, failTask, getTask, getTaskByRequestId, updateTask } from "./tasks.js";

test("task lifecycle exposes status, progress, result and error", () => {
  const task = createTask({
    requestId: "req-1",
    type: "ORGANIZE_NOTES",
    stage: "ACCEPTED",
    progress: 5,
    message: "已提交 AI 整理任务"
  });

  assert.equal(task.status, "RUNNING");
  assert.equal(task.progress, 5);

  const updated = updateTask(task.taskId, {
    stage: "CALLING_MODEL",
    progress: 45,
    message: "AI 正在整理今日内容"
  });
  assert.equal(updated.stage, "CALLING_MODEL");
  assert.equal(getTask(task.taskId).message, "AI 正在整理今日内容");

  const completed = completeTask(task.taskId, { drafts: [{ id: "draft-1" }] });
  assert.equal(completed.status, "SUCCEEDED");
  assert.equal(completed.stage, "DONE");
  assert.equal(completed.result.drafts.length, 1);

  const failedTask = createTask({
    requestId: "req-2",
    type: "ORGANIZE_NOTES",
    stage: "ACCEPTED",
    progress: 5,
    message: "已提交 AI 整理任务"
  });
  const failed = failTask(failedTask.taskId, {
    code: "MINIMAX_CHAT_TIMEOUT",
    publicMessage: "AI 请求超时，请稍后重试。"
  });
  assert.equal(failed.status, "FAILED");
  assert.equal(failed.error.code, "MINIMAX_CHAT_TIMEOUT");
});

test("task status is isolated by user owner", () => {
  const task = createTask({
    requestId: "req-user-owned",
    type: "AUDIO_TRANSCRIPTION",
    stage: "ACCEPTED",
    progress: 5,
    message: "已提交录音转写任务",
    auth: {
      mode: "user_token",
      userId: "user-a"
    }
  });

  assert.equal(getTask(task.taskId, { mode: "user_token", userId: "user-a" })?.taskId, task.taskId);
  assert.equal(getTask(task.taskId, { mode: "user_token", userId: "user-b" }), null);
  assert.equal(getTask(task.taskId, { mode: "dev_token", userId: null }), null);
});

test("request identity and task access are isolated by both authentication mode and user", () => {
  const options = {
    requestId: "shared-request-id",
    type: "ORGANIZE_NOTES",
    stage: "ACCEPTED",
    progress: 5,
    message: "accepted"
  };
  const userA = { mode: "user_token", userId: "owner-a" };
  const userB = { mode: "user_token", userId: "owner-b" };
  const devOpen = { mode: "dev_open", userId: null };
  const devToken = { mode: "dev_token", userId: null };
  const tasks = [userA, userB, devOpen, devToken].map((auth) => createTask({ ...options, auth }));

  assert.equal(new Set(tasks.map((task) => task.taskId)).size, 4);
  assert.equal(getTask(tasks[0].taskId, { ...userA, mode: "dev_token" }), null);
  assert.equal(getTask(tasks[2].taskId, devToken), null);
  assert.equal(getTask(tasks[3].taskId, devOpen), null);
  assert.equal(getTask(tasks[0].taskId, userB), null);
  assert.equal(getTaskByRequestId(options.requestId, options.type, userA)?.taskId, tasks[0].taskId);
  assert.equal(getTaskByRequestId(options.requestId, options.type, { mode: "user_token", userId: "other" }), null);
  assert.equal(getTaskByRequestId(options.requestId, options.type, { ...userA, mode: "dev_token" }), null);
  assert.equal(getTaskByRequestId(options.requestId, "AUDIO_TRANSCRIPTION", userA), null);
  assert.equal(createTask({ ...options, auth: userA }).taskId, tasks[0].taskId);
  assert.notEqual(createTask({ ...options, auth: userA, type: "AUDIO_TRANSCRIPTION" }).taskId, tasks[0].taskId);
});

test("full capacity rejects new work, accepts retries and only evicts completed work", async () => {
  const store = await import("./tasks.js?capacity-test");
  const options = { type: "ORGANIZE_NOTES", stage: "ACCEPTED", progress: 5, message: "accepted" };
  const running = Array.from({ length: 100 }, (_, index) => store.createTask({ ...options, requestId: `capacity-${index}` }));

  assert.throws(() => store.createTask({ ...options, requestId: "capacity-overflow" }), {
    statusCode: 429, code: "TASK_CAPACITY_REACHED"
  });
  assert.equal(store.createTask({ ...options, requestId: "capacity-0" }).taskId, running[0].taskId);
  assert.ok(running.every((task) => store.getTask(task.taskId)?.status === "RUNNING"));
  store.completeTask(running[55].taskId, { drafts: [] });
  const accepted = store.createTask({ ...options, requestId: "capacity-after-completion" });
  assert.equal(accepted.status, "RUNNING");
  assert.equal(store.getTask(running[55].taskId), null);
  assert.ok(running.filter((_, index) => index !== 55).every((task) => store.getTask(task.taskId)?.status === "RUNNING"));
});

test("TTL expires terminal results while keeping running tasks available for recovery", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: 0 });
  const store = await import("./tasks.js?ttl-test");
  const options = { type: "ORGANIZE_NOTES", stage: "ACCEPTED", progress: 5, message: "accepted" };
  const running = store.createTask({ ...options, requestId: "ttl-running" });
  const succeeded = store.createTask({ ...options, requestId: "ttl-succeeded" });
  const failed = store.createTask({ ...options, requestId: "ttl-failed" });
  store.completeTask(succeeded.taskId, { drafts: [] });
  store.failTask(failed.taskId, { code: "TEST_FAILURE" });
  t.mock.timers.tick(30 * 60 * 1000 + 1);

  assert.equal(store.getTask(running.taskId)?.status, "RUNNING");
  assert.equal(store.getTaskByRequestId(running.requestId, running.type)?.taskId, running.taskId);
  assert.equal(store.createTask({ ...options, requestId: running.requestId }).taskId, running.taskId);
  assert.equal(store.getTask(succeeded.taskId), null);
  assert.equal(store.getTask(failed.taskId), null);
  assert.equal(store.getTaskByRequestId(succeeded.requestId, succeeded.type), null);
});
