# Qusic

> 一个 Material Design 3 风格的 Android 本地音乐播放器。
> 自己导入曲库、动态取色、沉浸式播放页、系统级实况通知 —— **纯 Java 手写 UI，零第三方依赖**。

![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Design](https://img.shields.io/badge/Design-Material%20Design%203-6750A4)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk&logoColor=white)
![Dependencies](https://img.shields.io/badge/Dependencies-Zero-blue)
![APK](https://img.shields.io/badge/APK-139%20KB-orange)

---

## 截图

<!-- 把截图放进 docs/screenshots/ 后，去掉下面这行的注释
<p align="center">
  <img src="docs/screenshots/02-home.png"    width="24%">
  <img src="docs/screenshots/03-library.png" width="24%">
  <img src="docs/screenshots/05-player.png"  width="24%">
  <img src="docs/screenshots/06-lyrics.png"  width="24%">
</p>
-->

> 截图存放说明见 [docs/screenshots/README.md](docs/screenshots/README.md)

---

## 功能

| | |
|---|---|
| **导入制曲库** | 通过系统文件选择器自己挑歌，**不扫描**你的存储 |
| **动态取色** | 一颗种子色推导整套 MD3 配色，10 组预设，可切换深色 |
| **沉浸播放页** | 封面取色光晕、呼吸律动、三种版式（大封面 / 歌词 / 极简） |
| **滚动歌词** | 支持 `.lrc` 时间轴与内嵌歌词，逐行高亮、点击跳转 |
| **系统级流体云** | MediaSession + Android 16 实况更新，锁屏与实况窗都能控 |
| **在线音源** | 酷我（免登录）/ 网易云（匿名），见下方免责声明 |

---

## 一、怎么编译

项目里带了一套**不依赖 Gradle** 的手工构建链，链路是：

```
aapt2 compile → aapt2 link(+R.java) → javac → d8 → 写入 dex → zipalign → jarsigner
```

### 需要准备

| 工具 | 说明 |
|---|---|
| `aapt2` | 资源编译（从任意 Android SDK build-tools 里取） |
| `android.jar` | 编译用的平台接口（compileSdk 34 即可） |
| `r8.jar` | 提供 `com.android.tools.r8.D8` |
| `zipalign` | 4 字节对齐 |
| JDK | 8 或以上（本项目用 21 编译通过） |
| `python3` | 仅用于把 dex 写进 APK（`adddex.py`） |

### 两个脚本

- **`build.sh`** —— 普通环境用。需要自己改脚本顶部的 `TOOL` / `JDK` / `PY` 路径。
- **`build-root.sh`** —— 我在设备上实际用的版本。以 root 运行，解决 Termux JDK 的
  linker 命名空间问题（`libz.so.1 not found`）。如果你的环境没有这个毛病，用 `build.sh`。

```bash
# 用法
sh build.sh          # 产物：build/Qusic.apk
```

脚本会自动从 `AndroidManifest.xml` 读包名和版本号，不用手工同步。

### ⚠️ 打包顺序：必须先签名，再对齐

**这是本项目踩过的最坑的一个问题**，会导致 APK 在别人的手机上装不上：

```
-124: Failed parse during installPackageLI:
      Targeting R+ (version 30 and above) requires the resources.arsc of
      installed APKs to be stored uncompressed and aligned on a 4-byte boundary
```

Android 11+（targetSdk 30+）要求 `resources.arsc` 必须**既不压缩、又 4 字节对齐**。而

> **`jarsigner` 会重写整个 zip，把 `resources.arsc` 挪到未对齐的偏移上。**

所以 `zipalign → 签名` 这个常见顺序是**错的**（至少对本项目用的 v1 签名是错的）：
对齐会被签名毁掉。实测：

```
zipalign 之后   resources.arsc offset = 15956   → 4 字节对齐 ✓
jarsigner 之后  resources.arsc offset = 21391   → 未对齐 ✗
```

**正确顺序是把对齐放在最后一步**：

```
aapt2 link → 写入 dex → jarsigner 签名 → zipalign 对齐
```

这样做对 v1(JAR) 签名是**安全**的：v1 只对条目**内容**做摘要，不覆盖文件布局，
所以签完再对齐不会破坏签名（可用 `jarsigner -verify` 复核，会输出 `jar verified.`）。

> 注：如果将来改用 **v2/v3 签名**（`apksigner`），就不能这样做了 —— v2/v3 覆盖整个文件，
> 签名后任何字节变动都会失效。那种情况下要改用 `apksigner` 并依赖它自身的对齐能力。

自检方法（Python，无需任何工具）：

```python
import struct, zipfile
def data_offset(path, target):
    d = open(path, 'rb').read(); off = 0
    while True:
        i = d.find(b'PK\x03\x04', off)
        if i < 0: return None
        nlen, elen = struct.unpack('<HH', d[i+26:i+30])
        if d[i+30:i+30+nlen].decode('utf-8','replace') == target:
            return i + 30 + nlen + elen
        off = i + 4

apk = 'build/Qusic.apk'
i = zipfile.ZipFile(apk).getinfo('resources.arsc')
o = data_offset(apk, 'resources.arsc')
print('未压缩:', i.compress_type == 0)   # 必须是 True
print('4 字节对齐:', o % 4 == 0)          # 必须是 True
```

### 注意编译环境

本项目用 **Android bootclasspath + `-source 8`** 编译，这个组合下：

- **不能用 lambda**（缺 `LambdaMetafactory`，javac 会直接 Fatal Error）→ 全部用匿名内部类
- `android.jar` 版本较老时，`Notification.ProgressStyle`（API 36）等新 API 编译期不存在
  → 运行时用**反射**调用，见 `PlayerService.applyLiveUpdate()`

---

## 二、源码结构

```
app/src/main/
├── AndroidManifest.xml
├── res/
│   ├── drawable/ic_*.xml          25 个 Material Icons 官方矢量图
│   ├── mipmap-anydpi-v26/         自适应图标
│   └── values{,-night}/           主题、颜色
└── java/com/qusic/app/
    ├── WelcomeActivity.java       首次启动的欢迎页（滑动开始 → 墨水散去 → 主界面）
    ├── MainActivity.java          主界面：页面容器 + 底栏 + 迷你条 + 流体云
    ├── PlayerActivity.java        全屏播放页宿主
    │
    ├── Hct.java                   CAM16 近似：色调/明度/色度 ↔ sRGB
    ├── Tokens.java                MD3 颜色令牌（由一颗种子色推导整套配色）
    ├── Theme.java                 全局外观状态（种子色/明暗/动画/播放页样式）
    ├── Draw.java                  绘图工具：超椭圆、柔和阴影、液态玻璃、位图裁剪
    ├── Ui.java                    控件工厂、触感反馈、尺寸/格式化工具
    ├── Icons.java                 图标库（加载 Material 矢量图 + 实时染色）
    ├── Json.java                  极简 JSON 解析器（零依赖）
    │
    ├── LiquidNavBar.java          MD3 底部导航栏（滑动指示器胶囊）
    ├── SongListView.java          自绘高性能列表（错落入场/惯性滚动/律动条）
    ├── MiniPlayerBar.java         悬浮迷你播放条
    ├── NowPlayingView.java        播放页（三种样式 + 滚动歌词）
    ├── FluidCloud.java            应用内流体云胶囊
    ├── InkDissolve.java           「墨水向上散去」转场
    │
    ├── Song.java                  歌曲模型（本地/在线）
    ├── Library.java               本地曲库（SAF 导入制 + 封面缓存 + 持久化）
    ├── Lyrics.java                歌词（异步预加载，LRC / 内嵌）
    ├── PlayerService.java         播放服务（MediaPlayer + 队列 + 媒体通知）
    ├── Online.java                在线音源共用回调
    ├── Kuwo.java                  酷我客户端（零签名、免登录）
    ├── NetEase.java               网易云客户端（匿名模式、WEAPI 加密）
    │
    ├── HomePage.java              首页（问候 + 正在播放卡片 + 最近导入）
    ├── LibraryPage.java           曲库页（导入 + 歌曲/专辑/歌手）
    ├── SearchPage.java            搜索页（本地 / 酷我 / 网易云）
    ├── AboutPage.java             关于页（主题定制 + 曲库管理）
    └── AppMark.java               应用图标绘制（双八分音符）
```

---

## 三、几个关键实现说明

### 1. 色彩系统
`Hct` 实现了 CIELAB 的 L\*（MD3 的 "tone"）+ 色相 + 色度。`Tokens.of(seed, dark)`
按 MD3 规范取不同 tone 得到 primary/secondary/tertiary/neutral/error 五组角色。
中性色保留了极低色度（不是纯灰），这是 MD3 观感的关键。

### 2. 本地曲库是「导入制」
不扫描用户存储。通过 SAF 文件选择器导入，导入时 `takePersistableUriPermission` 拿持久授权。

**播放地址回退链**（`Library.candidates`）——因为 SAF 授权可能丢失：

```
在线曲目 → 直链
本地曲目 → ① SAF 原始 URI
           ② content://media/external/audio/media/<id>   ← 需要 READ_MEDIA_AUDIO
```

第 ② 条的 id 是从 SAF 的 document URI 里解析出来的（`.../document/audio%3A96966` → `96966`），
所以即使授权丢了也能自己恢复，不需要用户重新导入。

### 3. 歌词必须异步加载
早期版本在 `onDraw` 里直接调用 `MediaMetadataRetriever` —— 等于在主线程绘制期间做磁盘 I/O，
会被系统判 ANR（表现就是「闪退」）。现在 `Lyrics.preload()` 在切歌时后台加载，
`onDraw` 只读内存缓存，并且**零分配**（折行结果按宽度缓存，避免每帧 GC 卡顿）。

### 4. 系统级流体云
`PlayerService` 里做了两件事：

- **`android.media.session.MediaSession`**（平台 API）—— 锁屏、媒体卡片靠它识别
- **Android 16 实况更新（Live Updates）** —— `Notification.ProgressStyle` +
  `setRequestPromotedOngoing(true)`，ColorOS 的流体云就是消费这个

因为编译用的 `android.jar` 没有这些 API，全部走**反射**，不支持时静默降级。

### 5. 在线音源
- **酷我（默认）**：客户端**零加密**。播放接口参数就叫 `type=convert_url_with_sign`，
  签名由服务端生成后随 URL 返回，`user` 只是随机串。免登录、免费 320k。
- **网易云（备选）**：匿名模式（`register/anonimous` 拿匿名账号），**刻意不接账号登录** ——
  第三方客户端登录会被风控冻结。WEAPI 的 AES-128-CBC 双层加密 + RSA-NoPadding 全用 JDK 自带实现。
  注意 `cloudsearch` 接口被风控，用 `search/get`。

### 6. 本机踩过的坑（ColorOS / Android 16）
- **`oplus_fp_input` 是全机唯一的 TOUCHSCREEN 设备**。它是屏下指纹节点，会偶发抛出
  坐标固定、无位移的幽灵触摸。**不要按设备名过滤**，那会把真实触摸一起挡掉 ——
  正确做法是要求「滑动」这类需要真实位移的交互（见 `SlideToActivate`）。
- **`navigation_bar_height` 资源读不到**（返回 0），必须回退到 `WindowInsets`，
  否则底栏会被手势条盖住。
- `getDuration()` / `getCurrentPosition()` 在 `prepareAsync()` 回调前调用会返回
  底层错误 `-38`（`INVALID_OPERATION`），要先判 `preparing` 状态。

---

## 四、许可与声明

代码供学习参考。其中：

- **图标**来自 [Google Material Icons](https://fonts.google.com/icons)（Apache-2.0）
- 在线音源接口均为**第三方非官方接口**，不稳定、可能随时失效，且仅供个人学习研究；
  请勿用于分发或商业用途。请支持正版音乐。
