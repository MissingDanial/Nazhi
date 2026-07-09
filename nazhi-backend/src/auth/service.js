import { config } from "../config.js";
import { incrementWithExpiry } from "../db/redis.js";
import { httpError } from "../http.js";
import { hashPassword, validatePassword, verifyPassword } from "./password.js";
import {
  createLoginEvent,
  createRefreshTokenRecord,
  createUser,
  findRefreshTokenByHash,
  findUserByEmail,
  findUserById,
  normalizeEmail,
  revokeAllUserRefreshTokens,
  revokeRefreshToken,
  toPublicUser,
  updateUserLastLogin,
  updateUserPassword
} from "./repository.js";
import {
  createRefreshToken,
  extractBearerToken,
  hashRefreshToken,
  signAccessToken,
  verifyAccessToken
} from "./tokens.js";

export async function registerUser(body, meta) {
  await enforceRateLimit(`rate_limit:auth:register:${meta.ip}`, config.authRegisterMaxAttempts, config.authRegisterWindowSeconds);
  const email = normalizeEmail(body.email);
  const username = normalizeUsername(body.username);
  validateEmail(email);
  validatePassword(body.password);

  const existing = await findUserByEmail(email);
  if (existing) {
    throw httpError(409, "EMAIL_ALREADY_REGISTERED", "Email is already registered.");
  }

  const passwordHash = await hashPassword(body.password);
  const user = await createUser({ email, username, passwordHash });
  await createLoginEvent({ ...meta, userId: user.id, email, success: true, reason: "register" });
  return createSessionResponse(user, meta.deviceName);
}

export async function loginUser(body, meta) {
  await enforceRateLimit(`rate_limit:auth:login:${meta.ip}`, config.authLoginMaxAttempts, config.authLoginWindowSeconds);
  const email = normalizeEmail(body.email);
  validateEmail(email);
  const user = await findUserByEmail(email);
  const passwordOk = user ? await verifyPassword(user.password_hash, body.password) : false;
  if (!user || !passwordOk || user.status !== "active") {
    await createLoginEvent({
      ...meta,
      userId: user?.id,
      email,
      success: false,
      reason: "invalid_credentials"
    });
    throw httpError(401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
  }

  await updateUserLastLogin(user.id);
  await createLoginEvent({ ...meta, userId: user.id, email, success: true, reason: "login" });
  return createSessionResponse(user, meta.deviceName);
}

export async function refreshSession(body, meta) {
  const rawToken = String(body.refreshToken || "").trim();
  if (!rawToken) {
    throw httpError(400, "REFRESH_TOKEN_REQUIRED", "Refresh token is required.");
  }
  const tokenHash = hashRefreshToken(rawToken);
  const record = await findRefreshTokenByHash(tokenHash);
  if (!record || record.revoked_at || new Date(record.expires_at).getTime() <= Date.now()) {
    throw httpError(401, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
  }

  const user = await findUserById(record.user_id);
  if (!user || user.status !== "active") {
    throw httpError(401, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
  }

  await revokeRefreshToken(record.id);
  return createSessionResponse(user, meta.deviceName || record.device_name);
}

export async function logoutUser(body) {
  const rawToken = String(body.refreshToken || "").trim();
  if (rawToken) {
    const record = await findRefreshTokenByHash(hashRefreshToken(rawToken));
    if (record) {
      await revokeRefreshToken(record.id);
    }
  }
  return { ok: true };
}

export async function getCurrentUser(request) {
  const token = extractBearerToken(request);
  if (!token) {
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }
  const claims = await verifyAccessToken(token);
  const user = await findUserById(claims.userId);
  if (!user || user.status !== "active") {
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }
  return user;
}

export async function changePassword(request, body) {
  const user = await getCurrentUser(request);
  const passwordOk = await verifyPassword(user.password_hash, body.currentPassword);
  if (!passwordOk) {
    throw httpError(401, "INVALID_CREDENTIALS", "Current password is incorrect.");
  }
  validatePassword(body.newPassword);
  if (body.currentPassword === body.newPassword) {
    throw httpError(400, "PASSWORD_UNCHANGED", "New password must be different.");
  }
  const passwordHash = await hashPassword(body.newPassword);
  await updateUserPassword(user.id, passwordHash);
  await revokeAllUserRefreshTokens(user.id);
  return { ok: true };
}

async function createSessionResponse(user, deviceName) {
  const refreshToken = createRefreshToken();
  const expiresAt = new Date(Date.now() + config.refreshTokenTtlDays * 24 * 60 * 60 * 1000);
  await createRefreshTokenRecord({
    userId: user.id,
    tokenHash: hashRefreshToken(refreshToken),
    deviceName,
    expiresAt
  });
  return {
    user: toPublicUser(user),
    accessToken: await signAccessToken(user),
    refreshToken,
    expiresIn: config.accessTokenTtlSeconds
  };
}

async function enforceRateLimit(key, maxAttempts, windowSeconds) {
  const count = await incrementWithExpiry(key, windowSeconds);
  if (count > maxAttempts) {
    throw httpError(429, "RATE_LIMITED", "Too many attempts. Please try again later.");
  }
}

function validateEmail(email) {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw httpError(400, "INVALID_EMAIL", "Email is invalid.");
  }
}

function normalizeUsername(username) {
  const value = String(username || "").trim();
  if (value.length < 2 || value.length > 32) {
    throw httpError(400, "INVALID_USERNAME", "Username must be 2 to 32 characters.");
  }
  return value;
}
