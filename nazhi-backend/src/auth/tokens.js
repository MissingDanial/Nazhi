import crypto from "node:crypto";
import { config } from "../config.js";
import { httpError } from "../http.js";

const ISSUER = "nazhi-backend";
const AUDIENCE = "nazhi-android";

export async function signAccessToken(user) {
  const { SignJWT } = await import("jose");
  const secret = getJwtSecret();
  const jti = crypto.randomUUID();
  return new SignJWT({
    scope: "user",
    username: user.username,
    email: user.email
  })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setSubject(user.id)
    .setJti(jti)
    .setExpirationTime(`${config.accessTokenTtlSeconds}s`)
    .sign(secret);
}

export async function verifyAccessToken(token) {
  const { jwtVerify } = await import("jose");
  const secret = getJwtSecret();
  try {
    const { payload } = await jwtVerify(token, secret, {
      issuer: ISSUER,
      audience: AUDIENCE
    });
    return {
      userId: payload.sub,
      jti: payload.jti
    };
  } catch {
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }
}

export function createRefreshToken() {
  return crypto.randomBytes(32).toString("base64url");
}

export function hashRefreshToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

export function extractBearerToken(request) {
  const authorization = request.headers.authorization || "";
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || "";
}

function getJwtSecret() {
  const rawSecret = config.jwtSecret || config.devToken;
  if (!rawSecret || rawSecret.length < 24) {
    throw httpError(503, "JWT_SECRET_NOT_CONFIGURED", "JWT secret is not configured.");
  }
  return new TextEncoder().encode(rawSecret);
}
