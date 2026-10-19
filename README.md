# 纳知 Nazhi

把手机里转瞬即逝的好内容，养成一座可以追问的知识农场。

**V2.1.1** · Android 8.0+ · 本地优先 · Kotlin / Jetpack Compose

通过分享、剪贴板或悬浮窗收纳文字与音频，将碎片整理成知识，再通过搜索与带来源的问答重新找到它们。

**收纳 → AI 整理 → 确认入库 → 检索 / 问答**

## 能做什么

| 功能 | 使用体验 |
| --- | --- |
| 快速收纳 | 手动输入、系统分享、选中文字、剪贴板与悬浮窗；支持录音转写 |
| 整理入库 | 碎片生成知识草稿，编辑并确认后保存为知识条目 |
| 回顾查找 | 像素知识农场展示每日状态，日历回看历史，知识库支持筛选与语义搜索 |
| 知识问答 | 根据已保存知识回答问题，保留对话并回看引用来源 |
| 待处理 | 集中查看跨日期的整理、知识处理与失败问答，继续查询或手动重试 |
| 备份恢复 | 导出文字数据，导入前预览冲突、保留本机修改，显示最近备份与文件核验结果 |

## 界面预览

以下为现有界面截图，新版增加了顶部“待处理”入口与备份核验功能。

| 今日 | 日历 | 问答 | 知识库 |
| --- | --- | --- | --- |
| <img src="docs/app_show/today.jpg" alt="今日知识农场与收件箱" width="190" /> | <img src="docs/app_show/calendar.jpg" alt="按日期回顾收纳记录" width="190" /> | <img src="docs/app_show/qa.jpg" alt="知识问答与来源引用" width="190" /> | <img src="docs/app_show/knowledge.jpg" alt="知识条目与搜索" width="190" /> |

<details>
<summary>查看跨 App 收纳</summary>

| 粘贴收纳文字 | 录制 / 转写音频 |
| --- | --- |
| <img src="docs/app_show/pastecapture.jpg" alt="通过悬浮窗粘贴保存文字" width="260" /> | <img src="docs/app_show/audiocapture.jpg" alt="通过悬浮窗录制和转写音频" width="260" /> |

</details>

## V2.1.1 更新

- **任务可继续**：整理与转写保存请求身份，重开应用后查询原任务；需要重新调用模型时由用户手动发起。
- **保存更可靠**：笔记、草稿、知识与问答采用事务写入，防止重复保存，并保护处理期间的编辑。
- **备份可核验**：新增完整性校验、冲突预览、导入失败回滚与导出文件读回检查。
- **异常有反馈**：加固登录续期、向量和引用校验，以及网络与音频任务超时处理。

## 快速开始

以下命令以 PowerShell 为例。客户端使用 Android Studio、Android SDK 36；已在 JDK 21 环境验证。后端需要 Node.js 22+。

### 1. 启动后端

```powershell
cd nazhi-backend
npm ci
Copy-Item .env.example .env
# 本地模拟联调：将 .env 的 DATABASE_URL、REDIS_URL 留空，保留 NAZHI_DEV_TOKEN
npm start
```

默认监听 `http://localhost:8787`，默认 AI 提供方为 `mock`，仅返回模拟结果。使用真实 AI 需配置相应服务；登录注册还需配置 PostgreSQL、Redis、JWT 并执行数据库迁移，见 [后端说明](nazhi-backend/README.md)。

### 2. 构建客户端

用 Android Studio 打开仓库根目录并完成 Gradle Sync；也可在根目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。

在 App 设置中填写后端地址：Android 模拟器可用 `http://10.0.2.2:8787`，真机填写可访问的服务器地址。开发联调可取消登录，“服务访问 Token”须与后端 `NAZHI_DEV_TOKEN` 一致；也可选择自配 API 服务。

## 数据与使用边界

- **本地保存**：笔记、知识与对话保存在手机；已保存内容可离线浏览。AI 整理、索引、转写与问答会向所选服务发送相关内容，账号不提供知识库云同步。
- **备份范围**：包含笔记、知识、草稿、问答与引用；不包含录音原文件、待确认转写、未完成任务身份、复习记录、向量、凭证或配置。导入后需手动重建知识索引。
- **恢复边界**：服务端任务仍在内存中，服务器重启或任务过期后需手动重试；直连模型中断后同样需手动重试。
- **音频捕获**：系统音频录制需要授权，并受来源 App 是否允许捕获的限制。
- **升级状态**：客户端与配套后端需一起升级；已完成自动测试与模拟器验证，真实手机和服务器仍需升级验收。覆盖安装前备份数据并保留原签名。

## 代码与文档

客户端使用 Kotlin、Compose、Room 与 Coroutines；后端使用 Node.js，账号服务依赖 PostgreSQL 和 Redis。

```text
Nazhi/
├── app/             # Android 客户端与测试
├── nazhi-backend/   # AI 接口、账号服务与测试
├── docs/            # 界面截图、设计与验收说明
└── scripts/         # 发布辅助工具
```

- [后端配置与启动](nazhi-backend/README.md) · [API 接口](nazhi-backend/API.md)
- [任务恢复与迁移验证](docs/V2.1任务恢复验证.md)
- [备份、待处理与升级验收](docs/V2.1.1备份与待处理验证.md)
- [像素 UI 资产规则](docs/V1.4%20UI资产规则.md)
