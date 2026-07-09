# Nazhi Backend API

最后更新：2026-05-22

本文档是 `nazhi-backend` 的后端接口契约。后端当前定位是 AI 代理服务，不是用户知识库的长期存储服务。

```text
Android App -> Nazhi Backend -> MiniMax API -> Nazhi Backend -> Android App
```

## 设计边界

- Android 端保存 `Note`、`KnowledgeEntry`、embedding BLOB 和本地检索索引。
- 后端只处理本次请求所需的文本、上下文和模型调用，不长期保存用户正文。
- V1 不使用云端向量库；Android 先做本地 TopK 检索，再把选中的上下文传给后端问答。
- API Key 不写入 Android 安装包；生产部署时保存在服务器 `.env` 或系统环境变量中。
- 未来付费用户的云同步、云备份和云端向量库可以作为 V2+ 扩展，不改变 V1 本地优先边界。

## 认证

除 `/health` 外，接口都要求 Bearer Token。

V2 起 `/v1/*` AI 能力接口优先接受用户登录后的 access token：

```http
Authorization: Bearer <accessToken>
```

开发期仍兼容后端环境变量 `NAZHI_DEV_TOKEN`：

```http
Authorization: Bearer <NAZHI_DEV_TOKEN>
```

当 `NAZHI_DEV_TOKEN` 未配置时，后端保持本地开发开放模式；生产环境必须配置用户账号体系和 HTTPS。

认证失败统一返回：

```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Missing or invalid bearer token."
  }
}
```

## 错误格式

所有错误使用统一 JSON 结构：

```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "`input` must be a non-empty array."
  }
}
```

常见错误码：

- `INVALID_JSON`：请求体不是合法 JSON。
- `UNAUTHORIZED`：缺少或传入了错误的 Bearer Token，或用户 access token 已失效。
- `INVALID_INPUT` / `INVALID_NOTES` / `INVALID_CONTEXTS`：请求字段不符合接口要求。
- `TASK_NOT_FOUND`：异步任务不存在或已过期。
- `MINIMAX_CHAT_TIMEOUT`：Chat 模型调用超时。
- `NOT_FOUND`：路由不存在。

## GET /health

服务健康检查，不需要认证。

响应：

```json
{
  "ok": true,
  "service": "nazhi-backend",
  "embeddingProvider": "minimax",
  "chatProvider": "minimax",
  "asrProvider": "xfyun",
  "asr": {
    "provider": "xfyun",
    "configured": true,
    "shortAudio": true,
    "longAudio": true,
    "maxDurationMs": 900000,
    "shortThresholdMs": 58000,
    "status": "ready",
    "message": "Xfyun ASR is configured."
  }
}
```

`asr` 为安全自检结果，只返回配置状态，不返回 `XFYUN_APP_ID`、`XFYUN_API_KEY`、`XFYUN_API_SECRET`、签名或请求头。

常见 `asr.status`：

- `ready`：讯飞短音频与长音频配置完整。
- `mock`：当前为本地模拟转写。
- `missing_credentials`：缺少讯飞密钥环境变量。
- `websocket_unavailable`：Node.js 运行时不支持短音频 WebSocket。
- `missing_endpoint`：讯飞接口地址配置不完整。
- `unsupported_provider`：`ASR_PROVIDER` 不是当前支持的值。

## GET /v1/auth-check

用于 Android 设置页验证服务地址和 Token 是否可用。

响应：

```json
{
  "ok": true,
  "service": "nazhi-backend",
  "authMode": "user_token",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "username": "用户名",
    "status": "active"
  }
}
```

`authMode` 可能为：

- `user_token`：用户 access token。
- `dev_token`：开发 Token。
- `dev_open`：未配置 `NAZHI_DEV_TOKEN` 时的本地开放模式。

## POST /v1/embeddings

为 Android 端本地向量库生成 embedding。Android 负责保存向量和执行本地检索。

请求：

```json
{
  "requestId": "uuid",
  "model": "embo-01",
  "input": [
    {
      "id": "knowledge-entry-id",
      "text": "需要生成向量的文本",
      "metadata": {
        "ownerType": "knowledge_entry",
        "ownerId": "knowledge-entry-id",
        "textHash": "sha256"
      }
    }
  ]
}
```

响应：

```json
{
  "requestId": "uuid",
  "model": "embo-01",
  "dimensions": 1536,
  "items": [
    {
      "id": "knowledge-entry-id",
      "embedding": [0.0123, -0.0456],
      "metadata": {
        "ownerType": "knowledge_entry",
        "ownerId": "knowledge-entry-id",
        "textHash": "sha256"
      }
    }
  ],
  "usage": {
    "inputTokens": 128
  }
}
```

MiniMax provider 约定：

- 建库文本使用 `model=embo-01` 和 `type=db`。
- 查询文本使用 `model=embo-01` 和 `type=query`。
- 当前向量维度为 1536。
- 如果 Chat 和 Embedding 使用不同套餐或 Key，应使用 `MINIMAX_EMBEDDING_API` 独立配置 Embedding。

## POST /v1/organize-notes

同步执行 AI 整理。适合调试或小批量请求；真机体验优先使用异步任务接口。

请求：

```json
{
  "requestId": "uuid",
  "date": "2026-05-18",
  "language": "zh-CN",
  "notes": [
    {
      "id": "note-id-1",
      "content": "用户保存的原始文本",
      "title": "可选标题",
      "sourceType": "SHARE",
      "sourceUrl": "https://example.com",
      "createdAt": 1778457600000
    }
  ],
  "options": {
    "mergeSimilar": true,
    "maxDrafts": 10
  }
}
```

响应：

```json
{
  "requestId": "uuid",
  "date": "2026-05-18",
  "drafts": [
    {
      "id": "draft-id-1",
      "title": "知识条目标题",
      "summary": "一句话摘要",
      "content": "整理后的知识正文",
      "intentType": "QUOTABLE",
      "tags": ["产品", "知识管理"],
      "sourceNoteIds": ["note-id-1"],
      "evidenceQuotes": ["来自原文的短引用"],
      "insight": "可选推断或启发",
      "confidence": 0.86,
      "needsReview": false
    }
  ],
  "usage": {
    "inputTokens": 1200,
    "outputTokens": 500
  }
}
```

字段约束：

- `sourceNoteIds` 必须来自请求中的 `notes[].id`。
- `intentType` 只能是 `READ_LATER`、`QUOTABLE`、`INSPIRATION`。
- `content` 必须忠实表达原始内容；推断、启发必须放在 `insight`。
- `needsReview=true` 表示 Android 端应提示用户重点确认。

## POST /v1/organize-notes/jobs

异步提交 AI 整理任务。Android 可立即展示“已提交/处理中”状态，再轮询任务进度。

请求体与 `POST /v1/organize-notes` 相同。

响应状态码：`202 Accepted`

响应：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "ORGANIZE_NOTES",
  "status": "RUNNING",
  "stage": "ACCEPTED",
  "progress": 5,
  "message": "已提交 AI 整理任务",
  "createdAt": 1778457600000,
  "updatedAt": 1778457600000
}
```

## GET /v1/tasks/:taskId

查询异步任务状态。当前任务存在内存中，后端重启后任务会丢失，Android 应允许用户重试。

运行中：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "ORGANIZE_NOTES",
  "status": "RUNNING",
  "stage": "CALLING_MODEL",
  "progress": 45,
  "message": "AI 正在整理今日内容",
  "createdAt": 1778457600000,
  "updatedAt": 1778457601000
}
```

成功：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "ORGANIZE_NOTES",
  "status": "SUCCEEDED",
  "stage": "DONE",
  "progress": 100,
  "message": "已生成 3 条 AI 草稿",
  "result": {
    "requestId": "uuid",
    "date": "2026-05-18",
    "drafts": []
  }
}
```

失败：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "ORGANIZE_NOTES",
  "status": "FAILED",
  "stage": "FAILED",
  "progress": 100,
  "message": "任务失败",
  "error": {
    "code": "TASK_FAILED",
    "message": "任务失败"
  }
}
```

阶段枚举：

```text
ACCEPTED
PREPARING_NOTES
CALLING_MODEL
PARSING_RESULT
DONE
FAILED
```

## POST /v1/audio-transcriptions/jobs

创建录音转写任务。Android 上传用户主动录制的 WAV 文件，后端根据配置选择 ASR Provider。Android 不感知具体厂商。

请求：`multipart/form-data`

```text
audio: wav file
durationMs: 录音时长，毫秒
source: floating_ball
language: zh-CN
sampleRate: 16000
channels: 1
encoding: wav
```

响应状态码：`202 Accepted`

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "AUDIO_TRANSCRIPTION",
  "status": "RUNNING",
  "stage": "ACCEPTED",
  "progress": 5,
  "message": "已提交录音转写任务"
}
```

约束：

- 单次录音最长 15 分钟，默认 `ASR_MAX_DURATION_MS=900000`。
- 默认最大请求体约 35MB，覆盖 15 分钟 16kHz/16bit/mono WAV。
- 原始音频只用于本次转写任务，后端当前不做长期存储。
- `ASR_PROVIDER=mock` 用于本地开发。
- `ASR_PROVIDER=xfyun` 时，后端内部按时长分流到讯飞短音频 IAT 或极速录音转写。

## GET /v1/audio-transcriptions/jobs/:taskId

查询录音转写任务状态。

运行中：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "AUDIO_TRANSCRIPTION",
  "status": "RUNNING",
  "stage": "CALLING_ASR",
  "progress": 60,
  "message": "正在等待长音频转写结果"
}
```

成功：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "AUDIO_TRANSCRIPTION",
  "status": "SUCCEEDED",
  "stage": "DONE",
  "progress": 100,
  "message": "转写完成",
  "result": {
    "requestId": "uuid",
    "text": "转写文本",
    "rawText": "转写文本",
    "durationMs": 120000,
    "provider": "xfyun",
    "mode": "speed_transcription"
  }
}
```

说明：

- `text` 是后端规范化后的可保存文本，Android 默认保存该字段为今日 Note 正文。
- `rawText` 保留同一轮 ASR 的原始/近原始文本，当前不作为 Android 展示主字段。
- 后端只做确定性轻量规范化：去不可见字符、统一换行、合并多余空行、收紧中文异常空格。
- 标题、摘要、标签和知识归纳不在 ASR 阶段生成，交给每日 AI 整理阶段统一处理。
- ASR 识别为空时任务失败，错误码为 `EMPTY_TRANSCRIPT`。

失败：

```json
{
  "taskId": "task-uuid",
  "requestId": "uuid",
  "type": "AUDIO_TRANSCRIPTION",
  "status": "FAILED",
  "stage": "FAILED",
  "progress": 100,
  "message": "转写失败",
  "error": {
    "code": "ASR_PROVIDER_FAILED",
    "message": "转写失败，请稍后重试"
  }
}
```

## POST /v1/knowledge-chat

知识库问答接口。Android 端先完成查询 embedding、本地向量检索和 TopK 选择，再把选中的上下文传给后端。

V1.1 支持同一会话内的轻量记忆。记忆只作为追问消解和上下文压缩信号，不是事实来源；后端回答仍必须只基于本次 `contexts`。

多轮问答中，Android 可先调用 `POST /v1/rewrite-question` 生成 `resolvedQuestion` 和本地检索用的 `retrievalQuery`。

请求：

```json
{
  "requestId": "uuid",
  "question": "我最近关于产品体验的想法有哪些？",
  "language": "zh-CN",
  "resolvedQuestion": "我最近关于产品体验中降低保存步骤和增强即时反馈的想法有哪些？",
  "sessionMemory": "本会话此前围绕低摩擦收纳、分享入口和知识库问答体验展开讨论。",
  "previousCitationIds": ["knowledge-entry-id-prev"],
  "contexts": [
    {
      "id": "knowledge-entry-id",
      "title": "知识条目标题",
      "summary": "摘要",
      "content": "知识正文",
      "tags": ["产品"],
      "sourceNoteIds": ["note-id-1"],
      "score": 0.82
    }
  ]
}
```

响应：

```json
{
  "requestId": "uuid",
  "answer": "# 产品体验想法\n## 核心结论\n- 降低保存步骤\n- 增强即时反馈\n## 展开说明\n1. 分享入口应减少跳转。\n2. 引用查看应留在问答上下文中。",
  "citations": [
    {
      "contextId": "knowledge-entry-id",
      "quote": "降低保存步骤",
      "reason": "该知识条目直接支撑回答中的用户体验结论。"
    }
  ],
  "updatedMemoryDigest": "本会话关注产品体验中的低摩擦保存和即时反馈，已讨论分享入口、悬浮球和问答引用体验。",
  "usage": {
    "inputTokens": 1200,
    "outputTokens": 240
  }
}
```

约束：

- `contexts` 最多 8 条。
- `citations[].contextId` 只能引用 Android 传入的 `contexts[].id`。
- 如果 `contexts` 为空或不足，后端必须返回“当前知识库中没有足够信息”。
- `resolvedQuestion` 是追问补全后的独立问题，只用于消解指代和检索意图，不是事实来源。
- `sessionMemory` 最多建议 300 字，由 Android 本地保存和传入。
- `previousCitationIds` 最多建议 5 条，只用于提示上一轮引用来源，不允许替代 `contexts`。
- `updatedMemoryDigest` 是完整替换后的会话摘要，建议不超过 200 个中文字符。
- 如果回答失败、知识不足、或 `citations` 为空，`updatedMemoryDigest` 必须为空字符串。
- 如果 `contexts` 非空但不足以回答，回答应说明“当前知识库中没有足够信息”，不要说“没有上下文”或“未提供上下文”。
- `answer` 仍是字符串，但允许使用轻量 Markdown：`#` 标题、`##` 小标题、`-` 要点、`1.` 编号步骤和普通段落；不要依赖表格、代码块、HTML 或图片。
- 如果 `contexts` 足以回答，`answer` 末尾应包含 `## 可以继续追问`，并用 2-3 条 `-` 列表给出用户可直接继续提问的问题；Android 会将这些列表项渲染为追问按钮。
- 后端不接收全量知识库，不拥有云端记忆。

## POST /v1/rewrite-question

问题改写接口。用于多轮问答中的追问识别和检索 query 补全。该接口不回答用户问题，不接收本地知识库全文。

请求：

```json
{
  "requestId": "uuid",
  "currentQuestion": "这个怎么展开？",
  "language": "zh-CN",
  "sessionMemory": "本会话讨论知识库问答、引用展示和多轮追问。",
  "lastUserQuestion": "知识库问答体验还可以怎么优化？",
  "lastAssistantAnswerPreview": "可以从引用展示、会话记忆、追问识别三个方向优化。",
  "previousCitationTitles": ["知识库问答引用体验", "本地会话记忆策略"]
}
```

响应：

```json
{
  "requestId": "uuid",
  "isFollowUp": true,
  "standaloneQuestion": "如何展开知识库问答体验中的引用展示、会话记忆和追问识别优化？",
  "retrievalQuery": "知识库问答 引用展示 会话记忆 追问识别 优化",
  "shouldUsePreviousCitations": true,
  "confidence": 0.85
}
```

约束：

- `rewrite-question` 只做追问识别和检索问题改写，不生成最终回答。
- Android 使用 `retrievalQuery` 做 embedding 和本地向量检索。
- `confidence < 0.55` 时，Android 可回退到本地规则。
- `isFollowUp=false` 时，Android 不传 `sessionMemory` 和 `previousCitationIds` 给最终问答。

## 环境变量

```env
PORT=8787
NAZHI_DEV_TOKEN=change-me

# mock | minimax
EMBEDDING_PROVIDER=mock
EMBEDDING_MODEL=embo-01
EMBEDDING_DIMENSIONS=1536

MINIMAX_API=
MINIMAX_API_KEY=
MINIMAX_EMBEDDING_API=
MINIMAX_EMBEDDING_API_KEY=
MINIMAX_GROUP_ID=
MINIMAX_EMBEDDING_ENDPOINT=https://api.minimaxi.com/v1/embeddings
MINIMAX_EMBEDDING_MODEL=embo-01
MINIMAX_EMBEDDING_DIM=1536

# mock | minimax
CHAT_PROVIDER=mock
CHAT_MODEL=MiniMax-M2.7-highspeed

MINIMAX_CHAT_ENDPOINT=https://api.minimaxi.com/v1/chat/completions
MINIMAX_CHAT_MODEL=MiniMax-M2.7-highspeed
MINIMAX_JSON_MODE=false

# mock | xfyun
ASR_PROVIDER=mock
ASR_MAX_DURATION_MS=900000
ASR_SHORT_THRESHOLD_MS=58000
XFYUN_APP_ID=
XFYUN_API_KEY=
XFYUN_API_SECRET=
XFYUN_IAT_ENDPOINT=wss://iat.xf-yun.com/v1
XFYUN_SPEED_UPLOAD_ENDPOINT=https://upload-ost-api.xfyun.cn/file/upload
XFYUN_SPEED_CREATE_ENDPOINT=https://ost-api.xfyun.cn/v2/ost/pro_create
XFYUN_SPEED_QUERY_ENDPOINT=https://ost-api.xfyun.cn/v2/ost/query
```

## Android 本地 RAG 链路

```text
保存或确认知识条目
-> Android 请求 /v1/embeddings
-> Android 将 FloatArray 转 BLOB 存入 Room
-> 用户提问
-> Android 如存在会话历史，调用 /v1/rewrite-question 生成 retrievalQuery
-> Android 请求 /v1/embeddings 生成 query 向量
-> Android 本地 cosine/dot product 检索 TopK
-> Android 合并 TopK 与上一轮引用条目，最多保留 5 条
-> Android 请求 /v1/knowledge-chat 并携带 TopK contexts、sessionMemory、previousCitationIds
-> 后端调用 MiniMax Chat 生成答案、引用和 updatedMemoryDigest
-> Android 在回答有效且存在引用时更新本地 ChatSession.memoryDigest
```

规模判断：

- 1,000 - 20,000 条 chunk：Room + 内存 TopK 足够。
- 20,000 - 50,000 条 chunk：需要候选过滤、缓存和分页加载。
- 50,000+ 条 chunk：再考虑本地 ANN 索引或云端检索。
