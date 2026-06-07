import { httpError } from "../http.js";

const MIN_PASSWORD_LENGTH = 8;

export function validatePassword(password) {
  if (typeof password !== "string" || password.length < MIN_PASSWORD_LENGTH) {
    throw httpError(400, "PASSWORD_TOO_SHORT", "Password must be at least 8 characters.");
  }
  if (password.length > 128) {
    throw httpError(400, "PASSWORD_TOO_LONG", "Password is too long.");
  }
}

export async function hashPassword(password) {
  validatePassword(password);
  const argon2 = await import("argon2");
  return argon2.hash(password, {
    type: argon2.argon2id,
    memoryCost: 19_456,
    timeCost: 2,
    parallelism: 1
  });
}

export async function verifyPassword(hash, password) {
  if (!hash || typeof password !== "string") {
    return false;
  }
  const argon2 = await import("argon2");
  return argon2.verify(hash, password);
}
