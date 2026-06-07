import { randomUUID } from "node:crypto";

const TASK_TTL_MS = 30 * 60 * 1000;
const MAX_TASKS = 100;
const tasks = new Map();

export function createTask({ requestId, type, stage, progress, message }) {
  cleanupTasks();

  const now = Date.now();
  const task = {
    taskId: `task-${randomUUID()}`,
    requestId,
    type,
    status: "RUNNING",
    stage,
    progress,
    message,
    createdAt: now,
    updatedAt: now,
    result: null,
    error: null
  };
  tasks.set(task.taskId, task);
  trimTasks();
  return publicTask(task);
}

export function getTask(taskId) {
  cleanupTasks();
  const task = tasks.get(taskId);
  return task ? publicTask(task) : null;
}

export function updateTask(taskId, patch) {
  const task = tasks.get(taskId);
  if (!task || task.status !== "RUNNING") {
    return null;
  }
  Object.assign(task, patch, { updatedAt: Date.now() });
  return publicTask(task);
}

export function completeTask(taskId, result, message = "任务已完成") {
  const task = tasks.get(taskId);
  if (!task) {
    return null;
  }
  Object.assign(task, {
    status: "SUCCEEDED",
    stage: "DONE",
    progress: 100,
    message,
    result,
    error: null,
    updatedAt: Date.now()
  });
  return publicTask(task);
}

export function failTask(taskId, error) {
  const task = tasks.get(taskId);
  if (!task) {
    return null;
  }
  Object.assign(task, {
    status: "FAILED",
    stage: "FAILED",
    progress: 100,
    message: error?.publicMessage || error?.message || "任务失败",
    result: null,
    error: {
      code: error?.code || "TASK_FAILED",
      message: error?.publicMessage || error?.message || "任务失败"
    },
    updatedAt: Date.now()
  });
  return publicTask(task);
}

function publicTask(task) {
  const payload = {
    taskId: task.taskId,
    requestId: task.requestId,
    type: task.type,
    status: task.status,
    stage: task.stage,
    progress: task.progress,
    message: task.message,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt
  };
  if (task.result) {
    payload.result = task.result;
  }
  if (task.error) {
    payload.error = task.error;
  }
  return payload;
}

function cleanupTasks() {
  const cutoff = Date.now() - TASK_TTL_MS;
  for (const [taskId, task] of tasks) {
    if (task.updatedAt < cutoff) {
      tasks.delete(taskId);
    }
  }
}

function trimTasks() {
  if (tasks.size <= MAX_TASKS) {
    return;
  }
  const removable = [...tasks.entries()]
    .sort((left, right) => left[1].updatedAt - right[1].updatedAt)
    .slice(0, tasks.size - MAX_TASKS);
  for (const [taskId] of removable) {
    tasks.delete(taskId);
  }
}
