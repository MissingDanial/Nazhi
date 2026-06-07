import { config } from "../config.js";

let clientPromise = null;

export async function getRedisClient() {
  if (!config.redisUrl) {
    return null;
  }
  if (!clientPromise) {
    clientPromise = import("redis").then(async ({ createClient }) => {
      const client = createClient({ url: config.redisUrl });
      client.on("error", (error) => {
        console.error("[redis]", error.message);
      });
      await client.connect();
      return client;
    });
  }
  return clientPromise;
}

export async function incrementWithExpiry(key, ttlSeconds) {
  const client = await getRedisClient();
  if (!client) {
    return 1;
  }
  const count = await client.incr(key);
  if (count === 1) {
    await client.expire(key, ttlSeconds);
  }
  return count;
}
