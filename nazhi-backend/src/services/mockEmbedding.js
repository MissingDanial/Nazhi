import crypto from "node:crypto";

export function embedMock(text, dimensions) {
  const vector = new Array(dimensions).fill(0);
  const normalized = String(text || "").toLowerCase();

  for (let index = 0; index < normalized.length; index += 1) {
    const code = normalized.charCodeAt(index);
    if (Number.isNaN(code) || /\s/.test(normalized[index])) {
      continue;
    }
    const primary = positiveModulo(code * 31 + index * 17, dimensions);
    const secondary = positiveModulo(code * 13 + index * 7, dimensions);
    vector[primary] += 1;
    vector[secondary] += 0.25;
  }

  return normalize(vector);
}

export function textHash(text) {
  return crypto.createHash("sha256").update(String(text || ""), "utf8").digest("hex");
}

function normalize(vector) {
  const norm = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0));
  if (!norm) {
    return vector;
  }
  return vector.map((value) => value / norm);
}

function positiveModulo(value, modulus) {
  return ((value % modulus) + modulus) % modulus;
}
