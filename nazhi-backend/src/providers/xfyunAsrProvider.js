import crypto from "node:crypto";
import { httpError } from "../http.js";
import { buildAsrResult } from "../services/asrText.js";

const SHORT_FRAME_SIZE = 1280;
const SHORT_FRAME_INTERVAL_MS = 40;
const SHORT_TIMEOUT_MS = 75_000;
const LONG_POLL_INTERVAL_MS = 3_000;
const LONG_MAX_POLLS = 80;

export async function transcribeAudioWithXfyun({ config, audio, durationMs, requestId, onProgress }) {
  ensureXfyunConfigured(config);
  if (durationMs <= config.asrShortThresholdMs) {
    return transcribeShortAudio({ config, audio, durationMs, requestId, onProgress });
  }
  return transcribeLongAudio({ config, audio, durationMs, requestId, onProgress });
}

function ensureXfyunConfigured(config) {
  if (!config.xfyunAppId || !config.xfyunApiKey || !config.xfyunApiSecret) {
    throw httpError(400, "XFYUN_NOT_CONFIGURED", "讯飞 ASR 配置缺失，请检查 XFYUN_APP_ID / XFYUN_API_KEY / XFYUN_API_SECRET。");
  }
}

async function transcribeShortAudio({ config, audio, durationMs, requestId, onProgress }) {
  if (typeof WebSocket !== "function") {
    throw httpError(500, "WEBSOCKET_UNAVAILABLE", "当前 Node.js 运行时不支持 WebSocket，请升级 Node.js。");
  }
  const pcm = extractPcmFromWav(audio.data);
  if (pcm.length === 0) {
    throw httpError(400, "EMPTY_AUDIO", "音频为空，无法转写。");
  }

  onProgress?.({
    stage: "CALLING_ASR",
    progress: 30,
    message: "正在调用短音频识别"
  });

  const url = buildIatUrl(config);
  const parts = [];
  let resolved = false;

  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const timer = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        socket.close();
        reject(httpError(504, "ASR_TIMEOUT", "短音频转写超时。"));
      }
    }, SHORT_TIMEOUT_MS);

    socket.addEventListener("open", () => {
      void sendIatFrames(socket, {
        appId: config.xfyunAppId,
        pcm,
        onProgress
      }).catch((error) => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timer);
          socket.close();
          reject(error);
        }
      });
    });

    socket.addEventListener("message", (event) => {
      try {
        const payload = JSON.parse(String(event.data));
        if (payload.header?.code !== 0) {
          throw httpError(502, "XFYUN_IAT_FAILED", payload.header?.message || "讯飞短音频识别失败。");
        }
        const text = decodeIatResultText(payload.payload?.result?.text);
        if (text) {
          parts.push(text);
        }
        if (payload.header?.status === 2) {
          resolved = true;
          clearTimeout(timer);
          socket.close();
          resolve(buildAsrResult({
            requestId,
            text: parts.join(""),
            durationMs,
            provider: "xfyun",
            mode: "short_iat"
          }));
        }
      } catch (error) {
        if (!resolved) {
          resolved = true;
          clearTimeout(timer);
          socket.close();
          reject(error);
        }
      }
    });

    socket.addEventListener("error", (event) => {
      if (!resolved) {
        resolved = true;
        clearTimeout(timer);
        const detail = event?.error?.message || event?.message || "";
        reject(httpError(502, "XFYUN_IAT_FAILED", detail ? `讯飞短音频识别连接失败：${detail}` : "讯飞短音频识别连接失败。"));
      }
    });

    socket.addEventListener("close", (event) => {
      if (!resolved) {
        resolved = true;
        clearTimeout(timer);
        const reason = event?.reason ? `，原因：${event.reason}` : "";
        reject(httpError(502, "XFYUN_IAT_CLOSED", `讯飞短音频识别连接已关闭：${event?.code || "unknown"}${reason}`));
      }
    });
  });
}

async function sendIatFrames(socket, { appId, pcm, onProgress }) {
  let seq = 1;
  for (let offset = 0; offset < pcm.length; offset += SHORT_FRAME_SIZE) {
    const chunk = pcm.slice(offset, offset + SHORT_FRAME_SIZE);
    const isFirst = offset === 0;
    const frame = {
      header: {
        app_id: appId,
        status: isFirst ? 0 : 1
      },
      payload: {
        audio: {
          encoding: "raw",
          sample_rate: 16000,
          channels: 1,
          bit_depth: 16,
          seq,
          status: isFirst ? 0 : 1,
          audio: chunk.toString("base64")
        }
      }
    };
    if (isFirst) {
      frame.parameter = {
        iat: {
          domain: "slm",
          language: "zh_cn",
          accent: "mandarin",
          eos: 6000,
          result: {
            encoding: "utf8",
            compress: "raw",
            format: "json"
          }
        }
      };
    }
    socket.send(JSON.stringify(frame));
    seq += 1;
    if (seq % 30 === 0) {
      onProgress?.({
        stage: "CALLING_ASR",
        progress: Math.min(85, 30 + Math.round((offset / pcm.length) * 55)),
        message: "正在接收短音频识别结果"
      });
    }
    await sleep(SHORT_FRAME_INTERVAL_MS);
  }
  socket.send(JSON.stringify({
    header: {
      app_id: appId,
      status: 2
    },
    payload: {
      audio: {
        encoding: "raw",
        sample_rate: 16000,
        channels: 1,
        bit_depth: 16,
        seq,
        status: 2,
        audio: ""
      }
    }
  }));
}

async function transcribeLongAudio({ config, audio, durationMs, requestId, onProgress }) {
  onProgress?.({
    stage: "UPLOADING_ASR_AUDIO",
    progress: 20,
    message: "正在上传长音频"
  });
  const uploadUrl = await uploadLongAudio({ config, audio, requestId });

  onProgress?.({
    stage: "CALLING_ASR",
    progress: 45,
    message: "正在创建长音频转写任务"
  });
  const providerTaskId = await createLongTask({ config, requestId, uploadUrl, durationMs });

  for (let attempt = 0; attempt < LONG_MAX_POLLS; attempt += 1) {
    await sleep(LONG_POLL_INTERVAL_MS);
    onProgress?.({
      stage: "POLLING_ASR",
      progress: Math.min(95, 50 + attempt),
      message: "正在等待长音频转写结果"
    });
    const task = await queryLongTask({ config, providerTaskId });
    if (task.status === "done") {
      return buildAsrResult({
        requestId,
        text: task.text,
        durationMs,
        provider: "xfyun",
        mode: "speed_transcription"
      });
    }
  }

  throw httpError(504, "ASR_TIMEOUT", "长音频转写超时，请稍后重试。");
}

async function uploadLongAudio({ config, audio, requestId }) {
  const multipart = createMultipart({
    fields: {
      app_id: config.xfyunAppId,
      request_id: requestId
    },
    file: {
      fieldName: "data",
      filename: audio.filename || "audio.wav",
      contentType: audio.contentType || "application/octet-stream",
      data: audio.data
    }
  });
  const response = await signedJsonFetch(config.xfyunSpeedUploadEndpoint, {
    config,
    body: multipart.body,
    contentType: multipart.contentType
  });
  ensureProviderOk(response, "XFYUN_UPLOAD_FAILED", "讯飞长音频上传失败。");
  const url = response.data?.url;
  if (!url) {
    throw httpError(502, "XFYUN_UPLOAD_SHAPE_UNSUPPORTED", "讯飞长音频上传返回缺少文件地址。");
  }
  return url;
}

async function createLongTask({ config, requestId, uploadUrl, durationMs }) {
  const body = Buffer.from(JSON.stringify({
    common: {
      app_id: config.xfyunAppId
    },
    business: {
      request_id: requestId,
      language: "zh_cn",
      domain: "pro_ost_ed",
      accent: "mandarin",
      duration: Math.ceil(durationMs / 1000),
      smoothproc: true,
      colloqproc: false
    },
    data: {
      audio_url: uploadUrl,
      audio_src: "http",
      format: "audio/L16;rate=16000",
      encoding: "raw"
    }
  }));
  const response = await signedJsonFetch(config.xfyunSpeedCreateEndpoint, {
    config,
    body,
    contentType: "application/json"
  });
  ensureProviderOk(response, "XFYUN_CREATE_TASK_FAILED", "讯飞长音频转写任务创建失败。");
  const taskId = response.data?.task_id;
  if (!taskId) {
    throw httpError(502, "XFYUN_CREATE_TASK_SHAPE_UNSUPPORTED", "讯飞长音频转写返回缺少 task_id。");
  }
  return taskId;
}

async function queryLongTask({ config, providerTaskId }) {
  const body = Buffer.from(JSON.stringify({
    common: {
      app_id: config.xfyunAppId
    },
    business: {
      task_id: providerTaskId
    }
  }));
  const response = await signedJsonFetch(config.xfyunSpeedQueryEndpoint, {
    config,
    body,
    contentType: "application/json"
  });
  ensureProviderOk(response, "XFYUN_QUERY_TASK_FAILED", "讯飞长音频转写查询失败。");
  const status = String(response.data?.task_status || "");
  if (status === "3" || status === "4") {
    return {
      status: "done",
      text: extractSpeedTranscriptionText(response.data?.result)
    };
  }
  if (status === "1" || status === "2") {
    return { status: "running", text: "" };
  }
  throw httpError(502, "XFYUN_TASK_FAILED", `讯飞长音频转写状态异常：${status || "unknown"}`);
}

async function signedJsonFetch(endpoint, { config, body, contentType }) {
  const url = new URL(endpoint);
  const digest = `SHA-256=${crypto.createHash("sha256").update(body).digest("base64")}`;
  const date = new Date().toUTCString();
  const requestLine = `POST ${url.pathname} HTTP/1.1`;
  const signatureOrigin = `host: ${url.host}\ndate: ${date}\n${requestLine}\ndigest: ${digest}`;
  const signature = crypto
    .createHmac("sha256", config.xfyunApiSecret)
    .update(signatureOrigin)
    .digest("base64");
  const authorization =
    `api_key="${config.xfyunApiKey}", algorithm="hmac-sha256", headers="host date request-line digest", signature="${signature}"`;

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "content-type": contentType,
      "content-length": String(body.length),
      host: url.host,
      date,
      digest,
      authorization
    },
    body
  });
  const text = await response.text();
  if (!response.ok) {
    throw httpError(response.status, "XFYUN_HTTP_FAILED", text || "讯飞 ASR 请求失败。");
  }
  return JSON.parse(text);
}

function buildIatUrl(config) {
  const endpoint = new URL(config.xfyunIatEndpoint);
  const date = new Date().toUTCString();
  const requestLine = `GET ${endpoint.pathname} HTTP/1.1`;
  const signatureOrigin = `host: ${endpoint.host}\ndate: ${date}\n${requestLine}`;
  const signature = crypto
    .createHmac("sha256", config.xfyunApiSecret)
    .update(signatureOrigin)
    .digest("base64");
  const authorizationOrigin =
    `api_key="${config.xfyunApiKey}", algorithm="hmac-sha256", headers="host date request-line", signature="${signature}"`;
  endpoint.searchParams.set("authorization", Buffer.from(authorizationOrigin).toString("base64"));
  endpoint.searchParams.set("date", date);
  endpoint.searchParams.set("host", endpoint.host);
  return endpoint.toString();
}

function createMultipart({ fields, file }) {
  const boundary = `NazhiXfyunBoundary${crypto.randomUUID()}`;
  const chunks = [];
  for (const [name, value] of Object.entries(fields)) {
    chunks.push(Buffer.from(`--${boundary}\r\n`));
    chunks.push(Buffer.from(`Content-Disposition: form-data; name="${name}"\r\n\r\n`));
    chunks.push(Buffer.from(String(value)));
    chunks.push(Buffer.from("\r\n"));
  }
  chunks.push(Buffer.from(`--${boundary}\r\n`));
  chunks.push(Buffer.from(
    `Content-Disposition: form-data; name="${file.fieldName}"; filename="${file.filename}"\r\n`
  ));
  chunks.push(Buffer.from(`Content-Type: ${file.contentType}\r\n\r\n`));
  chunks.push(file.data);
  chunks.push(Buffer.from(`\r\n--${boundary}--\r\n`));
  return {
    body: Buffer.concat(chunks),
    contentType: `multipart/form-data; boundary=${boundary}`
  };
}

function ensureProviderOk(response, code, message) {
  if (response.code !== 0) {
    throw httpError(502, code, response.message || message);
  }
}

function extractPcmFromWav(buffer) {
  if (buffer.slice(0, 4).toString("ascii") !== "RIFF") {
    return buffer;
  }
  let offset = 12;
  while (offset + 8 <= buffer.length) {
    const chunkId = buffer.slice(offset, offset + 4).toString("ascii");
    const chunkSize = buffer.readUInt32LE(offset + 4);
    const dataStart = offset + 8;
    if (chunkId === "data") {
      return buffer.slice(dataStart, dataStart + chunkSize);
    }
    offset = dataStart + chunkSize;
  }
  return Buffer.alloc(0);
}

function decodeIatResultText(encoded) {
  if (!encoded) {
    return "";
  }
  const decoded = Buffer.from(encoded, "base64").toString("utf8");
  const payload = JSON.parse(decoded);
  return (payload.ws || [])
    .flatMap((item) => item.cw || [])
    .map((candidate) => candidate.w || "")
    .join("");
}

function extractSpeedTranscriptionText(result) {
  if (!result) {
    return "";
  }
  if (typeof result === "string") {
    const parsed = tryParseJson(result);
    return parsed ? extractSpeedTranscriptionText(parsed) : result;
  }
  const lattices = result.lattice || result.lattice2 || [];
  const parts = [];
  for (const lattice of lattices) {
    const rt = lattice?.json_1best?.st?.rt || [];
    const sentence = rt
      .flatMap((item) => item.ws || [])
      .flatMap((word) => word.cw || [])
      .map((candidate) => candidate.w || "")
      .join("");
    if (sentence.trim()) {
      parts.push(sentence.trim());
    }
  }
  return parts.join("\n");
}

function tryParseJson(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
