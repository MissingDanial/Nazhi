export async function readJsonBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }

  const rawBody = Buffer.concat(chunks).toString("utf8");
  if (!rawBody.trim()) {
    return {};
  }

  try {
    return JSON.parse(rawBody);
  } catch {
    throw httpError(400, "INVALID_JSON", "Request body must be valid JSON.");
  }
}

export async function readMultipartForm(request, { maxBytes = 35 * 1024 * 1024 } = {}) {
  const contentType = request.headers["content-type"] || "";
  const boundaryMatch = contentType.match(/boundary=(?:"([^"]+)"|([^;]+))/i);
  if (!boundaryMatch) {
    throw httpError(400, "INVALID_MULTIPART", "Request must be multipart/form-data.");
  }
  const boundary = boundaryMatch[1] || boundaryMatch[2];
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > maxBytes) {
      throw httpError(413, "PAYLOAD_TOO_LARGE", "Audio payload is too large.");
    }
    chunks.push(chunk);
  }
  return parseMultipart(Buffer.concat(chunks), boundary);
}

export function sendJson(response, statusCode, body) {
  const json = JSON.stringify(body);
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(json)
  });
  response.end(json);
}

function parseMultipart(buffer, boundary) {
  const delimiter = Buffer.from(`--${boundary}`);
  const fields = {};
  const files = {};
  let cursor = buffer.indexOf(delimiter);
  while (cursor !== -1) {
    cursor += delimiter.length;
    if (buffer.slice(cursor, cursor + 2).toString("utf8") === "--") {
      break;
    }
    if (buffer.slice(cursor, cursor + 2).toString("utf8") === "\r\n") {
      cursor += 2;
    }
    const headerEnd = buffer.indexOf(Buffer.from("\r\n\r\n"), cursor);
    if (headerEnd === -1) {
      break;
    }
    const headersText = buffer.slice(cursor, headerEnd).toString("utf8");
    const nextDelimiter = buffer.indexOf(delimiter, headerEnd + 4);
    if (nextDelimiter === -1) {
      break;
    }
    let body = buffer.slice(headerEnd + 4, nextDelimiter);
    if (body.slice(-2).toString("utf8") === "\r\n") {
      body = body.slice(0, -2);
    }
    const disposition = headersText.match(/content-disposition:\s*form-data;\s*([^\r\n]+)/i)?.[1] || "";
    const name = disposition.match(/name="([^"]+)"/i)?.[1];
    const filename = disposition.match(/filename="([^"]*)"/i)?.[1];
    const contentType = headersText.match(/content-type:\s*([^\r\n]+)/i)?.[1]?.trim() || "application/octet-stream";
    if (name && filename !== undefined) {
      files[name] = {
        filename,
        contentType,
        data: body
      };
    } else if (name) {
      fields[name] = body.toString("utf8");
    }
    cursor = nextDelimiter;
  }
  return { fields, files };
}

export function sendError(response, error) {
  const statusCode = error.statusCode || 500;
  sendJson(response, statusCode, {
    error: {
      code: error.code || "INTERNAL_ERROR",
      message: error.publicMessage || "Internal server error."
    }
  });
}

export function httpError(statusCode, code, publicMessage) {
  const error = new Error(publicMessage);
  error.statusCode = statusCode;
  error.code = code;
  error.publicMessage = publicMessage;
  return error;
}

export function requireAuth(request, devToken) {
  if (!devToken) {
    return;
  }
  const authorization = request.headers.authorization || "";
  if (authorization !== `Bearer ${devToken}`) {
    throw httpError(401, "UNAUTHORIZED", "Missing or invalid bearer token.");
  }
}
