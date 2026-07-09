import { httpError } from "../http.js";

export function normalizeAsrTranscript(text) {
  return String(text || "")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
    .replace(/\r\n?/g, "\n")
    .replace(/[\u200B-\u200D\uFEFF]/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n[ \t]+/g, "\n")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/(?<=[\u4e00-\u9fff])[ \t]+(?=[\u4e00-\u9fff])/g, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function buildAsrResult({
  requestId,
  text,
  durationMs,
  provider,
  mode,
  rawText = text
}) {
  const normalizedText = normalizeAsrTranscript(text);
  if (!normalizedText) {
    throw httpError(502, "EMPTY_TRANSCRIPT", "转写结果为空。");
  }
  return {
    requestId,
    text: normalizedText,
    rawText: normalizeAsrTranscript(rawText),
    durationMs,
    provider,
    mode
  };
}
