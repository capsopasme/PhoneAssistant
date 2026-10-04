# 语音助手（PhoneAssistant）

给一加 Ace 5（SM8650 / LineageOS / Android 16 / KernelSU）用的语音助手：离线语音识别 + 大模型 tool calling（DeepSeek / 智谱 GLM / Gemini 三选一），完成闹钟、电话、导航、系统开关这类常见操作。

## 用法

1. 打开“语音助手”（桌面图标进入设置）：
   - 在“大模型”里选一个（DeepSeek / 智谱 GLM / Gemini），填它的 API Key。只用选中的那一个，请求失败就直接报错，不会自动换别的模型。智谱默认 `glm-4.7-flash`（免费，Key 在 open.bigmodel.cn 申请）。
   - 语音模型：小企鹅输入法里已经装了同款 SenseVoice 模型的话，点“从小企鹅复制”（需要 root），否则“下载”。装好后点“测试”确认 NPU 能跑。
   - 点“设为默认助理（root）”，再点“授予权限”。
2. 系统设置 → 系统 → 手势 → 系统导航 → 手势导航旁的齿轮 → 打开“滑动调用助理”。
3. 从屏幕左下角或右下角斜向上滑：底部弹出卡片，直接说话。说完停顿 0.7 秒自动结束，也可以点方块按钮手动结束，或者点输入框打字。

QuickBall 等应用可以启动 Intent 动作 `com.capsopasme.assistant.START` 唤起同一个界面。

## 能做的事

| 类别 | 工具 |
|---|---|
| 时间 | 设闹钟（可重复）、倒计时、打开闹钟列表、新建日程（打开日历编辑页确认） |
| 联系人 | 按姓名查号码、打电话（先确认）、写短信（打开编辑页由你点发送） |
| 出行/信息 | 导航（优先高德，其次百度，再其次 geo:）、打开应用、浏览器搜索、查天气（Open-Meteo，无需 Key） |
| 媒体/声音 | 播放暂停上下首、各类音量、铃声/震动/静音、手电筒 |
| root 开关 | WiFi、蓝牙、移动数据、飞行模式、勿扰、定位、NFC、自动旋转、深色模式、省电模式、亮度、锁屏 |
| 查询 | 电量、充电、WiFi/蓝牙、铃声模式、勿扰、音量、剩余存储 |

root 命令全部由代码按固定模板拼出，模型只能选开关名和开/关，不能传任意命令。关 WiFi、关移动数据、开飞行模式会先弹确认。

## 省电和内存

- 没有任何常驻服务、前台服务、定时任务、WakeLock 或唤醒词。卡片关掉后整个 App 就没有在运行的线程了。
- 麦克风、识别会话、网络请求、工作线程都只在卡片显示期间存在；切到别的应用、锁屏都会立刻关掉卡片并释放。
- 识别在独立进程 `:asr` 里跑（NPU 初始化失败只会崩这个进程，界面会提示换 CPU 模型）。默认用完后保留模型：进程被系统冻结在后台缓存里，不占 CPU、不耗电，内存紧张时由系统回收，下次唤起不用重新加载。设置里可以改成每次用完立即释放。
- 听写有上限：7 秒没人说话自动结束，单次最长 25 秒；VAD 判断说完就停麦。
- 对话历史只保存在当前卡片里，关掉就没了；API Key 存在应用私有目录。

## 构建

原生库不在仓库里，CI 会从源码编 sherpa-onnx（QNN），跟小企鹅输入法 fork 用的是同一个脚本和版本（sherpa-onnx v1.13.8 + QNN 2.40）。

**GitHub Actions**：推到 `main` 或手动运行 “Build APK”，产物在 Actions 页面下载。仓库 Secrets 里配 `SIGN_KEY_BASE64` / `SIGN_KEY_ALIAS` / `SIGN_KEY_PWD`（可以直接复用 fcitx5-android 那套），否则每次用临时 debug 签名，后一个包装不上前一个。

**本地**：

```sh
export ANDROID_NDK=/path/to/ndk   # r27+
./voice/prepare-native.sh
./gradlew :app:assembleRelease
```

`com/k2fsa/sherpa/onnx/*.kt` 是 sherpa-onnx v1.13.8 的 Kotlin API 原样拷贝，JNI 按名字访问，升级 sherpa-onnx 时两边一起换。识别相关代码（`asr/`）改自 fcitx5-android 语音输入，保留 LGPL-2.1 头。
