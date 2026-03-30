# trans_audio

一个基于 Android + Kotlin + FFmpeg 的本地音视频转 MP3 工具。

## 功能

- 选择本地音频或视频文件
- 统一转换为 `MP3`
- 支持 MP3 码率选择：
  - `128 kbps`
  - `192 kbps`
  - `320 kbps`
- 输出目录固定为：
  - `Download/audio_autotrans`
- 支持从系统“打开方式 / 分享”入口接收文件
  - 例如微信里的“更多打开方式”
- 内置“已转换 MP3 播放页”
  - 可读取 `Download/audio_autotrans` 下的 mp3 并直接播放

## 当前行为

- 只要输入文件中存在音轨，就会尝试转成 MP3
- 输入可以是常见音频文件或带音轨的视频文件
- 如果源文件没有音轨，则不会输出 MP3

## 项目结构

- `MainActivity.kt`
  - 主转换页面
  - 外部 `Intent` 接入
  - FFmpeg 转 MP3 流程
  - 输出到 `Download/audio_autotrans`
- `PlayerActivity.kt`
  - 已转换 MP3 列表和播放页面
- `StorageConfig.kt`
  - 下载目录常量

## 本地运行

要求：

- Android Studio
- JDK 17
- Android SDK

构建调试包：

```powershell
.\gradlew.bat assembleDebug
```

构建 release：

```powershell
.\gradlew.bat assembleRelease
```

## Release 签名

当前项目默认 `assembleRelease` 只生成未签名包：

- `app/build/outputs/apk/release/app-release-unsigned.apk`

本地已有 keystore：

- `release-keystore.jks`
- alias: `app1release`

已签名 release APK 的生成流程：

1. 执行 `assembleRelease`
2. 使用 `zipalign` 处理 `app-release-unsigned.apk`
3. 使用 `apksigner` 基于现有 keystore 签名
4. 使用 `apksigner verify --verbose` 验签

签名后的 APK 输出位置：

- `app/build/outputs/apk/release/app-release-signed.apk`

## 使用说明

### 方式 1：应用内选择文件

1. 打开应用
2. 点击“选择音频或视频文件”
3. 选择 MP3 码率
4. 点击“开始转换为 MP3”

### 方式 2：从外部应用打开

1. 在微信或其他应用中选择一个音频或视频文件
2. 点击“更多打开方式”或“用其他应用打开”
3. 选择本应用
4. 进入应用后直接开始转 MP3

### 播放已转换 MP3

1. 打开主页面
2. 点击“打开已转换 MP3 播放页”
3. 在列表中点击任意 mp3 开始播放

## 依赖

- AndroidX
- Material Components
- Kotlin Coroutines
- `com.mrljdx:ffmpeg-kit-full:6.1.4`

## GitHub

当前仓库地址：

- [https://github.com/hehe2078/trans_audio](https://github.com/hehe2078/trans_audio)
