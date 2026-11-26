# ESP-FlashBurn

## 安装

直接下载安装包：[**ESP-FlashBurn-v2.0.1.apk**](release/ESP-FlashBurn-v2.0.1.apk)

- 版本 2.0.1（versionCode 205），arm64-v8a / armeabi-v7a，Android 6.0 及以上
- 首次安装需允许「安装未知来源应用」；应用名为 `ESP-FlashBurn`，包名 `com.espflash.burn`

---

安卓平台上的 ESP 芯片烧录工具。界面用 Kotlin 写，底层通过 JNI 调用官方 `esp-serial-flasher` 完成复位、握手、擦除和写入，宿主机侧不做任何命令中转，插上 Type-C 就能直接烧。

- 应用名：ESP-FlashBurn
- 包名 / namespace：`com.espflash.burn`
- 版本：2.0.1（versionCode 205）
- 最低系统：Android 6.0（minSdk 23），targetSdk 33
- ABI：arm64-v8a、armeabi-v7a

## 功能

**烧录页**
- 自动识别原生 USB（`303A:1001` USB-Serial-JTAG）与 CH340 等 UART 桥，原生 USB 走裸端点直读，不依赖 usb-serial-for-android 的 1 字节读路径。
- 自动进入下载模式：原生 USB 用 `usb_jtag_bootloader_reset` 的 DTR/RTS 时序，UART 桥用 DTR=IO0 / RTS=EN 复位后在端口中转。
- 握手带短探测：先 200ms 探测芯片是否已在下载模式，失败再复位重连，正常几十毫秒完成，冷启动约 3 秒。
- 设备芯片擦除：强制走 stub 整片擦除，擦完回读 `0x0 / 0x8000 / 0x10000 / 末尾扇区` 确认全 `0xFF`；成功/失败各有专用弹窗，可复制完整日志。
- 写入块自适应：原生 USB 上限 16 KB，被拒或首块超时按 16K → 8K → 4K 降档，成功后后续文件从该档起步。
- 固件库：多选（CheckBox），未勾选的固件不烧；每行可单独设偏移；空库时点「管理固件 / 刷写固件」直接弹系统文件选择器。
- 日志按 esptool 风格输出成单列文本，每行 `[分:秒]` 时间戳；库内部的 `[2]/[3] 读 #N 失败`、复位明细、接口拓扑等噪声只进原始缓冲，不上屏。失败、超时、被拒、断开这些关键错误才上屏。
- 「复制日志」取全量原始日志（含被过滤的行），排错时不会丢信息。
- 烧录开始时只收起日志区顶部的固件块，结束不自动展开；手动选固件才清屏并展开。

**串口页**
- 打开端口后按行镜像输出，时间戳 `mm:ss`。
- 垃圾桶按钮 / 菜单复位 / 连接后自动复位统一走清屏：清屏幕、清缓冲、清输入队列。
- 「复制串口日志」取末尾 60 条完整行，按换行边界截断，保证首行完整。

**网页后台**
- 内置 WebView 打开设备自身网页，缩放固定 100%。
- 支持网页内 `<input type="file">` 选择文件（自定义 `WebChromeClient` + `StartActivityForResult`）。

## 目录结构

```
app/src/main/java/com/espflash/burn/   应用代码（Kotlin）
    FlashFragment.kt      烧录页：状态机、复位、握手、日志
    SerialFragment.kt     串口页：打开、镜像、清屏、复制
    SerialLink.kt         USB 端点读写、raw/驱动双路径、复位后重开
    NativeFlasher.kt      JNI 声明与错误码映射
    MainActivity.kt       页签、网页后台、文件选择器
    CrashStore.kt         日志与崩溃文件落盘（Android/media/ESP-FlashBurn）
    ...
app/src/main/java/com/hoho/android/usbserial/   第三方 USB 串口驱动（CH34x、PL2303 等）
app/src/main/cpp/
    jni_bridge.c          JNI 桥接，导出 Java_com_espflash_burn_*
    android_port.c        平台移植层：端点打开/读写超时、DTR/RTS
    esp-serial-flasher/   上游库自带副本
    CMakeLists.txt        原生构建脚本
extern/esp-serial-flasher/   上游库完整克隆（存在时优先使用）
keystore/espburn.jks          release 签名库
```

## 构建

环境：JDK 17、Android SDK（compileSdk 33）、NDK（CMake 3.22.1）、Gradle 7.6、AGP 7.4.2、Kotlin 1.8.22。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

gradle :app:assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-release.apk`。

原生库来源由 `CMakeLists.txt` 的 `ESP_FLASHER_SRC` 控制：`auto`（默认，`extern/` 存在就用官方仓库，否则用工程内副本）、`upstream`、`vendored`。只保留一份时删掉 `extern/esp-serial-flasher` 即可回落到自带副本。

## 签名

仓库不包含签名库。要出可安装的 release 包，在工程根目录放一个 `keystore.properties`（已加入 `.gitignore`）：

```properties
storeFile=keystore/your.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

`app/build.gradle` 会读取该文件；没有它时 `assembleRelease` 产出的是未签名包，自行用 `apksigner` 签一下即可。仓库里 `release/` 下的 APK 是已签名产物，仅供直接安装。

## 运行时文件

- 日志/崩溃记录：`/storage/emulated/0/Android/media/ESP-FlashBurn/Bug.txt`
- 旧版目录 `Android/media/ESPburn` 在首次启动时自动迁移。
- 需要存储权限（Android 10 及以下为写外部存储，11 以上走 Media 目录）。

## 已知限制

- 设置里的型号 / 频率 / 模式 / 大小目前只是界面参数，尚未真正传入烧录流程，实际按芯片自动识别走默认值。
- applicationId 为 `com.espflash.burn`，与早期版本不互通，安装后是新应用，不覆盖旧版，也不继承旧日志与偏好。
- 「读取当前固件」的产物落在应用私有 `read/` 目录，未导出到公共目录。
- 写入结束不做整片 MD5 回读校验，写完直接复位；掉块只能靠设备运行表现判断。
- CH340 串口路径受波特率限制（默认 115200），16 MB 整包写入较慢，建议用原生 USB。
