# 消息回应排行（Reaction Sorter）

一个 **LSPosed / Xposed 模块**，给 Telegram Android 客户端加一个功能：

> 统计**当前聊天里每条消息收到的表情回应（reactions）总数**，按数量排序展示，点击任意一项直接**跳转定位到原消息**。

- 📊 排行：降序 / 升序一键切换，显示每种表情的明细（如 `👍×30 ❤️×12`）
- 📚 **全量回应加载**（0.2.0 起）：打开排行时自动读取客户端本地消息库，把**该聊天已缓存的全部历史消息**纳入排行，不再受「消息分段加载」限制
- 🎯 定位：点击列表项调用客户端自己的 `scrollToMessageId` 回到原消息；历史消息由客户端按需加载
- ⏫ 可选「加载更早」：让客户端自己补拉更早历史并落库后再扫描（默认关闭，需手动开启）
- 🖼️ 合并相册：同一组（grouped id）消息只保留回应最多的一条
- 🔎 过滤：最小回应数、只看有回应的消息、只显示前 N 条
- 📋 长按列表项复制消息 ID；长按悬浮按钮隐藏按钮
- 🔒 不申请任何权限、不落盘、不上传聊天内容

---

## 与 Re: TeleVIP 的关系

参考了 [`2B-4G10/Re-TeleVIP`](https://github.com/2B-4G10/Re-TeleVIP) 的**产品形态**（Telegram for Android 的 Vector / Xposed 模块、按作用域勾选客户端）和**「按行为定位 hook 点」的思路**：不写死混淆后的名字，而是靠结构特征去找。

但该仓库**自 1.5.4 起已不再公开源码**（当前仓库只有 README / CHANGELOG / `compat.json` 兼容表 / 下载量统计 workflow），其 LICENSE 也禁止复用名称、Logo 与代码。因此本项目**完全从零编写**，不包含它的任何代码、资源、图标或商标，也没有使用它的功能代码。

---

## 为什么回应可以「一次拿全」

Telegram 的聊天界面是**分页加载**的：滚到哪一段，才把那一段的 `MessageObject` 放进内存。图片、视频、文件是**另外按需下载**的媒体，与消息元数据分离——所以你会看到缩略图还是空的，但表情回应的数字已经显示了。

**回应不属于媒体**：`TLRPC.Message.reactions` 是消息对象自身的字段，随消息元数据一起到达客户端，并随消息一起序列化进本地 SQLite 库 `cache4.db`（官方 `MessagesStorage` 的写入方式是 `message.serializeToStream(data)`，读取方式是 `TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)`）。因此只要消息进过本地库，回应计数就是完整可读的，不需要等图片视频下载，也不需要向服务器再要一次。

模块于是绕开 UI 的分页列表，直接以**只读**方式查询客户端自己的消息库：

| 步骤 | 做法 |
| --- | --- |
| 打开数据库 | `SQLiteDatabase.openDatabase(<filesDir>/cache4.db, OPEN_READONLY)`，多路径探测；用的是 Android 框架类，不受宿主混淆影响 |
| 识别表结构 | `sqlite_master` + `PRAGMA table_info` 动态定位 `messages_v2` / `messages`（老版本）/ `messages_topics`（论坛话题）的 `mid / uid / date / data` 列，不写死列顺序 |
| 按聊天过滤 | 优先用 `MessageObject.messageOwner.dialog_id`；取不到时用 `dialogs.last_mid` 反查当前聊天 |
| 反序列化 | 用宿主自己的 `TLRPC.Message.TLdeserialize` 解 `data` BLOB，再交给 `ReactionCounter` 复用同一套 reactions 解析，不重复实现 TL schema |
| 合并去重 | `ReactionRepository` 按消息 id 合并：本地库补历史，内存窗口提供最新状态（刚点的赞立即生效）；只存在于内存，不写盘 |
| 上限与线程 | 默认最多 20000 行、每批 250 行、后台线程执行并回报进度，关闭对话框即中止 |

> **覆盖范围 = 客户端本地库里已缓存的消息。** 如果某段历史客户端从未取过，库里自然没有。此时可以在设置里开启「允许联网补全更早历史」，用排行里的「加载更早」按钮让客户端自己向服务器补拉；或在聊天里手动上翻几屏后重新打开排行，新落库的消息会被自动并入。

---

## 实现原理

关键前提是 Telegram 官方 `TMessagesProj/proguard-rules.pro` 里的两条规则：

```proguard
-keep class org.telegram.tgnet.**     { *; }   # 整个 TL 数据模型不混淆
-keep class org.telegram.messenger.*  { *; }   # MessageObject 等顶层类不混淆
```

而 `org.telegram.ui.ChatActivity` **不在保留列表里**，类名、字段名、方法名每个版本都会变。所以模块分三层处理：

| 层 | 做法 | 稳定性 |
| --- | --- | --- |
| **数据层** | 直接反射读取保留原名：`MessageObject.messageOwner` → `TLRPC.Message.reactions` → `TL_messageReactions.results` → `ReactionCount.count` / `.reaction`，以及 `id` / `date` / `message` / `grouped_id` | 跨版本、跨 fork 稳定；每一步另有结构兜底 |
| **本地库层** | 只读 `cache4.db`，运行时探测表/列，用宿主自己的 `TLdeserialize` 解 BLOB；`SerializedData(byte[])`、`readInt32(boolean)`、`TLdeserialize(stream,int,boolean)` 全部按**签名**定位（`AbstractSerializedData` 在新版已改名 `InputSerializedData`） | 不依赖版本号、不依赖列顺序 |
| **UI 层** | 从 Activity 出发做**受限的对象图扫描**（只下钻 `org.telegram.*` 及集合，深度 ≤ 5、对象数 ≤ 2500），找到「元素为 `MessageObject` 的 `ArrayList`」= 当前聊天已加载的消息列表 | 不依赖任何混淆后的类名 |
| **跳转** | 在该类里按**方法签名指纹**找 `scrollToMessageId(int, int, boolean, int, boolean, int, ...)`：R8 会改方法名，但改不了参数类型 | 以 `(int,int,boolean,int,boolean,int)` 为精确指纹，另有宽松兜底 |

「加载更早」同样按签名形状定位 `MessagesController.loadMessages(long dialogId, ..., int count, int max_id, ...)`（该方法的参数个数随版本变化），并使用一个不会与宿主 UI 冲突的 `classGuid`：客户端照常把拉到的消息写进本地库，但自己的聊天界面不会因这次回调而插入消息。这一步**默认关闭、只在用户点击时执行、失败自动回退**。

> 正因为数据层不混淆，本模块不需要像 Re: TeleVIP 那样解析宿主 APK 的 dex 字节码来「按行为找 hook 点」；结构扫描 + 签名指纹已足够覆盖绝大多数官方版与分支版。

---

## 安装

1. 从 [Actions 的最新构建](../../actions) 或 [Releases](../../releases) 下载 `app-release.apk`（或 `app-debug.apk`）并安装。
2. 在 **LSPosed**（或 Vector / LSPatch）里：模块 → 勾选「消息回应排行」→ 在作用域中勾选你的 Telegram 客户端 → **强制停止客户端并重新打开**。
3. 进入任意聊天，右下角出现蓝色「回应排行」按钮即可使用。

模块设置页：LSPosed 模块列表中点开本模块（或打开「消息回应排行」应用图标）。

**要求**：Android 8.1（API 26）+、Xposed API 82 的框架（LSPosed / LSPatch）。Vector 2.2 使用新 Xposed API 102，本模块暂未适配该框架。

### 推荐作用域（`res/values/arrays.xml` / `core/Scope.java`）

```
org.telegram.messenger / .web / .plus、tw.nekomimi.nekogram、uz.unnarsx.cherrygram、
nu.gpu.nagram / .nagramx、xyz.nextalone.nagram、org.forkgram.messenger / .classic、
it.belloworld.mercurygram、momo.gram、ir.ilmili.telegraph、ua.entaytion.entinygram
```

运行时还会再校验一次包名白名单，即使手动把作用域改大，模块也不会作用于其它应用。

---

## 使用

| 操作 | 结果 |
| --- | --- |
| 点「回应排行」按钮 | 立即显示当前窗口的结果，同时后台扫描本地消息库，完成后自动刷新为全量排行 |
| 点列表项 | 跳转到该条原消息（成功会有 Toast 提示） |
| 「仅当前窗口」/「切换到全量」 | 在「只统计已加载消息」与「全部本地消息」之间切换 |
| 「重新扫描」 | 重新读一次本地库（例如刚在聊天里上翻加载了更多历史） |
| 「加载更早」 | 需先在设置里开启；让客户端补拉更早历史并落库后重新扫描，每次最多 3 批 |
| 「切换排序」 | 降序 ↔ 升序，无需重新扫描 |
| 长按列表项 | 复制该消息 ID 到剪贴板 |
| 长按悬浮按钮 | 隐藏按钮，重新进入界面恢复 |

设置项：启用模块、显示悬浮按钮、默认降序、只显示有回应的消息、合并相册、最小回应数、前 N 条、详细日志，**以及**「打开排行时自动扫描本地消息库」（默认开）、「单次扫描行数上限」（默认 20000）、「允许联网补全更早历史」（默认关）。修改后**强制停止客户端再打开**才生效。

排行顶部会显示当前覆盖范围（本地库 N 条 / 窗口 M 条）、扫描进度和结果状态；本地库因权限或版本原因打不开时，会明确提示并自动回退到「仅当前窗口」。

---

## 构建（GitHub Actions）

`.github/workflows/build.yml` 在 `push` / `PR` / `workflow_dispatch` / `v*` 标签时触发：

1. `temurin` JDK 17 + Android SDK 35 / build-tools 35.0.0（使用 runner 自带 SDK）
2. `./gradlew :app:testDebugUnitTest` —— 跑排序与仓库合并逻辑的 JVM 单测
3. `./gradlew :app:assembleDebug :app:assembleRelease` —— 产出两个可直接安装的 APK
4. 校验产物存在且 `assets/xposed_init` 被打进 APK，上传 artifact（保留 30 天）
5. 推送 `v*` 标签时自动创建 Release 并附上 APK

构建只使用仓库自带的 `GITHUB_TOKEN`，**不需要任何 Personal Access Token / Secret**。Xposed API 82 已作为 `app/libs/api-82.jar` 随仓库提供（`compileOnly`），CI 不依赖外部 maven 源。

本地构建：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物在 `app/build/outputs/apk/`。Release 用自动生成的 debug key 签名，仅为方便直接安装，发布前请自行换成正式签名。

---

## 已知限制

- **全量 = 客户端本地已缓存的历史**，不是服务器上该聊天的全部消息。从未加载过的早期历史需要「加载更早」或手动上翻。
- 若客户端把消息库放在非标准路径、使用了自定义 SQLite 封装或加密，或当前设备的 SELinux / 存储策略阻止只读打开，扫描会失败并明确提示，功能自动回退到「仅当前窗口」。
- 跳转依赖 `scrollToMessageId` 的签名指纹。若某个 fork 大改该方法，会提示「当前客户端未找到跳转接口」，但排行功能仍可用（列表长按可复制消息 ID）。
- 「加载更早」按签名形状反射调用 `loadMessages`，个别 fork 可能不兼容；失败时只提示，不影响排行，也不会写入任何数据。
- 不适配 Vector 2.2 的 Xposed API 102；LSPosed / LSPatch 正常。
- 悬浮按钮常驻于 Telegram 主 Activity（半透明、可长按隐藏）。Telegram 用 Fragment 切换聊天，Activity 不变，因此按钮不需要重建；当前聊天在点击时才解析。
- 修改客户端可能违反 Telegram 服务条款、存在账号风险；本项目仅供学习研究，使用前自行评估。

---

## 目录

```
app/src/main/java/io/github/fuoins/reactionsorter/
├── HookEntry.java                 Xposed 入口（assets/xposed_init 声明）
├── core/
│   ├── Scope.java                 包名白名单
│   ├── TelegramClasses.java       keep 规则下的稳定类名 + 结构兜底
│   ├── ReactionCounter.java       反射提取 reactions 总数与明细（支持 MessageObject 与裸 TL Message）
│   ├── MessageSnapshot.java       与 Android 无关的消息快照
│   ├── ReactionRanker.java        纯 Java 排序 / 过滤 / 合并（可单测）
│   ├── ReactionRepository.java    纯 Java 全量结果与实时窗口的去重合并（可单测）
│   ├── TlMessageDecoder.java      用宿主自己的 TLdeserialize 解 data BLOB
│   ├── MessageDatabase.java       只读 cache4.db，动态探测表/列，分页扫描
│   ├── HistoryLoader.java         按签名反射调用 loadMessages 补拉更早历史（可选）
│   └── ChatResolver.java          对象图结构发现 + 跳转方法签名指纹
├── hook/HostHook.java             唯一一个 Activity.onResume hook
├── ui/FloatingEntry.java          悬浮入口
├── ui/RankingDialog.java          排行列表、全量扫描进度与定位交互
├── settings/                      设置页与 XSharedPreferences
└── util/                          日志与防御式反射工具
app/src/test/                       ReactionRanker / ReactionRepository 的 JVM 单元测试
```

## 隐私

无权限、无网络（除用户显式开启并点击「加载更早」时由客户端自身发起）、无文件写入（除模块自身设置）、无统计上报。本地消息库以**只读**方式打开，消息文本只在内存中生成摘要并显示在你自己的屏幕上，不复制、不导出、不上传。

## 许可证

MIT，见 [LICENSE](LICENSE)。
