import assert from "node:assert/strict";
import test from "node:test";
import { buildAsrResult, normalizeAsrTranscript } from "./asrText.js";

test("normalizeAsrTranscript removes invisible chars and normalizes whitespace", () => {
  assert.equal(
    normalizeAsrTranscript("  今 天\u200B  学到\r\n  一个  方法。\n\n\n下一段\t\t内容。  "),
    "今天学到\n一个方法。\n\n下一段内容。"
  );
});

test("buildAsrResult returns unified ASR payload", () => {
  const result = buildAsrResult({
    requestId: "req-1",
    text: "  系统 音频\n\n转写完成  ",
    durationMs: 12_000,
    provider: "xfyun",
    mode: "short_iat"
  });

  assert.equal(result.requestId, "req-1");
  assert.equal(result.text, "系统音频\n\n转写完成");
  assert.equal(result.rawText, "系统音频\n\n转写完成");
  assert.equal(result.durationMs, 12_000);
  assert.equal(result.provider, "xfyun");
  assert.equal(result.mode, "short_iat");
});

test("buildAsrResult rejects empty transcript", () => {
  assert.throws(
    () => buildAsrResult({
      requestId: "req-2",
      text: " \u200B \n ",
      durationMs: 1000,
      provider: "xfyun",
      mode: "short_iat"
    }),
    /转写结果为空/
  );
});
