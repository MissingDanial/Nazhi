import assert from "node:assert/strict";
import test from "node:test";
import { config } from "./config.js";
import { route } from "./router.js";
import { completeTask, createTask } from "./services/tasks.js";

test("request lookup returns the original result and preserves generic and audio status routes", async (t) => {
  useRouteConfig(t);
  const auth = { mode: "dev_token", userId: null };
  const organize = createTask({
    requestId: "lookup-request /?中文",
    type: "ORGANIZE_NOTES", stage: "ACCEPTED", progress: 5, message: "accepted", auth
  });
  const result = { requestId: organize.requestId, drafts: [{ id: "draft-1", content: "已完成" }] };
  completeTask(organize.taskId, result);
  const audio = createTask({
    requestId: "lookup-audio", type: "AUDIO_TRANSCRIPTION",
    stage: "ACCEPTED", progress: 5, message: "accepted", auth
  });

  const lookup = await requestRoute(`/v1/tasks/by-request/${encodeURIComponent(organize.requestId)}?type=ORGANIZE_NOTES`);
  assert.equal(lookup.status, 200);
  assert.equal(lookup.body.taskId, organize.taskId);
  assert.deepEqual(lookup.body.result, result);
  assert.equal("owner" in lookup.body, false);
  const generic = await requestRoute(`/v1/tasks/${organize.taskId}`);
  assert.deepEqual(generic.body, lookup.body);
  const audioStatus = await requestRoute(`/v1/audio-transcriptions/jobs/${audio.taskId}`);
  assert.equal(audioStatus.body.taskId, audio.taskId);
  const audioLookup = await requestRoute("/v1/tasks/by-request/lookup-audio?type=AUDIO_TRANSCRIPTION");
  assert.equal(audioLookup.body.taskId, audio.taskId);
});

test("request lookup validates task type, requires authentication and hides other owners", async (t) => {
  useRouteConfig(t);
  const task = createTask({
    requestId: "restricted-lookup", type: "ORGANIZE_NOTES",
    stage: "ACCEPTED", progress: 5, message: "accepted", auth: { mode: "dev_token", userId: null }
  });
  for (const path of [
    "/v1/tasks/by-request/no-such-task?type=ORGANIZE_NOTES",
    `/v1/tasks/by-request/${task.requestId}?type=AUDIO_TRANSCRIPTION`
  ]) {
    await assert.rejects(() => requestRoute(path), { statusCode: 404, code: "TASK_NOT_FOUND" });
  }
  for (const type of ["", "UNKNOWN"]) {
    await assert.rejects(() => requestRoute(`/v1/tasks/by-request/${task.requestId}?type=${type}`), {
      statusCode: 400, code: "INVALID_TASK_TYPE"
    });
  }
  await assert.rejects(() => requestRoute(`/v1/tasks/by-request/${task.requestId}?type=ORGANIZE_NOTES`, ""), {
    statusCode: 401, code: "UNAUTHORIZED"
  });
  config.devToken = "";
  await assert.rejects(() => requestRoute(`/v1/tasks/by-request/${task.requestId}?type=ORGANIZE_NOTES`, ""), {
    statusCode: 404, code: "TASK_NOT_FOUND"
  });
});

function useRouteConfig(t) {
  const saved = { ...config };
  Object.assign(config, { devToken: "test-token", databaseUrl: "", redisUrl: "" });
  t.after(() => Object.assign(config, saved));
}

async function requestRoute(url, authorization = "Bearer test-token") {
  const output = {};
  await route({ method: "GET", url, headers: { host: "localhost", authorization } }, {
    writeHead(status) { output.status = status; },
    end(json) { output.body = JSON.parse(json); }
  });
  return output;
}
