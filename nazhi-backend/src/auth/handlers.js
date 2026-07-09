import {
  changePassword,
  getCurrentUser,
  loginUser,
  logoutUser,
  refreshSession,
  registerUser
} from "./service.js";
import { toPublicUser } from "./repository.js";

export async function handleAuthRegister(body, request) {
  return registerUser(body, requestMeta(request));
}

export async function handleAuthLogin(body, request) {
  return loginUser(body, requestMeta(request));
}

export async function handleAuthRefresh(body, request) {
  return refreshSession(body, requestMeta(request));
}

export async function handleAuthLogout(body) {
  return logoutUser(body);
}

export async function handleAuthMe(request) {
  const user = await getCurrentUser(request);
  return { user: toPublicUser(user) };
}

export async function handleAuthChangePassword(request, body) {
  return changePassword(request, body);
}

function requestMeta(request) {
  return {
    ip: request.headers["x-forwarded-for"]?.split(",")[0]?.trim() || request.socket.remoteAddress || "",
    userAgent: request.headers["user-agent"] || "",
    deviceName: request.headers["x-nazhi-device"] || ""
  };
}
