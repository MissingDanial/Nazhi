import http from "node:http";
import { config } from "./config.js";
import { sendError } from "./http.js";
import { route } from "./router.js";

const server = http.createServer(async (request, response) => {
  try {
    await route(request, response);
  } catch (error) {
    if (!error.statusCode || error.statusCode >= 500) {
      console.error(error);
    }
    sendError(response, error);
  }
});

server.listen(config.port, () => {
  console.log(`Nazhi backend listening on http://localhost:${config.port}`);
  console.log(`Embedding provider: ${config.embeddingProvider}`);
  console.log(`Chat provider: ${config.chatProvider}`);
});
