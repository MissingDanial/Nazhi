import { config } from "../config.js";
import { httpError } from "../http.js";
import { findUserById, toPublicUser } from "./repository.js";
import { extractBearerToken, verifyAccessToken } from "./tokens.js";

export async function resolveApiAuth(request) {
  const token = extractBearerToken(request);

  if (!token) {
    if (!config.devToken) {
      return {
        mode: "dev_open",
        userId: null,
        user: null,
        jti: null
      };
    }
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }

  if (config.devToken && token === config.devToken) {
    return {
      mode: "dev_token",
      userId: null,
      user: null,
      jti: null
    };
  }

  const claims = await verifyAccessToken(token);
  const user = await findUserById(claims.userId);
  if (!user || user.status !== "active") {
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }

  return {
    mode: "user_token",
    userId: user.id,
    user: toPublicUser(user),
    jti: claims.jti || null
  };
}
