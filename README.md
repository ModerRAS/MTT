# MTT — Machine Translation Tool

> Android 端 AI 批量翻译工具，基于 LLM API（OpenAI / Anthropic）进行高质量长文本翻译。  
> 灵感与设计参考自 [AiNiee](https://github.com/NEKOparapa/AiNiee) —— 一款专注于 AI 翻译的开源桌面工具。

---

## 项目简介

MTT（Machine Translation Tool）是一款运行在 Android 平台的 AI 辅助翻译应用。用户只需输入待翻译文本、选择 LLM 模型并配置 API Key，即可利用 OpenAI / Anthropic 等大语言模型进行批量翻译，支持多轮并发、术语管理、自定义提示词等进阶功能。

本项目的核心流水线设计（分块 → 并发调用 → 校验 → 失败拆分重试）及术语表引擎等核心思路，均参考并致敬了 [AiNiee](https://github.com/NEKOparapa/AiNiee) 的设计。AiNiee 是 NEKOparapa 开发的一款功能强大的 AI 翻译桌面工具，支持游戏、书籍、字幕、文档等多种格式，是 MTT 在翻译策略上的重要参考实现。

## 功能特性

- **多 LLM 支持** — 集成 OpenAI（GPT-4o / GPT-4o-mini 等）与 Anthropic（Claude 3.5 Sonnet / Haiku 等）
- **批量翻译** — 支持文本列表批量提交，自动分块并发处理
- **智能流水线** — 限流控制 → 并发调用 → JSON 校验 → 失败自动拆分重试（最多 3 次）
- **术语管理** — AI 术语表引擎，确保特定词汇按需翻译或保留
- **提示词定制** — 支持翻译、润色、校对三种模式，可自定义 prompt 模板
- **安全存储** — API Key 使用 EncryptedSharedPreferences 加密存储
- **本地缓存** — Room 数据库缓存翻译结果与术语表
- **前台服务** — 通知栏翻译进度展示，支持后台持续运行
- **Material 3 设计** — Jetpack Compose 构建的现代化 UI

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| 数据库 | Room (SQLite) |
| 网络 | OkHttp + Retrofit |
| LLM SDK | openai-java, anthropic-java |
| 安全 | EncryptedSharedPreferences |
| 测试 | JUnit 5, MockK, MockWebServer, Robolectric |
| 构建 | Gradle KTS (Kotlin DSL) |

## 项目结构

```
app/src/main/java/com/mtt/app/
├── core/            # 错误体系、日志工具
├── data/            # 数据层（网络、本地、缓存、安全）
│   ├── cache/       # 缓存管理器
│   ├── io/          # 文件读写与 JSON 校验
│   ├── llm/         # 限流器、Token 预估、重试策略
│   ├── local/       # Room 数据库与 DAO
│   ├── model/       # 实体与配置 DTO
│   ├── network/     # HTTP 客户端与拦截器
│   ├── remote/      # LLM SDK 封装
│   │   └── llm/     # LlmService 接口 + 工厂 + 实现
│   └── security/    # 安全存储
├── di/              # 6 个 Hilt 模块
├── domain/          # 纯业务逻辑（无 Android 依赖）
│   ├── glossary/    # 术语引擎
│   ├── pipeline/    # 翻译流水线（核心编排）
│   ├── prompt/      # 提示词构建器
│   └── usecase/     # 用例层
├── service/         # 前台翻译服务
└── ui/              # Compose 屏幕 + ViewModel
    ├── translation/ # 翻译主页
    ├── glossary/    # 术语管理
    ├── settings/    # 设置页
    ├── result/      # 结果查看
    └── theme/       # Material 3 主题
```

## 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1+) 或更高版本
- JDK 17+
- Android SDK 35
- Gradle 8.9

### 构建与运行

```bash
# 克隆项目
git clone https://github.com/your-username/MTT.git
cd MTT

# Debug 构建
.\gradlew.bat assembleDebug

# 安装到设备
.\gradlew.bat installDebug

# 运行单元测试
.\gradlew.bat testDebugUnitTest

# Lint 检查
.\gradlew.bat lintDebug
```

### 配置 API Key

首次打开应用后，进入 **设置** 页面，配置 OpenAI 或 Anthropic 的 API Key。  
Key 将通过 `EncryptedSharedPreferences` 安全加密存储于本地。

## 翻译工作流

1. **输入文本** — 在翻译页输入或粘贴待翻译内容
2. **配置参数** — 选择 LLM 模型、源语言、目标语言与翻译模式
3. **开始翻译** — 触发后台 Service，进入 9 步流水线：
   - 预处理 → 构建 Prompt → Token 预估 → 限流等待 → 并发 API 调用
   - → JSON 校验 → 失败自动拆分重试（50/50，最多 3 次）→ 结果提取 → 结果合并
4. **查看结果** — 在结果页查看译文，支持复制与分享

## 参考与致谢

MTT 的设计与实现深受以下项目启发：

- **[AiNiee](https://github.com/NEKOparapa/AiNiee)** — 由 NEKOparapa 开发的 AI 翻译桌面工具，支持游戏、书籍、字幕等多种格式的一键翻译。MTT 的翻译流水线策略（分块处理、失败重试、术语替换）均参考了 AiNiee 的设计。

## 许可证

[GNU General Public License v3.0](LICENSE)
