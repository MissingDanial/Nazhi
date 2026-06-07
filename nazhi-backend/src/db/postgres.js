import { config } from "../config.js";
import { httpError } from "../http.js";

let poolPromise = null;

export async function query(sql, params = []) {
  const pool = await getPostgresPool();
  return pool.query(sql, params);
}

async function getPostgresPool() {
  if (!config.databaseUrl) {
    throw httpError(503, "DATABASE_NOT_CONFIGURED", "User database is not configured.");
  }
  if (!poolPromise) {
    poolPromise = import("pg").then(({ Pool }) => {
      return new Pool({
        connectionString: config.databaseUrl,
        max: 10,
        idleTimeoutMillis: 30_000,
        connectionTimeoutMillis: 5_000
      });
    });
  }
  return poolPromise;
}
