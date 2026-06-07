import fs from "node:fs";
import path from "node:path";

loadEnvFile(path.resolve(process.cwd(), ".env"));
loadEnvFile(path.resolve(process.cwd(), "kyAPI.txt"));
loadEnvFile(path.resolve(process.cwd(), "../kyAPI.txt"));

export const config = {
  port: readNumber("PORT", 8787),
  devToken: readString("NAZHI_DEV_TOKEN", ""),
  databaseUrl: readString("DATABASE_URL", ""),
  redisUrl: readString("REDIS_URL", ""),
  jwtSecret: readString("NAZHI_JWT_SECRET", readString("JWT_SECRET", "")),
  accessTokenTtlSeconds: readNumber("ACCESS_TOKEN_TTL_SECONDS", 15 * 60),
  refreshTokenTtlDays: readNumber("REFRESH_TOKEN_TTL_DAYS", 30),
  authLoginWindowSeconds: readNumber("AUTH_LOGIN_WINDOW_SECONDS", 15 * 60),
  authLoginMaxAttempts: readNumber("AUTH_LOGIN_MAX_ATTEMPTS", 8),
  authRegisterWindowSeconds: readNumber("AUTH_REGISTER_WINDOW_SECONDS", 60 * 60),
  authRegisterMaxAttempts: readNumber("AUTH_REGISTER_MAX_ATTEMPTS", 5),
  embeddingProvider: readString("EMBEDDING_PROVIDER", "mock"),
  embeddingModel: readString("EMBEDDING_MODEL", "embo-01"),
  embeddingDimensions: readNumber("EMBEDDING_DIMENSIONS", 1536),
  chatProvider: readString("CHAT_PROVIDER", "mock"),
  chatModel: readString("CHAT_MODEL", "MiniMax-M2.7-highspeed"),
  minimaxApiKey: readString("MINIMAX_API", readString("MINIMAX_API_KEY", "")),
  minimaxEmbeddingApiKey: readString(
    "MINIMAX_EMBEDDING_API",
    readString("MINIMAX_EMBEDDING_API_KEY", readString("MINIMAX_API", readString("MINIMAX_API_KEY", "")))
  ),
  minimaxGroupId: readString("MINIMAX_GROUP_ID", ""),
  minimaxEmbeddingEndpoint: readString("MINIMAX_EMBEDDING_ENDPOINT", "https://api.minimaxi.com/v1/embeddings"),
  minimaxEmbeddingModel: readString("MINIMAX_EMBEDDING_MODEL", "embo-01"),
  minimaxEmbeddingDim: readNumber("MINIMAX_EMBEDDING_DIM", 1536),
  minimaxChatEndpoint: readString("MINIMAX_CHAT_ENDPOINT", "https://api.minimaxi.com/v1/chat/completions"),
  minimaxChatModel: readString("MINIMAX_CHAT_MODEL", "MiniMax-M2.7-highspeed"),
  minimaxJsonMode: readBoolean("MINIMAX_JSON_MODE", false),
  asrProvider: readString("ASR_PROVIDER", "mock"),
  asrMaxDurationMs: readNumber("ASR_MAX_DURATION_MS", 15 * 60 * 1000),
  asrShortThresholdMs: readNumber("ASR_SHORT_THRESHOLD_MS", 58 * 1000),
  xfyunAppId: readString("XFYUN_APP_ID", readString("APPID", "")),
  xfyunApiKey: readString("XFYUN_API_KEY", readString("APIKey", "")),
  xfyunApiSecret: readString("XFYUN_API_SECRET", readString("APISecret", "")),
  xfyunIatEndpoint: readString("XFYUN_IAT_ENDPOINT", "wss://iat.xf-yun.com/v1"),
  xfyunSpeedUploadEndpoint: readString("XFYUN_SPEED_UPLOAD_ENDPOINT", "https://upload-ost-api.xfyun.cn/file/upload"),
  xfyunSpeedCreateEndpoint: readString("XFYUN_SPEED_CREATE_ENDPOINT", "https://ost-api.xfyun.cn/v2/ost/pro_create"),
  xfyunSpeedQueryEndpoint: readString("XFYUN_SPEED_QUERY_ENDPOINT", "https://ost-api.xfyun.cn/v2/ost/query")
};

function readString(name, fallback) {
  return process.env[name]?.trim() || fallback;
}

function readNumber(name, fallback) {
  const value = Number(process.env[name]);
  return Number.isFinite(value) ? value : fallback;
}

function readBoolean(name, fallback) {
  const value = process.env[name]?.trim().toLowerCase();
  if (!value) {
    return fallback;
  }
  return ["1", "true", "yes", "on"].includes(value);
}

function loadEnvFile(envPath) {
  if (!fs.existsSync(envPath)) {
    return;
  }

  const content = fs.readFileSync(envPath, "utf8");
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const separatorIndex = line.indexOf("=");
    if (separatorIndex === -1) {
      continue;
    }
    const key = line.slice(0, separatorIndex).trim();
    const value = line.slice(separatorIndex + 1).trim();
    if (!process.env[key]) {
      process.env[key] = stripQuotes(value);
    }
  }
}

function stripQuotes(value) {
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    return value.slice(1, -1);
  }
  return value;
}
