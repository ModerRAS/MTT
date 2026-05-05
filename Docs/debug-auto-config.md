# Debug Auto-Configuration

> 在 ADB 连接的设备/模拟器上免 UI 操作快速配置 API 凭据并自动启动翻译测试。

## 原理

`MTTApplication.onCreate()` 启动时会检查设备上的 `/data/local/tmp/mtt_debug_config.json`。如果存在，则：

1. 读取 JSON 中的 API Key、Base URL、模型 ID → 写入 `SecureStorage`（EncryptedSharedPreferences）
2. 读取测试 JSON 文件 → 设置待翻译文本
3. `TranslationViewModel` 初始化时检测到待翻译文本 → 自动启动翻译 pipeline
4. **配置 JSON 在读取后立即删除**，避免下次启动重复配置

整个过程不需要打开设置页面或点击任何按钮。

## 使用方法

### 1. 创建配置 JSON

```json
{
    "openai_api_key": "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "openai_base_url": "https://your-api-endpoint.example.com/v1",
    "openai_model": "deepseek-v4-flash",
    "test_json_path": "/data/local/tmp/test_translation.json"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `openai_api_key` | ✓ | OpenAI 兼容 API 的密钥 |
| `openai_base_url` | ✓ | API 基础地址，不要包含 `/chat/completions`，程序会自动拼接 |
| `openai_model` | ✓ | 模型 ID（需在 ModelRegistry 中有预设，或已添加为自定义模型） |
| `test_json_path` | - | 测试用的 MTool 格式 JSON 文件路径 |

> **注意**: 如果 API 使用 `/v1/chat/completions` 路径（标准 OpenAI 兼容格式），`openai_base_url` 需要包含 `/v1` 后缀，例如 `https://llm.miaostay.com/v1`。程序会自动拼接 `/chat/completions`。

### 2. 创建测试 JSON

MTool 格式：扁平键值对，key 和 value 都是字符串，value 初始与 key 相同。

```json
{
    "こんにちは": "こんにちは",
    "おはようございます": "おはようございます",
    "ありがとうございます": "ありがとうございます"
}
```

### 3. 推送到设备

```bash
# 推送配置 JSON
adb push mtt_debug_config.json /data/local/tmp/

# 推送测试 JSON
adb push test_translation.json /data/local/tmp/

# 可选：清除之前的数据（首次使用或需要重置时）
adb shell pm clear com.mtt.app

# 启动应用
adb shell am start -n com.mtt.app/.MainActivity
```

### 4. 观察结果

```bash
# 实时查看翻译日志
adb logcat | Select-String "MTT|Translation|OpenAi"
```

成功时可见：
```
I/MTT-MTTApplication: Auto-configured OpenAI API key
I/MTT-MTTApplication: Auto-configured OpenAI base URL
I/MTT-MTTApplication: Auto-configured OpenAI model
I/MTT-MTTApplication: Auto-loaded test JSON: test_translation.json (3 entries)
I/MTT-MTTApplication: Debug config applied and config file deleted
D/OkHttp: --> POST https://llm.miaostay.com/v1/chat/completions
D/OkHttp: <-- 200 ...
```

### 5. 导出翻译结果

翻译完成后，应用 UI 会显示完成状态。在 TranslationScreen 点击「导出」按钮即可保存翻译后的 JSON。

如需通过 ADB 查看翻译结果，翻译完成后会在 Service 中写入 `CacheManager`（Room 数据库），或通过 UI 导出。

## 配置字段说明

### OpenAI 配置

| 配置字段 | SecureStorage Key | 说明 |
|----------|-------------------|------|
| `openai_api_key` | `key_openai` | API 密钥，通过 `saveApiKey/ getApiKey` 存取 |
| `openai_base_url` | `openai_base_url` | 基础 URL，通过 `saveValue/ getValue` 存取 |
| `openai_model` | `key_openai_model` | 模型 ID，通过 `saveApiKey/ getApiKey` 存取 |

### Anthropic 配置（预留）

当前仅支持 OpenAI 兼容 API 的自动配置。如需支持 Anthropic，可在 `tryAutoConfigure()` 中添加对应字段：

- `anthropic_api_key` → `PROVIDER_ANTHROPIC`
- `anthropic_base_url` → `KEY_ANTHROPIC_BASE_URL`
- `anthropic_model` → `KEY_ANTHROPIC_MODEL`

## 安全说明

1. **配置 JSON 在读取后自动删除**，不会残留在设备上
2. **源码中不硬编码任何密钥**，所有凭据仅存在于运行时推送的设备文件中
3. 该功能仅在 `/data/local/tmp/mtt_debug_config.json` **存在时**才激活，普通用户不会触发
4. `tryAutoConfigure()` 的异常被 `try-catch` 捕获，不会导致应用崩溃
5. 调试功能使用 `android.util.Log` 直接输出，不影响正常日志体系

## 文件清单

| 文件 | 作用 |
|------|------|
| `MTTApplication.kt` | 启动时读取配置 JSON，写入 SecureStorage，设置 pendingAutoLoad |
| `TranslationViewModel.kt` | init 时检测 pendingAutoLoad，自动加载文本并启动翻译 |

## CI 提醒

在推送代码前，确认已删除项目目录下的测试 JSON 和配置 JSON：

```bash
# 检查是否有未跟踪的 json 文件
git status

# 确认源码中无密钥残留
grep -r "sk-YourKey\|example\.com" app/src/main/java/
```
