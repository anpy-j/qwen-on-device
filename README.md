# Qwen 端侧英语学习（Android）

在 Android 平板/手机本地运行的端侧英语学习应用：大模型推理、离线词典、离线 TTS 与离线语音识别全程不出设备，100% 隐私安全、0 流量消耗。

- 推理框架：MediaPipe LLM Inference（LiteRT / tasks-genai 0.10.23），无需 NDK 编译
- 大模型：litert-community/Qwen2.5-0.5B-Instruct（q8 量化，约 523MB，峰值内存约 1.4GB）
- 词典：ECDICT 高频词库内置（9733 词，`assets/dict/words.json`），未收录词由本地 LLM 现场解释
- 语音合成：Sherpa-ONNX OfflineTts + Piper `en_US-lessac-medium`（VITS，模型随包内置，`assets/piper/`）
- 语音识别：Sherpa-ONNX 离线实时识别（中英双语 zipformer，模型随包内置）
- UI：原生 Kotlin + XML，Material 3 深色玻璃拟态设计

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 离线词典 | 输入/语音说出单词 → 音标、释义、英文释义、词形变化、AI 生成例句，一键播放发音、加入单词本 |
| 口语陪练 | 本地 LLM 扮演母语伙伴 Mia 进行对话，说错自动 `[纠错]` 并朗读，保持话题跟进 |
| 句子纠错 | 英文原句或中文意图输入 → 正确表达 / 逐条讲解 / 中文意思 / 备选写法，改完自动朗读 |
| 单词本 | 间隔重复记忆（SRS，0/1/3/7/15/30 天梯度），忘记/模糊/认识三档评分，到期逐词复习 |
| 跟读听力 | 端侧 TTS 示范发音 + 离线识别后逐词高亮差距（LCS 对齐），支持慢速重播、AI 出题 |

## 目录结构

```
app/src/main/
├── assets/
│   ├── qwen.task                 # Qwen2.5-0.5B q8 LLM（LFS）
│   ├── dict/words.json           # 内置词典（9733 高频词）
│   ├── piper/                    # 英文 TTS（Piper lessac-medium + espeak-ng-data，LFS）
│   └── sherpa/                   # 中英双语 ASR 模型（LFS）
├── libs/sherpa-onnx-1.13.7.aar   # 官方 AAR：离线 ASR + OfflineTts（LFS）
└── java/com/example/qwenondevice/
    ├── MainActivity.kt           # 单 Activity，五大功能模块
    ├── TtsManager.kt             # Piper TTS 引擎封装（assets→filesDir→OfflineTts→WAV→MediaPlayer）
    ├── Dictionary.kt             # 内置词库加载与查询
    ├── AppDatabaseHelper.kt      # SQLite：单词本（SRS）+ 例句缓存
    ├── VoiceModelManager.kt      # ASR 模型加载
    └── RealtimeVoiceManager.kt   # 实时语音识别
```

## 克隆

仓库中的大模型文件（`*.task` / `*.onnx` / `*.aar`）通过 Git LFS 管理，克隆前先安装 LFS：

```bash
git lfs install
git clone https://github.com/anpy-j/qwen-on-device.git
```

## 构建

```bash
./gradlew assembleDebug   # APK 输出在 app/build/outputs/apk/debug/
```

Debug 构建无需任何额外配置。Release 签名信息（`storePassword` / `keyAlias` /
`keyPassword`）放在 `local.properties`（已 gitignore，不入库），并配合
`app/release.jks` 使用；缺省时 `assembleRelease` 以未签名包输出。

## 运行

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.qwenondevice/.MainActivity
```

首次启动会从 APK 解包 LLM 到应用数据目录（约 523MB，需要 30–60 秒），
词典与 TTS 引擎后台加载完毕后即可离线使用。麦克风权限用于语音输入。

## 换更大/更小模型

改 `MainActivity.kt` 里的 `ASSET_MODEL_FILENAME` / `MODEL_SIZE_BYTES`，
并替换 `assets/qwen.task`（换成其他
[litert-community](https://huggingface.co/litert-community) 下的 .task 文件，
如 Qwen2.5-1.5B / 3B），注意设备内存是否够用。
