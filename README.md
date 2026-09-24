# FakeLoc · 虚拟定位（Android 14 / Magisk + LSPosed）

面向 **已 root 的 Android 设备**的虚拟定位模块。本体是一个 **LSPosed 模块**（同一个 APK 既是普通应用，也是 Xposed 模块），配套一个可选的 **Magisk 模块**做 root 辅助。

- 目标环境：Android 14 (API 34)、Magisk（Kitsune Mask 亦可）、**LSPosed 1.9.2 (7024)**
- 编译环境：JDK 21 + AGP 8.13.2 + Gradle 8.13 + compileSdk 36
- 语言：Kotlin（UI 用 Jetpack Compose）+ 少量 Java-free 反射调用

### 更新日志

**v1.4.2**

- **修复地图拖动方向反了**。`GestureDetector.onScroll` 给的 `dx/dy` 是"手指向左/向上为正"，
  即内容应该往哪边挪；换算到中心点时应该是**加**而不是减。
  实测：左滑 500px 经度 `130.0000 → 130.0195`（视口往东），上滑 500px 纬度
  `29.9983 → 29.9772`（视口往南）。

**v1.4.0 / v1.4.1（真机调试后的重大修正）**

- **地图从 WebView+Leaflet 换成原生 Canvas 瓦片地图**。实测这台设备的系统 WebView
  （117.0.5938.60）**图像合成已损坏**：HTML/CSS 正常渲染、瓦片 HTTP 200 且 `tileload`
  触发、DOM 里 `256x256 / visibility:visible`，但图片就是不上屏，并伴随
  `Renderer process crash (code -1)` 与 `webview_service` SIGSEGV。
  新实现见 `ui/TileMapView.kt`，顺带移除了 160KB 的 Leaflet 资源。
- **修复 GPS 无信号时定位不生效**：真实 provider 返回 `null` 时，原来直接放行。
  现在对 `getLast*` / `getCurrent*` / `getLocation` 这类查询方法会**凭空造一个**伪造位置。
- **修复跨进程配置通道**：Android 11+ 的**包可见性**会让第三方应用进程解析不到模块的
  ContentProvider（`Failed to find provider info`），只有 system_server 能连。
  新增 **Settings.Global 中继**：App 用 root 执行 `settings put global fakeloc_cfg`，
  任意应用进程读 `Settings.Global` 即可拿到配置。
- 新增不需要 root 的诊断出口：`adb shell settings get global fakeloc_diag`。
- 新增 `am start ... --ez open_map true` 直接打开地图弹窗（自动化测试用）。

**v1.1.0**

- **修复地图选点看不到地图**：改用虚拟 https 源 + `shouldInterceptRequest` 从 assets 供页，
  绕开 `file://` 在 Android 10+ 上的各种限制；加双路径兜底、瓦片源降级链、离线坐标网格。
- **新增地图规划路线**：路线模式下依次点击落途经点，实时连线编号、显示总长，支持撤销/清空/全览。
- **修复 LSPosed 模式"好像没生效"**：
  - `SystemServerHook` 改为**按类型特征泛化扫描**，补齐 AOSP 13+ 的新包名与签名变化；
  - 新增 **hook 回执通道** —— App 里能直接看到哪些进程被注入、挂了几个 hook、读到的配置是开还是关；
  - `LocConfig.appliesTo` 作用域白名单**真正接进三个 hook**（之前是死代码）；
  - `App.onCreate` 主动落一次默认配置，保证 XSharedPreferences 兜底通道可用；
  - 高频落盘从 `commit()` 改 `apply()`（路线模拟时每秒写一次 prefs，原先会在主线程同步 IO）。


---

## 一、源码与工具链解耦

这是本项目的一条硬约束：**源码仓库里不含任何编译期依赖**。

```
源码（本目录，可放任意位置 / 可入 git）      编译期依赖（外部工具链，不进仓库）
├─ app/                                     D:\Android\toolchain\
│  ├─ src/main/java/...                       ├─ sdk\        Android SDK 36 + build-tools
│  ├─ src/main/assets/                        └─ gradle-home\ Gradle 8.13 + 依赖缓存
│  ├─ libs/xposed-api-82.jar  ← 内置 stub   D:\Android\keystore\fakeloc.jks  签名密钥
│  └─ build.gradle.kts                      D:\Java\Java21  JDK
├─ gradle/libs.versions.toml
├─ magisk/            ← Magisk 模块模板
├─ scripts/           ← 一键构建脚本
├─ local.properties   ← 每台机器自己生成（已 gitignore）
└─ local.properties.example
```

三处「解耦点」：

| 依赖 | 解耦方式 | 位置 |
|------|----------|------|
| Android SDK | `local.properties` 的 `sdk.dir` | 外部 |
| Gradle 发行版 + 依赖缓存 | 环境变量 `GRADLE_USER_HOME` | 外部 |
| 签名密钥 | `local.properties` 的 `fakeloc.storeFile` | 外部（不入库） |

Xposed API 是唯一的例外 —— 它被**内置成 `app/libs/xposed-api-82.jar`**（`compileOnly`）。
这么做是因为官方 `https://api.xposed.info/` 在国内网络下经常拉不动，
内置后整个构建可以完全离线进行。该 jar 只是编译期桩，运行时由 LSPosed 注入真实实现，**不会打进 APK**。

---

## 二、构建

### 一键构建

```bat
:: Windows
scripts\build.bat            :: release
scripts\build.bat debug      :: debug
```

```bash
# Git Bash / WSL
./scripts/build.sh release
```

脚本内部已设好 `JAVA_HOME` / `GRADLE_USER_HOME` / `ANDROID_SDK_ROOT`，
换机器时只需改 `scripts/build.*` 顶部那三行。

### 手工构建

```bash
export JAVA_HOME=D:/Java/Java21
export GRADLE_USER_HOME=D:/Android/toolchain/gradle-home
export ANDROID_SDK_ROOT=D:/Android/toolchain/sdk
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 首次在新机器上构建

1. 复制 `local.properties.example` → `local.properties`，改 `sdk.dir` 与签名路径；
2. 没有自己的签名密钥时，删掉 `fakeloc.storeFile` 那几行即可 —— release 会自动降级用 debug 签名；
3. 如果 Gradle 缓存目录是空的，第一次构建需要联网拉依赖（已配阿里云镜像 + jitpack 兜底）。

---

## 三、安装与使用

### 1. 安装 APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 2. 在 LSPosed 里启用

1. 打开 LSPosed 管理器 → 模块 → 勾选 **FakeLoc**；
2. 设置**作用域**。默认已声明 `android`、`com.android.location.fused`、`com.google.android.gms`，
   还需要**手动勾上要伪造位置的目标应用**（如某打卡 App）；
3. 作用域变更后**重启目标应用**（或重启手机），让 hook 重新挂载。

### 3. 开始伪造

1. 打开 FakeLoc，在地图上选点（或直接输入经纬度），点「应用坐标」；
2. 点「启动」；
3. 打开目标应用验证。

### 4.（可选）刷入 Magisk 模块

把 `magisk/` 目录打包成 zip 刷入（或直接用 Magisk 的「从本地安装」指向该目录打包的 zip）：

```bash
cd magisk && zip -r ../FakeLoc-RootHelper.zip . && cd ..
```

Magisk 模块**不负责 hook**，它只做三件事：
- 创建 `/data/adb/fakeloc/`，给 App 一个 root 级的配置备份点；
- 开机时修正 App prefs 的可读权限（XSharedPreferences 兜底通道用）；
- 提供状态探测与日志，方便排查。

---

## 四、工作原理

### 三层 hook

| 层 | 目标进程 | 拦截内容 | 文件 |
|----|----------|----------|------|
| 应用层 | 任意被作用域覆盖的应用 | `LocationManager` 的同步查询 / 异步回调 / provider 状态 | `xposed/hooks/LocationManagerHook.kt` |
| 系统层 | `system_server` | `LocationProviderManager` / `MockableLocationProvider` / `GnssLocationProvider` / `LocationManagerService` | `xposed/hooks/SystemServerHook.kt` |
| 融合层 | 任意进程（有 GMS 时） | `com.google.android.gms.location.LocationResult` | `xposed/hooks/FusedLocationHook.kt` |

**应用层**覆盖面最广：只要 App 通过 `LocationManager` 取位置（绝大多数 App 都是），
都会被 `requestLocationUpdates` 的 listener 包装、`getLastKnownLocation` 的返回值替换拦下来。
包装后的 listener 与原始对象做了双向映射，保证 `removeUpdates(原始listener)` 仍然有效。
另外还会把 `isProviderEnabled` / `isLocationEnabled` / `hasProvider` / `getProviders`
统一改写成"GPS 开着"——很多 App 在发起定位前会先查这个开关，查到关就直接不请求了。

**系统层**做版本容错：AOSP 的定位模块在 Android 12/13/14 之间改过多轮
（类从 `com.android.server.location.*` 搬到 `provider.*` 子包、GNSS 搬到 `gnss.*` 子包、
`onReportLocation` 的参数在 `Location` 与 `LocationResult` 之间摇摆），
所以这里不写死方法名，而是**按类型特征泛化扫描**，四条规则覆盖全部可能：

| 特征 | 动作 |
|------|------|
| 返回值是 `Location` | 换掉返回值 |
| 返回值是 `LocationResult` | 换掉整个结果集（`LocationResult.create` 重新打包） |
| 参数里有 `Location` | 就地替换该参数 |
| 参数里有 `LocationResult` | 就地替换该参数 |

递归安全：造出来的 `Location` 都带 `fakeloc_mark` 标记，遇到带标记的直接放行，
所以即使同时 hook 到上游和下游也不会死循环、不会重复注入。

### 配置通道（App → hook）

这是本项目最容易踩坑的地方，值得单独说清楚：

> hook 代码运行在**目标进程**里，uid 与被 hook 的应用相同。
> 它既读不到 App 的私有目录（SELinux 对 `app_data_file` 跨应用拒绝），
> 也过不了签名级权限校验。所以在 Android 14 上，**ContentProvider 的 `call()`
> 是唯一稳定的跨 uid 单工通道**。

> **实测结论（很重要）**：Android 11+ 的**包可见性**会让第三方应用进程**解析不到**
> 模块的 ContentProvider。证据：`dumpsys activity providers` 里
> `com.mo.fakeloc.config` 的 `Connections:` 只有 `1910:system/1000`，
> 其它进程一律 `Failed to find provider info for com.mo.fakeloc.config`。
> 所以 **ContentProvider 只能在 system_server 里用**，不能当通用通道。

通道按"普通应用进程能不能用"排序，逐级降级：

| 通道 | system_server | 普通应用进程 | 说明 |
|------|---------------|--------------|------|
| 1. ContentProvider | ✅ | ❌ 被包可见性拦 | 只有 uid 1000 / 同 uid 可用 |
| 2. **Settings.Global 中继** | ✅ | ✅ | ★ 主力通道 |
| 3. XSharedPreferences | ✅ | 看 LSPosed 重定向 | 官方机制 |
| 4. 直读文件 | ✅ | ✅ | `/data/local/tmp/fakeloc/config.json` |
| 5. 都没有 | — | — | **保持上一次快照，绝不清空** |

**通道 2 是两跳中继**：

```
App ──Provider──> system_server（uid 1000，不受可见性限制）
                      │ 写入 Settings.Global["fakeloc_cfg"]
                      ▼
                任意应用进程读 Settings.Global（读它不需要任何权限）
```

写入由 **App 用 root 执行 `settings put global fakeloc_cfg '<json>'`** 完成
（实测让 system_server 里的 hook 直接调 `Settings.Global.putString` 写不进去），
节流 2 秒，只在用户改配置时发一次。

> App 在 `Application.onCreate` 里会主动落一次默认配置，
> 保证 prefs 文件从第一秒就存在 —— 否则用户装完没点过「应用坐标」时，
> XSharedPreferences 这条兜底通道会直接失效。

**关键约束：绝不在 hook 的调用线程里发 binder。**
很多 hook 触发点位于 system_server 的持锁路径上（`LocationProviderManager`、
`ActivityManagerService` 监视器等）。一旦在锁内同步请求 App 而 App 卡顿，
binder 事务会把系统锁占住几十秒 → watchdog 判定 system_server 阻塞 → 整机无响应。

所以 `ConfigBridge` 的做法是：**后台线程按需拉取 → 写内存快照 → hook 只读内存。**
开启状态 1 秒一次（路线模拟要跟手），关闭状态 2 秒一次，带 ±15% 抖动避免多进程同时醒来。

### Hook 回执：让 App 能"看见"hook

LSPosed 模块最难受的一点是**单向**：hook 跑在别的进程里，App 完全看不到它有没有装载。
用户只能靠"定位没变"反推，而"没变"的原因可能有一打。

所以每次 hook 向 `ConfigProvider` 要配置时，会**顺路**把自己的状态塞进 `extras`：

```
hookPkg / hookProc     我是谁（包名 / 进程名）
hookTags               挂了哪些 hook，如 "SS:6,LM:14"
hookVer                模块 versionCode
hookSeq / hookEnabled  我读到的配置序号与总开关
```

Provider 运行在 App 自己的进程里，收到就记进 `HookReportRegistry`（内存 + 节流落盘）。
UI 的「LSPosed 接入状态」卡片会实时显示：

- **已注入进程**：有几个进程正在回报（`15 秒`内有回执算"活着"）；
- 每个进程一行：`hook SS:6,LM:14 · 配置#1758700000000（开）· v2 · 37 次`。

**这是零额外 IPC 的** —— 反正本来就要拉配置，回执只是搭了个便车。

### LSPosed 模式"看起来没生效"怎么查

按这个顺序看「LSPosed 接入状态」卡片，能直接定位到断在哪一环：

| 现象 | 断点 | 处理 |
|------|------|------|
| 一条回执都没有 | 模块没启用 / 作用域空 | LSPosed 里启用模块；作用域至少勾 `android` |
| 只有 `android` 有回执，目标 App 没有 | 目标 App 不在作用域 | 把目标 App 勾进作用域，**然后重启它** |
| 有回执，但「配置」显示"（关）" | 配置没下发到 | 回 App 点「启动」；确认总开关是开的 |
| 有回执、配置"（开）"，但定位没变 | 该 App 走的不是被 hook 的路径 | 见下 |
| 目标 App 重启后才出现回执 | 正常 | hook 只在**进程启动时**挂载，不重启不生效 |

最后一种情况（hook 挂上了、配置也读到了，但定位没变）常见于：
App 用的是**它自己进程外**的定位服务（如某地图 SDK 的网络定位、或 `PendingIntent` 形式的
定位回调）。这类场景由 `system_server` 那一层覆盖 —— 所以 `android` 必须留在作用域里。


### 反检测

- 抹掉 `Location.isMock`（API 31+）与 `mIsFromMockProvider`（旧版）字段；
- 清掉 extras 里的 `mockLocation` 标记（框架给模拟位置打的）；
- provider 名里含 `mock` / `test` / `fake` 的一律改写成 `gps`；
- 补 `makeComplete()`，避免 API 31+ 因"位置不完整"抛异常；
- **自然抖动**：慢速漂移 + 快速噪声，模拟真实 GPS 的"呼吸感"，避免坐标纹丝不动；
- GNSS extras 里补 `satellites` / `meanCn0` / `maxCn0`。

### 坐标系

中国境内的地图服务不通用 WGS-84：系统定位 API 给的是 **WGS-84**，
而高德/腾讯等用 **GCJ-02**，百度用 **BD-09**，两者在国内偏差可达 **100~700 米**。

因此约定：**存储与下发的统一是 WGS-84**。地图选点时按当前图层所属坐标系自动换算回 WGS-84
（高德图层是 GCJ-02，界面上会显示实际偏移了多少米）；反过来把已有坐标画到图上时，
先正向换算成该图层坐标系再落标记 —— 否则换图层后标记会"跑"到几百米外。

`geo/CoordinateConverter.kt` 提供 WGS-84 / GCJ-02 / BD-09 双向换算。

### 地图实现（原生 Canvas，不依赖 WebView）

`ui/TileMapView.kt` —— 一个自己画瓦片的自定义 View：

- **Web Mercator 投影 + 小数级缩放**，瓦片按比例拉伸，缩放手感连续；
- `LruCache` + 4 线程异步下载瓦片，主线程只负责绘制；
- 双指缩放、单指拖动、轻点选点；
- 瓦片连续失败 6 次且一张都没成功 → 自动降级成**离线经纬网格**（原生画线 + 纬度标注），
  断网时选点/画路线照常可用。

**为什么不用 WebView + Leaflet**：实测目标设备（realme RMX3366 / ColorOS 14，
系统 WebView 117.0.5938.60）的 WebView **图像合成已经损坏** ——
HTML/CSS 全部正常渲染（HUD、按钮都在），瓦片 HTTP 200、`tileload` 触发、
DOM 探针显示 `256x256 / visibility:visible / opacity:1`，但图片**就是不上屏**；
关掉 `translate3d`（Leaflet 的 `any3d`）改用 left/top 定位也无效；
期间伴随 `Renderer process crash (code -1)` 和 `webview_service` 的 SIGSEGV。
换成原生绘制后一切正常，还顺带去掉了 160KB 的 leaflet.js/css。

### 路线模拟

两种落点方式：

- 「添加当前点」——把当前伪造坐标追加为一个途经点；
- 「在地图上规划路线」——打开路线模式弹窗，依次点击落点，实时连线、编号、显示总长，
  支持撤销 / 清空 / 全览。确认后整条路线按当前图层坐标系换算回 WGS-84 存下来。

播放时 `FakeLocationService` 每秒按设定速度推进一次，把新坐标写进配置，
hook 端 1 秒内读到 —— 位置就动起来了。**hook 侧完全不需要知道路线**，
只认一个静态坐标，复杂度全留在 App 里。


---

## 五、已知限制

- **不做 native 注入**。Magisk 模块只是辅助，不替代 LSPosed。
  若设备没有 LSPosed，只能退回「开发者模拟位置」模式（需在开发者选项里把本应用
  选为模拟位置信息应用），该模式下很多 App 能检测到 mock 标记。
- **传感器伪造未实现**。计步、气压计等仍是真实值。依赖步数交叉验证的场景需要另行处理。
- **部分 App 会做完整性校验**（检测 Xposed/LSPosed 痕迹、校验 Location 对象内部一致性）。
  本项目只处理了 `Location` 层面的标记，不处理框架痕迹隐藏 —— 那属于 LSPosed 隐藏能力的范畴
  （可在 LSPosed 里对目标应用开启"隐藏"）。
- **未开混淆**（`isMinifyEnabled = false`）。hook 代码大量依赖反射与类名，开混淆需要额外配置 keep 规则。
- 地图瓦片默认走**高德**（国内速度快），可切到高德影像 / OSM；全部拉不到时自动降级为离线坐标网格。
- **`android`（系统框架）必须留在 LSPosed 作用域里**。它是 `PendingIntent` 形式定位回调、
  以及不经过 App 进程 `LocationManager` 的那部分请求的唯一覆盖点。
- 配置回执会让 App 进程被 hook 端"顺路唤醒"。这是把"App 看不见 hook"变成"看得见"的代价；
  关闭状态下轮询已降到 2 秒一次。


---

## 六、目录导航

```
app/src/main/java/com/mo/fakeloc/
├─ App.kt / MainActivity.kt          应用入口（App.onCreate 会主动落一次默认配置）
├─ data/                             配置模型、存储、Provider 通道、hook 回执
│  ├─ LocConfig.kt                   ★ App 与 hook 共用的配置类（含作用域白名单）
│  ├─ ConfigStore.kt                 SharedPreferences 读写
│  ├─ ConfigProvider.kt              ★ App → hook 的配置通道 + hook 回执收口
│  └─ HookReport.kt                  ★ hook 回执模型与登记簿
├─ geo/CoordinateConverter.kt        WGS-84 / GCJ-02 / BD-09 互转
├─ root/                             RootShell + RootHelper
├─ service/                          前台服务 + 路线播放引擎
├─ ui/                               Compose 界面
│  ├─ MainScreen.kt                  主界面（含 LSPosed 接入状态面板）
│  ├─ TileMapView.kt                 ★ 原生 Canvas 瓦片地图（不依赖 WebView）
│  ├─ MapPickerDialog.kt             单点选点 + 地图规划路线（两个弹窗）
│  └─ AppScopeDialog.kt              作用域白名单选择
└─ xposed/                           ★ hook 层
   ├─ XposedEntry.kt                 模块入口（xposed_init 指向这里）
   ├─ ConfigBridge.kt                ★ 配置读取桥（五级通道 + 内存快照 + 回执上报）
   ├─ FakeLocationFactory.kt         ★ 伪造 Location 的构造与反检测处理
   └─ hooks/                         三层 hook 实现

app/src/main/assets/
└─ xposed_init                       模块入口类名
```

---

## 七、参考

同类开源项目的思路参考：

- [LSPosed](https://github.com/LSPosed/LSPosed) —— 模块框架本体
- [FakeLocation / 虚拟定位类模块](https://github.com/search?q=android+fake+location+xposed) —— 定位 hook 的常见拦截点
- AOSP `frameworks/base/services/core/java/com/android/server/location/` —— 系统定位管线

---

## License

仅供学习与个人测试使用。请遵守当地法律法规，勿用于伪造考勤、逃避监管等用途。
