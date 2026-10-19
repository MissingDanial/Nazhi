// Disposable-emulator fixture: no provider calls, credentials or real recordings.
import http from 'node:http';

const tasks = new Map();
const requests = [];
const disconnected = new Map();
const port = Number(process.argv[2] || 8799);
const taskResponse = (task, finished = true) => ({
  taskId: task.taskId, requestId: task.requestId, type: 'AUDIO_TRANSCRIPTION',
  status: finished ? 'SUCCEEDED' : 'RUNNING', stage: finished ? 'DONE' : 'TRANSCRIBING',
  progress: finished ? 100 : 40, message: finished ? 'finished' : 'processing',
  ...(finished ? { result: { text: `测试恢复结果 ${task.requestId}`, durationMs: 1000, provider: 'fixture', mode: 'test' } } : {}),
});
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = Buffer.concat(chunks).toString();
  requests.push({ method: req.method, path: url.pathname });
  const send = (status, value) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(value));
  };
  if (url.pathname === '/test/state') {
    return send(200, { requests, tasks: [...tasks.values()] });
  }
  if (req.method === 'POST' && url.pathname === '/v1/audio-transcriptions/jobs') {
    const requestId = /name="requestId"\r\n\r\n([^\r\n]+)/.exec(body)?.[1];
    if (!requestId) return send(400, { error: { code: 'INVALID_REQUEST_ID', message: 'missing identity' } });
    const task = tasks.get(requestId) || { requestId, taskId: `remote-${requestId}`, creates: 0 };
    task.creates += 1;
    tasks.set(requestId, task);
    return send(202, taskResponse(task, requestId === 'finished-before-save'));
  }
  if (req.method === 'GET') {
    const lookup = url.pathname.startsWith('/v1/tasks/by-request/');
    const id = decodeURIComponent(url.pathname.split('/').pop());
    const task = lookup ? tasks.get(id) : [...tasks.values()].find(t => t.taskId === id);
    if (task) {
      // HttpURLConnection can retry an idempotent GET once after a broken connection.
      if (task.requestId === 'network-interruption' && (disconnected.get(task.requestId) || 0) < 2) {
        disconnected.set(task.requestId, (disconnected.get(task.requestId) || 0) + 1);
        return req.socket.destroy();
      }
      return send(200, taskResponse(task));
    }
  }
  return send(404, { error: { code: 'TASK_NOT_FOUND', message: 'task lost' } });
});
server.listen(port, '127.0.0.1', () => process.stdout.write(`recovery fixture listening on ${port}\n`));
