import assert from "node:assert/strict";
import test from "node:test";
import { transcribeAudioWithXfyun } from "./xfyunAsrProvider.js";

const config = {
  xfyunAppId: "test-app",
  xfyunApiKey: "test-key",
  xfyunApiSecret: "test-secret",
  xfyunIatEndpoint: "wss://example.test/iat",
  xfyunSpeedUploadEndpoint: "https://example.test/upload",
  xfyunSpeedCreateEndpoint: "https://example.test/create",
  xfyunSpeedQueryEndpoint: "https://example.test/query",
  asrShortThresholdMs: 58_000,
  asrRequestTimeoutMs: 100,
  asrTaskTimeoutMs: 10_000
};

for (const phase of ["upload", "create", "query"]) {
  for (const pending of ["request", "response body"]) {
    test(`long ASR times out a stalled ${phase} ${pending} and aborts its request`, async (t) => {
      fakeTime(t);
      let signal;
      t.mock.method(globalThis, "fetch", async (url, init) => {
        if (url.endsWith(`/${phase}`)) {
          signal = init.signal;
          if (pending === "request") {
            return new Promise(() => {});
          }
          return { ok: true, text: () => new Promise(() => {}) };
        }
        return providerResponse(url);
      });
      const outcome = observe(transcribeLong());
      await flush();
      if (phase === "query") {
        t.mock.timers.tick(3_000);
        await flush();
      }
      t.mock.timers.tick(100);
      await flush();

      assert.equal(outcome.error?.code, "ASR_REQUEST_TIMEOUT");
      assert.equal(outcome.error?.statusCode, 504);
      assert.equal(signal?.aborted, true);
    });
  }
}

test("long ASR bounds a stalled query by the remaining total task deadline", async (t) => {
  fakeTime(t);
  let signal;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    if (url.endsWith("/query")) {
      signal = init.signal;
      return new Promise(() => {});
    }
    return providerResponse(url);
  });
  const outcome = observe(transcribeLong({ asrRequestTimeoutMs: 10_000, asrTaskTimeoutMs: 3_050 }));
  await flush();
  t.mock.timers.tick(3_000);
  await flush();
  t.mock.timers.tick(50);
  await flush();

  assert.equal(outcome.error?.code, "ASR_TIMEOUT");
  assert.equal(signal?.aborted, true);
});

test("long ASR stops at the total deadline while waiting to poll", async (t) => {
  fakeTime(t);
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    calls.push(url);
    return providerResponse(url);
  });
  const outcome = observe(transcribeLong({ asrTaskTimeoutMs: 1_000 }));
  await flush();
  t.mock.timers.tick(1_000);
  await flush();

  assert.equal(outcome.error?.code, "ASR_TIMEOUT");
  assert.equal(calls.some((url) => url.endsWith("/query")), false);
});

test("long ASR succeeds and clears request timeout timers", async (t) => {
  fakeTime(t);
  const signals = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    signals.push(init.signal);
    return providerResponse(url);
  });
  const outcome = observe(transcribeLong());
  await flush();
  t.mock.timers.tick(3_000);
  await flush();
  assert.equal(outcome.value?.text, "转写完成");
  assert.equal(outcome.value?.mode, "speed_transcription");
  t.mock.timers.tick(10_000);
  await flush();

  assert.equal(signals.length, 3);
  assert.ok(signals.every((signal) => signal && !signal.aborted));
});

test("long ASR counts uploading and creating against the same total deadline", async (t) => {
  fakeTime(t);
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    calls.push(url);
    await new Promise((resolve) => setTimeout(resolve, 60));
    return providerResponse(url);
  });
  const outcome = observe(transcribeLong({ asrRequestTimeoutMs: 1_000, asrTaskTimeoutMs: 150 }));
  t.mock.timers.tick(60);
  await flush();
  t.mock.timers.tick(60);
  await flush();
  t.mock.timers.tick(30);
  await flush();

  assert.equal(outcome.error?.code, "ASR_TIMEOUT");
  assert.deepEqual(calls, [config.xfyunSpeedUploadEndpoint, config.xfyunSpeedCreateEndpoint]);
});

test("short ASR closes its socket and stops sending frames after a socket error", async (t) => {
  fakeTime(t);
  const socket = mockSocket(t);
  const outcome = observe(transcribeShort());
  socket.emit("open");
  assert.equal(socket.frames.length, 1);
  socket.emit("error", { message: "connection failed" });
  await flush();
  t.mock.timers.tick(100_000);
  await flush();

  assert.equal(outcome.error?.code, "XFYUN_IAT_FAILED");
  assert.equal(socket.closeCount, 1);
  assert.equal(socket.frames.length, 1);
  assert.equal(socket.listenerCount(), 0);
});

test("short ASR success stops pending frames and clears its timeout", async (t) => {
  fakeTime(t);
  const socket = mockSocket(t);
  const outcome = observe(transcribeShort());
  socket.emit("open");
  socket.emit("message", {
    data: JSON.stringify({
      header: { code: 0, status: 2 },
      payload: {
        result: { text: Buffer.from(JSON.stringify({ ws: [{ cw: [{ w: "转写完成" }] }] })).toString("base64") }
      }
    })
  });
  await flush();
  t.mock.timers.tick(100_000);
  await flush();

  assert.equal(outcome.value?.text, "转写完成");
  assert.equal(outcome.value?.mode, "short_iat");
  assert.equal(outcome.error, undefined);
  assert.equal(socket.closeCount, 1);
  assert.equal(socket.frames.length, 1);
  assert.equal(socket.listenerCount(), 0);
});

test("short ASR with a completed but empty transcript fails and cleans up", async (t) => {
  fakeTime(t);
  const socket = mockSocket(t);
  const outcome = observe(transcribeShort());
  socket.emit("message", { data: JSON.stringify({ header: { code: 0, status: 2 } }) });
  await flush();

  assert.equal(outcome.error?.code, "EMPTY_TRANSCRIPT");
  assert.equal(socket.closeCount, 1);
  assert.equal(socket.listenerCount(), 0);
});

test("short ASR retains its timeout and cleans up its socket and frame timers", async (t) => {
  fakeTime(t);
  const socket = mockSocket(t);
  const outcome = observe(transcribeShort());
  t.mock.timers.tick(75_000);
  await flush();

  assert.equal(outcome.error?.code, "ASR_TIMEOUT");
  assert.equal(socket.closeCount, 1);
  assert.equal(socket.listenerCount(), 0);
});

function fakeTime(t) {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 0 });
}

function transcribeLong(overrides = {}) {
  return transcribeAudioWithXfyun({
    config: { ...config, ...overrides },
    audio: { data: Buffer.from("test audio") },
    durationMs: 60_000,
    requestId: "test-long"
  });
}

function transcribeShort() {
  return transcribeAudioWithXfyun({
    config,
    audio: { data: Buffer.alloc(3_840) },
    durationMs: 120,
    requestId: "test-short"
  });
}

function providerResponse(url) {
  const data = url.endsWith("/upload")
    ? { url: "https://example.test/audio.wav" }
    : url.endsWith("/create")
      ? { task_id: "task-1" }
      : { task_status: "3", result: "转写完成" };
  return new Response(JSON.stringify({ code: 0, data }), { status: 200 });
}

function observe(promise) {
  const outcome = {};
  promise.then(
    (value) => { outcome.value = value; },
    (error) => { outcome.error = error; }
  );
  return outcome;
}

function flush() {
  return new Promise((resolve) => setImmediate(resolve));
}

function mockSocket(t) {
  const listeners = new Map();
  const socket = {
    frames: [],
    closeCount: 0,
    addEventListener(type, listener) {
      const entries = listeners.get(type) || new Set();
      entries.add(listener);
      listeners.set(type, entries);
    },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener); },
    emit(type, event = {}) {
      for (const listener of listeners.get(type) || []) {
        listener(event);
      }
    },
    send(frame) { this.frames.push(JSON.parse(frame)); },
    close() { this.closeCount += 1; this.emit("close"); },
    listenerCount() { return [...listeners.values()].reduce((total, entries) => total + entries.size, 0); }
  };
  t.mock.method(globalThis, "WebSocket", function () { return socket; });
  return socket;
}
