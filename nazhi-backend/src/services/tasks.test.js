import assert from "node:assert/strict";
import test from "node:test";
import { completeTask, createTask, failTask, getTask, updateTask } from "./tasks.js";

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
