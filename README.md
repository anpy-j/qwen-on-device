# Qwen 端侧 Demo（Android）

在 Android 平板/手机本地运行的端侧 AI 助理 Demo：大模型推理、离线语音识别全程不出设备，100% 隐私安全、0 流量消耗。

- 推理框架：MediaPipe LLM Inference（LiteRT / tasks-genai 0.10.23），无需 NDK 编译
- 大模型：litert-community/Qwen2.5-0.5B-Instruct（q8 量化，521MB，峰值内存约 1.4GB）
- 语音：Sherpa-ONNX 离线实时语音识别（中文，模型随包内置）
- UI：原生 Kotlin + XML，Material 3 深色玻璃拟态设计（设计系统见 `design-system/`）

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 智能记账 | 自然语言记账，自动解析金额/类别/支付方式，落 SQLite，实时资产看板 |
| 闹钟日程 | 自然语言创建系统日历事件 / 闹钟 / 本地备忘 |
| 合同速读 | 租赁/开发/NDA 等合同要点提取 |
| 会议纪要 | 语音或文本输入，自动归档纪要 |
| 私密日记 | 本地加密存储，端侧情绪识别 |
| 反诈雷达 | 涉诈短信深度研判，高危风险标红预警 |

## 克隆

仓库中的大模型文件（`*.task` / `*.onnx`）通过 Git LFS 管理，克隆前先安装 LFS：

```bash
git lfs install
git clone https://github.com/anpy-j/qwen-on-device-demo.git
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

模型已随包内置，装完即可离线使用。

## 换更大/更小模型

改 `MainActivity.kt` 里的 `modelUrl` 和 `modelFile`，换成其他
[litert-community](https://huggingface.co/litert-community) 下的 .task 文件即可
（如 Qwen2.5-1.5B / 3B），注意设备内存是否够用。

## 截图

深色模式实测（模拟器 2560x1600 平板）：

- 记账（智能记账 E2E：自然语言 → 解析 → 落库 → 看板刷新）
- 反诈雷达（涉诈短信研判 → 高危预警）

见 `docs/screenshots/`。
