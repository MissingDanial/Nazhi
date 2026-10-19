# 纳知 Nazhi Android V2.1

把手机里转瞬即逝的好内容，养成一座可以追问的知识农场。

纳知是一个本地优先的 Android 个人知识库。它用悬浮窗在微信阅读、短视频、网页、聊天和任意 App 中快速收纳内容，再用 AI 整理、语义检索和 RAG 问答，把碎片信息沉淀成真正可复用的知识。

## 为什么做纳知

每天刷到、读到、听到的信息很多，但真正能留下来的很少。收藏夹会堆灰，截图很难搜索，手动整理又太慢。

纳知想解决的是这条链路：

```text
看到 / 听到有价值的内容
        ↓
悬浮窗一键收纳文字或音频
        ↓
今日页统一整理
        ↓
沉淀到本地知识库
        ↓
需要时直接向知识库提问
```

## 产品预览

### 跨 App 收纳

不用切回纳知。阅读、刷视频、看资料时，悬浮窗就停在当前 App 上方，随手把内容收入今日。

| 阅读文字时粘贴保存 | 短视频中录制/转写音频 |
| --- | --- |
| <img src="docs/app_show/pastecapture.jpg" alt="在阅读场景中通过纳知悬浮窗粘贴保存文字" width="260" /> | <img src="docs/app_show/audiocapture.jpg" alt="在短视频应用中通过纳知悬浮窗录制或转写音频" width="260" /> |

### 四个核心页面

| 今日 | 日历 | 问答 | 知识库 |
| --- | --- | --- | --- |
| <img src="docs/app_show/today.jpg" alt="纳知今日页，包含知识农场和今日告示牌" width="190" /> | <img src="docs/app_show/calendar.jpg" alt="纳知日历页，按日期展示收纳数量" width="190" /> | <img src="docs/app_show/qa.jpg" alt="纳知问答页，基于本地知识库提问" width="190" /> | <img src="docs/app_show/knowledge.jpg" alt="纳知知识库页，支持语义搜索和状态筛选" width="190" /> |

## 核心能力

### 1. 悬浮窗收纳

- 复制文字后用悬浮窗一键保存，适合阅读、网页、聊天和文档场景。
- 在短视频、课程、播客等场景中发起音频录制或系统音频捕获。
- 支持系统分享、Direct Share、文本选择菜单和剪贴板捕获。
- 上传或转写失败的音频会暂存，可在今日页统一重试。

### 2. 今日知识农场

- 今日页按状态展示 `待整理 / 待确认 / 已沉淀 / 已失败`。
- 碎片内容先进入今日收件箱，再由 AI 整理为结构化知识草稿。
- 像素农场用地块和作物表达每日知识状态，让整理过程有明确反馈。

### 3. 日历回顾

- 按日期查看历史收纳记录。
- 日期格按收纳数量显示不同生长状态。
- 适合回看某一天的阅读、视频、灵感和整理结果。

### 4. 本地知识库

- AI 草稿需用户确认后才正式入库。
- 支持标题、摘要、正文、标签的本地筛选。
- 支持索引状态追踪：可问答、沉淀中、处理失败。
- 知识条目、向量和对话记录优先保存在本地。

### 5. 知识库问答

- 提问时先从本地知识库做语义检索。
- 检索结果作为上下文发送给大模型，生成可追溯回答。
- 保留历史对话，支持引用来源回看。

## 设计方向

V1.4 采用“像素知识农场”视觉语言：界面有农场的温度，但正文、按钮、长回答和表单仍保持现代 Android 的可读性。

- PNG 资产负责像素边框、农田、作物、气泡和面板氛围。
- Compose / Material3 负责文字、滚动、输入、按钮、状态和交互。
- 不把文字写死进图片，所有文案仍可动态变化。

详细资产规则见 [docs/V1.4 UI资产规则.md](docs/V1.4%20UI资产规则.md)。

### 8. V1.4 像素知识农场 UI 改版

V1.4 聚焦视觉体验升级，引入像素农场主题，不改变数据库、后端接口、AI 整理逻辑、embedding、问答主逻辑、登录、同步或任务系统。

#### 核心视觉更新

- **今日页**：像素风状态标签、告示牌背景、知识农场面板、底部导航图标
- **日历页**：农场年鉴风格，草绿色面板、木质边框、叶片浆果点缀
- **问答页**：像素风消息气泡、输入面板、引用条背景
- **知识库页**：语义搜索面板、筛选面板、条目卡、空状态、状态徽标背景

#### 农场交互

- **5x5 农田网格**：每个地块可种植不同作物，代表知识条目
- **作物生长阶段**：幼苗 → 成长 → 成熟，对应知识条目状态
- **缩放拖拽**：支持 1.0x - 2.2x 缩放，放大后可拖拽平移
- **地块点击**：选中态覆盖层，聚合展示关联的 Note / Draft / KnowledgeEntry

#### 资产规范

- 所有 PNG 资产位于 `app/src/main/res/drawable-nodpi/`
- 不在 PNG 中内嵌中文、数字、状态文案、按钮文字、徽标或水印
- 所有可变文字由 Compose 覆盖渲染
- 像素边缘保持清晰，不做模糊、柔光、写实质感
- 详细资产规则见 `docs/V1.4 UI资产规则.md`

#### 技术实现

- **Canvas 渲染**：农场区域使用 `FilterQuality.None` 保持像素清晰
- **九宫格拉伸**：面板背景使用 NinePatch 避免边角变形
- **组件复用**：`NazhiStatusChip`、`FarmNoticeCard`、`DailyFarmPreview` 等
- **主题 token**：复用 `NazhiTokens` 和状态 palette，不散写一次性颜色

## 技术架构

### 技术栈

- 语言：Kotlin
- UI 框架：Jetpack Compose
- 数据库：Room（含 TypeConverters）
- 偏好存储：DataStore + EncryptedSharedPreferences
- 异步：Kotlin Coroutines
- 依赖注入：手写 `AppContainer`（V1 暂不引入 Hilt）

### 构建环境

- AGP 9.1.0
- Gradle 9.3.1
- JDK 17
- Android SDK API 36
- V1.4 UI 资产位于 `app/src/main/res/drawable-nodpi/`

### 数据模型

| 模型 | 说明 |
| --- | --- |
| `Note` | 原始笔记，按日期分组 |
| `KnowledgeEntryDraft` | AI 整理后的待确认草稿 |
| `KnowledgeEntry` | 用户确认入库的知识条目 |
| `EmbeddingRecord` | 知识条目的向量嵌入记录 |
| `ChatSession` / `ChatMessage` | 对话会话与消息 |
| `ChatCitation` | 回答中的引用来源 |
| `FarmPlot` | 农场地块状态（V1.4 新增） |
| `CropType` | 作物类型与生长阶段（V1.4 新增） |

### 模块结构

```text
app/src/main/java/com/nazhi/app/
├── core/
│   ├── audio/            # WAV 录音与音频转写基础能力
│   ├── capture/          # 捕获服务（悬浮窗、剪贴板）
│   ├── database/         # Room 数据库、DAO、Entity
│   ├── embedding/        # 本地向量嵌入
│   ├── export/           # 数据导入导出
│   ├── knowledge/        # 知识整理与索引协调
│   ├── model/            # 数据模型
│   ├── network/          # 后端 API 客户端
│   ├── repository/       # 数据仓库层
│   ├── settings/         # 设置存储
│   └── ui/               # V1.4 UI 组件与主题
│       ├── NazhiComponents.kt  # 像素风 UI 组件
│       └── NazhiTheme.kt       # 主题配置与 token
├── feature/
│   ├── calendar/         # 日历视图
│   ├── chat/             # RAG 问答
│   ├── farm/             # 知识农场
│   ├── home/             # 主页导航
│   ├── inbox/            # 今日收件箱
│   ├── knowledge/        # 知识库管理
│   └── settings/         # 设置页面
├── ClipboardCaptureActivity.kt
├── AudioTranscriptionActivity.kt
├── AudioTranscriptionPermissionActivity.kt
├── AudioTranscriptionService.kt
├── FloatingCaptureService.kt
├── MainActivity.kt
├── NazhiApp.kt
├── ProcessTextCaptureActivity.kt
├── ShareCaptureActivity.kt
└── SystemAudioCapturePermissionActivity.kt
```

### 仓库结构

V2.0 起采用 monorepo 方式管理 Android 与后端，确保登录注册页面、用户鉴权 API、数据库 migration 和部署说明可以在同一个版本提交中同步演进。

```text
nazhi-android/
├── app/                    # Android 客户端
├── docs/                   # 产品、开发和资产规则文档
└── nazhi-backend/          # Node.js API 中转与 V2 账号认证服务
```

后端目录只提交源码、migration、`package.json`、`package-lock.json`、`.env.example` 和接口文档。真实 `.env`、`node_modules/`、本地接口密钥文件和工具缓存不进入 Git。

## 快速开始

### 环境要求

- Android Studio（推荐最新稳定版）
- JDK 17
- Android SDK API 36

### 打开项目

使用 Android Studio 打开项目目录：

```text
nazhi-android
```

首次打开后执行 Gradle Sync。

### 构建验证

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 本地优先

纳知遵循 Local-First 原则：

- 笔记、知识条目、向量数据和对话记录优先存储在 Android 本地。
- 模型调用可走纳知后端代理，也可使用用户自配 API 服务。
- 支持离线浏览已保存内容。
- 用户可以导出、导入本地知识数据，导入后重建向量索引。

## 音频能力边界

- 系统音频捕获需要 Android MediaProjection 授权，且只能捕获第三方 App 允许被捕获的音频。
- 麦克风录音作为备用路径，适合外放、会议和环境声音；耳机场景可能录不到外部应用声音。
- 若第三方 App 禁止捕获或录制结果为空，纳知会提示失败并避免保存空 Note。
- 有效音频在上传或转写失败时会暂存；关联 Note 入库或删除后会清理原始音频。

## 项目状态

V2.1 第一批聚焦稳定性和结果正确性：

- 后端问答不再自动为缺少证据的答案补引用；后端及自配 API 模式都校验结果结构和来源摘录，信息不足时返回空摘要更新。
- 向量请求明确区分建库和查询，兼容旧客户端 metadata；校验向量维度、数值与条目身份，mock 使用独立模型标识。本地检索跳过损坏或维度不符的历史向量。
- 网络失败、服务临时错误和协程取消保留登录状态；用户请求收到 401 时刷新并重试一次。
- Embedding 与长音频 ASR 增加请求及响应体超时，长音频有总任务期限，客户端等待时间与后端默认期限配套。

后端验证：在 `nazhi-backend/` 执行 `npm test` 和 `npm run check`。Android 单元测试使用本地 HTTP 服务验证真实客户端请求，不访问模型厂商。

旧版本已经把 mock 向量标记为 `embo-01` 的记录无法自动区分于真实向量。切换到真实模型前，应重新生成受影响知识条目的索引；编辑并保存知识条目会重新索引。新的 mock 向量不会继续写入真实模型标识。

V2.1 第二批增加本地事务和中断恢复：

- 整理和录音在提交前持久化请求身份；重开应用、恢复网络或切回原服务配置时，只查询已有任务。请求创建响应丢失时，可通过 `requestId` 找回原任务。
- 录音转写、笔记保存和任务完成状态一次提交；重复回调、重复确认不会生成第二条笔记。应用内录音仍保留编辑预览和确认入口，关闭后可从对应日期继续确认。
- 草稿替换、知识确认、问答回答与引用、导入导出使用事务；等待整理期间修改的笔记和草稿会受到保护。中断的索引和问答明确显示为可重试状态。
- 后端按账号、任务类型和请求 ID 去重；容量或 TTL 清理不会删除运行中的任务。音频清理失败保留记录，后续启动再次尝试。

客户端与本目录后端需一起升级。服务器任务仍存于内存，重启或完成后的保留期限结束会导致任务丢失；应用会保留本地文字和录音并提示手动重试。重新调用模型由用户点击发起。直连模型没有可查询的任务身份，中断后同样需要手动重试。

迁移和恢复验证步骤见 [V2.1 任务恢复验证](docs/V2.1任务恢复验证.md)。后续再考虑服务器持久化队列，以及面向公开服务的鉴权和额度控制。

V2.1 第三批试用包为 **2.1.1（versionCode 3）**，重点是文字备份恢复与集中待处理入口：

- 新备份带内容完整性校验，导入前检查整份文件的结构和关联。恢复时重新核对本机数据，保留同 ID 的本机编辑与关联记录；新增内容在同一事务中写入并回读校验，失败全部回滚。兼容旧格式的有效备份。
- 备份页显示最近成功保存时间、内容截至时间和文件核验结果。文件选择器重建可继续保存；导出中进程丢失时解除忙碌并提示重导。旧问答引用和知识来源保留原文，不因知识后续编辑而拒绝备份。
- 顶部“待处理”集中显示不同日期的整理、知识处理和问答状态。可以查看原笔记、知识与原问题；查询原任务与再次调用模型有不同操作，重复点击不会创建第二次处理，离开页面后已发起的处理继续进行。

备份包含笔记、知识、草稿、问答与引用；不包含录音原文件、待确认转写、未完成任务身份、复习记录、向量或凭证。导入后问答中断项立即可重试，知识索引由用户手动重建。新版可读旧备份，旧版不能读取新版格式；升级前保留原版本导出的文件。

完整验证和荣耀 80 SE 升级准备见 [V2.1.1 备份与待处理验证](docs/V2.1.1备份与待处理验证.md)。

## 许可证

Private
