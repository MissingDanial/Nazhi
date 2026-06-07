import { query } from "../db/postgres.js";

export async function findUserByEmail(email) {
  const result = await query(
    `select id, email, username, password_hash, status, created_at, updated_at, last_login_at
       from auth.users
      where email = $1`,
    [normalizeEmail(email)]
  );
  return result.rows[0] || null;
}

export async function findUserById(id) {
  const result = await query(
    `select id, email, username, password_hash, status, created_at, updated_at, last_login_at
       from auth.users
      where id = $1`,
    [id]
  );
  return result.rows[0] || null;
}

export async function createUser({ email, username, passwordHash }) {
  const result = await query(
    `insert into auth.users (email, username, password_hash, status)
     values ($1, $2, $3, 'active')
     returning id, email, username, status, created_at, updated_at, last_login_at`,
    [normalizeEmail(email), username.trim(), passwordHash]
  );
  return result.rows[0];
}

export async function updateUserLastLogin(userId) {
  await query(
    `update auth.users
        set last_login_at = now(), updated_at = now()
      where id = $1`,
    [userId]
  );
}

export async function updateUserPassword(userId, passwordHash) {
  await query(
    `update auth.users
        set password_hash = $2, updated_at = now()
      where id = $1`,
    [userId, passwordHash]
  );
}

export async function createRefreshTokenRecord({ userId, tokenHash, deviceName, expiresAt }) {
  const result = await query(
    `insert into auth.refresh_tokens (user_id, token_hash, device_name, expires_at)
     values ($1, $2, $3, $4)
     returning id, user_id, expires_at, revoked_at, created_at`,
    [userId, tokenHash, deviceName || null, expiresAt]
  );
  return result.rows[0];
}

export async function findRefreshTokenByHash(tokenHash) {
  const result = await query(
    `select id, user_id, token_hash, device_name, expires_at, revoked_at, created_at
       from auth.refresh_tokens
      where token_hash = $1`,
    [tokenHash]
  );
  return result.rows[0] || null;
}

export async function revokeRefreshToken(tokenId) {
  await query(
    `update auth.refresh_tokens
        set revoked_at = coalesce(revoked_at, now())
      where id = $1`,
    [tokenId]
  );
}

export async function revokeAllUserRefreshTokens(userId) {
  await query(
    `update auth.refresh_tokens
        set revoked_at = coalesce(revoked_at, now())
      where user_id = $1 and revoked_at is null`,
    [userId]
  );
}

export async function createLoginEvent({ userId, email, ip, userAgent, success, reason }) {
  await query(
    `insert into auth.login_events (user_id, email, ip, user_agent, success, reason)
     values ($1, $2, $3, $4, $5, $6)`,
    [userId || null, email ? normalizeEmail(email) : null, ip || null, userAgent || null, success, reason || null]
  );
}

export function toPublicUser(user) {
  if (!user) {
    return null;
  }
  return {
    id: user.id,
    email: user.email,
    username: user.username,
    status: user.status
  };
}

export function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}
