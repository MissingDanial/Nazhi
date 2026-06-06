# 纳知 Nazhi Android

纳知 —— 基于 AI 的个人知识管理 Android 客户端。通过悬浮球一键收纳碎片知识，结合 RAG 语义检索与大模型对话，让知识不再遗忘。

## 核心功能

### 1. 知识捕获

多入口快速捕获文字内容，随时随地记录灵感与信息：

- **悬浮球捕获**：全局悬浮球覆盖层，复制文字后点击悬浮球一键保存，无需切换应用
- **系统音频转写（实验）**：Android 10+ 通过系统授权捕获可被捕获的播放音频，优先适配耳机场景
- **悬浮球麦克风转写**：作为外放、环境声音和系统音频不可用时的备用录音路径
- **音频失败暂存**：上传或转写失败时保留有效音频，今日页可统一重试；生成 Note 后在入库或删除时清理原始音频
- **系统分享**：从任意应用通过系统分享菜单直接发送到纳知
- **Direct Share**：系统分享面板直接显示纳知快捷入口
- **文本选择捕获**：选中文字后通过 `ACTION_PROCESS_TEXT` 菜单直接收纳
- **剪贴板捕获**：`ClipboardCaptureActivity` 自动读取剪贴板内容并保存

### 2. 今日收件箱

按日期管理每日捕获的碎片知识：

- 查看当日所有笔记列表
- 新建、编辑、复制、删除笔记
- AI 一键整理：调用大模型将当日碎片笔记自动整理为结构化知识草稿
- 整理完成后自动跳转知识库查看草稿

### 3. 日历视图

以日历形式回顾历史记录：

- 按日期查看历史笔记
- 标记有记录的日期
- 标记有待回顾内容的日期
- 点击日期查看当日详情

### 4. 知识库

AI 驱动的知识管理核心：

- **AI 草稿确认**：AI 整理后的草稿需用户确认后才正式入库
- **草稿操作**：支持编辑、跳过、提交单条或批量提交
- **KnowledgeEntry 入库**：确认后的知识条目持久化存储
- **本地 Embedding**：基于本地引擎生成向量嵌入，存储为 BLOB
- **语义检索**：基于向量相似度的语义搜索
- **索引状态追踪**：显示每条知识的索引构建状态
- **知识详情查看**：查看知识条目完整内容与元数据
- **重复检测**：入库前检测重复知识，避免冗余

### 5. RAG 智能问答

基于检索增强生成（RAG）的知识问答：

- 输入问题后，先从本地知识库进行 TopK 语义检索
- 将检索到的相关知识作为上下文发送给大模型
- 大模型基于知识库内容生成回答
- 展示可点击的引用来源，追溯原始笔记
- 支持多轮对话，保留聊天历史

### 6. 设置与配置

灵活的后端与模型配置：

- **后端模式**：纳知云服务模式 或 自带 API Key 模式
- **API Key 管理**：加密存储用户的 API Key（`EncryptedSettingsStore`）
- **后端地址配置**：自定义后端服务 URL
- **连接测试**：测试后端服务连通性
- **数据导出**：将本地知识数据导出为 JSON 文件
- **数据导入**：从 JSON 文件导入知识数据，导入后自动重建向量索引
- **悬浮球开关**：控制悬浮球服务的启停

### 7. V1.2 音频模式边界

- 系统音频模式优先服务耳机场景，需要系统 MediaProjection 授权。
- 麦克风模式保留为备用路径，适合外放、会议和环境声音，但耳机场景可能录不到短视频声音。
- 系统音频模式授权前会先显示纳知说明页，说明系统授权弹窗与音频捕获边界。
- 系统音频模式只捕获允许被捕获的媒体音频；若第三方 App 禁止捕获或得到静音，录制结束后会提示失败并避免保存空 Note。
- 两种模式都复用同一个后端 ASR Job 接口，转写完成后保存为今日 `AUDIO_TRANSCRIPTION` Note。
- 有效音频在上传或转写失败时会暂存，用户可在今日页重试；原始音频不进入知识库和导出数据，关联 Note 入库或删除后自动清理。
- 今日收件箱会展示音频来源、录音时长、保存时间和状态，复制、编辑、删除继续复用普通 Note 能力。

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
- API 36.1
- V1.4 UI 资产位于 `app/src/main/res/drawable-nodpi/`

### 数据模型

| 模型 | 说明 |
|------|------|
| `Note` | 原始笔记，按日期分组 |
| `KnowledgeEntry` | 确认入库的知识条目 |
| `KnowledgeEntryDraft` | AI 整理后的待确认草稿 |
| `EmbeddingRecord` | 知识条目的向量嵌入记录 |
| `ChatSession` / `ChatMessage` | 对话会话与消息 |
| `ChatCitation` | 回答中的引用来源 |
| `FarmPlot` | 农场地块状态（V1.4 新增） |
| `CropType` | 作物类型与生长阶段（V1.4 新增） |

### 模块结构

```
app/src/main/java/com/nazhi/app/
├── core/
│   ├── capture/          # 捕获服务（悬浮球、剪贴板）
│   ├── audio/            # WAV 录音与音频转写基础能力
│   ├── database/         # Room 数据库、DAO、Entity
│   ├── embedding/        # 本地向量嵌入引擎
│   ├── export/           # 数据导入导出
│   ├── model/            # 数据模型
│   ├── network/          # 后端 API 客户端
│   ├── repository/       # 数据仓库层
│   ├── settings/         # 设置存储
│   └── ui/               # V1.4 UI 组件与主题
│       ├── NazhiComponents.kt  # 像素风 UI 组件
│       └── NazhiTheme.kt       # 主题配置与 token
├── feature/
│   ├── calendar/         # 日历视图（V1.4 新增）
│   ├── chat/             # RAG 问答对话
│   ├── farm/             # 知识农场（V1.4 新增）
│   ├── home/             # 主页导航
│   ├── inbox/            # 今日收件箱
│   ├── knowledge/        # 知识库管理
│   └── settings/         # 设置页面
├── MainActivity.kt
├── NazhiApp.kt           # 应用入口与导航
├── ClipboardCaptureActivity.kt
├── AudioTranscriptionActivity.kt
├── AudioTranscriptionPermissionActivity.kt
├── AudioTranscriptionService.kt
├── SystemAudioCapturePermissionActivity.kt
├── FloatingCaptureService.kt
├── ProcessTextCaptureActivity.kt
└── ShareCaptureActivity.kt
```

## 快速开始

### 环境要求

- Android Studio（推荐最新稳定版）
- JDK 17
- Android SDK API 36

### 打开项目

使用 Android Studio 打开项目目录：

```
nazhi-android
```

首次打开后执行 Gradle Sync。

### 构建验证

```bash
# 编译检查
./gradlew :app:compileDebugKotlin

# 构建 Debug APK
./gradlew :app:assembleDebug
```

生成的 Debug APK 位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 设计理念

纳知遵循 **本地优先（Local-First）** 原则：

- 所有笔记、知识条目、向量数据和对话记录均存储在 Android 本地
- 模型调用可走纳知后端代理，也可使用用户自配的 API 服务
- 用户数据完全掌控在自己手中
- 支持离线使用知识捕获和浏览功能

## 许可证

Private
