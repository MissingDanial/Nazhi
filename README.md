# Nazhi Android

纳知 V1 Android 客户端。

## 当前范围

- 今日：本地文本保存、分享快速收纳、剪贴板收纳、编辑、复制、删除和 AI 整理入口。
- 日历：按日期查看历史记录，标记有记录和待回顾日期。
- 问答：基于本地知识库 TopK 检索后调用 AI 生成回答，并展示可点击引用。
- 知识库：AI 草稿确认、KnowledgeEntry 入库、本地 embedding BLOB、语义检索、索引状态和知识详情。
- 设置：纳知服务 / 自带 API Key 模式、本地导出、本地导入、导入后重建索引、悬浮球权限与开关。
- 捕获入口：系统分享、Direct Share、`ACTION_PROCESS_TEXT` 兼容入口、悬浮球粘贴保存。

V1 仍保持本地优先：Note、KnowledgeEntry、向量和问答记录保存在 Android 本地；模型调用可走纳知后端代理或用户本机保存的 API 服务配置。

## 工程环境

- Kotlin
- Jetpack Compose
- Room
- DataStore
- Coroutines
- 手写 `AppContainer`，V1 暂不引入 Hilt
- AGP 9.1.0
- Gradle 9.3.1
- JDK 17
- API 36.1

## 打开方式

使用 Android Studio 打开本目录：

```text
.\nazhi-android
```

首次打开后执行 Gradle Sync。

## 常用验证

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

生成的 debug APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

