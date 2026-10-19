import { randomUUID } from "node:crypto";
import { httpError } from "../http.js";

const TASK_TTL_MS = 30 * 60 * 1000;
const MAX_TASKS = 100;
const tasks = new Map();

export function createTask(options) {
  return createOrGetTask(options).task;
}

export function createOrGetTask({ requestId, type, stage, progress, message, auth, payloadFingerprint }) {
  cleanupTasks();

  const existing = findTaskByRequestId(requestId, type, auth);
  if (existing) {
    if (existing.payloadFingerprint !== payloadFingerprint) {
      throw httpError(409, "IDEMPOTENCY_CONFLICT", "This request ID was already used with different input.");
    }
    return { task: publicTask(existing), created: false };
  }

  ensureTaskCapacity();

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
    error: null,
    payloadFingerprint,
    owner: normalizeTaskOwner(auth)
  };
  tasks.set(task.taskId, task);
  return { task: publicTask(task), created: true };
}

export function getTask(taskId, auth) {
  cleanupTasks();
  const task = tasks.get(taskId);
  if (task && !canAccessTask(task, auth)) {
    return null;
  }
  return task ? publicTask(task) : null;
}

export function getTaskByRequestId(requestId, type, auth) {
  cleanupTasks();
  const task = findTaskByRequestId(requestId, type, auth);
  return task ? publicTask(task) : null;
}

function findTaskByRequestId(requestId, type, auth) {
  return [...tasks.values()].find((task) =>
    task.requestId === requestId && task.type === type && canAccessTask(task, auth)
  );
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

function normalizeTaskOwner(auth) {
  return {
    authMode: auth?.mode || "unknown",
    userId: auth?.userId || null
  };
}

function canAccessTask(task, auth) {
  const requester = normalizeTaskOwner(auth);
  return task.owner.authMode === requester.authMode && task.owner.userId === requester.userId;
}

function cleanupTasks() {
  const cutoff = Date.now() - TASK_TTL_MS;
  for (const [taskId, task] of tasks) {
    if (task.status !== "RUNNING" && task.updatedAt < cutoff) {
      tasks.delete(taskId);
    }
  }
}

function ensureTaskCapacity() {
  if (tasks.size < MAX_TASKS) {
    return;
  }
  const removable = [...tasks.entries()]
    .filter(([, task]) => task.status !== "RUNNING")
    .sort((left, right) => left[1].updatedAt - right[1].updatedAt)
    .slice(0, tasks.size - MAX_TASKS + 1);
  for (const [taskId] of removable) {
    tasks.delete(taskId);
  }
  if (tasks.size >= MAX_TASKS) {
    throw httpError(429, "TASK_CAPACITY_REACHED", "Task capacity is reached. Please wait for running tasks to finish.");
  }
}
