# 消息回应排行（Reaction Sorter）

一个 **LSPosed / Xposed 模块**，给 Telegram Android 客户端加一个功能：

> 像**聊天内搜索**一样使用回应排行：在聊天右上角「三个点」菜单里点「回应排行」，状态显示在**频道名下方**，用官方样式底栏的**上一个 / 下一个**依次定位到消息，切换**按回应数排序**，点「**以列表显示**」按当前排序方式以官方搜索结果那种列表查看每条消息的回应明细。

**核心能力（0.5.0）**：分页读取**服务器上该聊天的全部消息**的回应数，而不是只读客户端本地缓存过的那一小段。做法是调用 Telegram 自己的 `messages.getHistory`——它返回的永远是**消息元数据**（文本、媒体引用、以及完整的表情回应），图片/视频/文件的内容走完全独立的下载通道。因此可以快速把整个频道的历史消息回应数全部拿到，且**不下载任何一张图片或一段视频、不写入本地库、不改变聊天界面**。

- 🌐 **在线全量（默认开）**：`messages.getHistory` 并行分页，每页 100 条，用 `add_offset` 直接寻址每一页，页与页之间互不依赖 → 大频道也很快；读取服务器报告的 `messagesSlice.count` 得到「共 N 条」
- 📭 **只取元数据**：只解析每条消息的 `id / date / reactions`（外加 64 字符文本预览用于列表显示——它本就在元数据里，不额外发请求），随即丢弃 TL 响应对象；媒体文件字节从不请求
- 📊 **渐进出结果**：每完成一波就并入排行并刷新，列表头部显示「服务器：共 N 条 · 已取 M 条 / 读取中」，无需等全扫完
- 📚 **本地库即时兜底**：打开时先秒扫 `cache4.db` 出一个完整排行，在线扫描随后补全；离线或在线接口不可用时自动保留本地结果
- 🏷️ **状态在频道名那里**：短状态写进官方 ActionBar 副标题行，**没有自造顶栏、不会「...」截断**；完整统计在「以列表显示」的列表头部
- 🔼🔽 **上一个 / 下一个**：底栏显示官方 `Of` 计数格式，调用客户端自己的 `scrollToMessageId` 跳转
- ⇅ **底栏排序方式**：只保留两个——**回应最多 → 最少** / **回应最少 → 最多**
- ☰ **以列表显示**：官方 `SearchAsList` / `SearchAsChat` 文案的蓝色切换，按当前排序展开官方搜索结果样式的列表
- 🖼️ 每行显示 `👍×30 ❤️×12` 明细、总数徽章、消息 ID、时间；合并相册、最小回应数、前 N 条过滤
- 🛡️ 不申请任何权限、不落盘、不上传聊天内容；**FLOOD_WAIT 限流自动停止并保留部分结果**，可在设置里完全关闭联网

---

## 与 Re: TeleVIP 的关系

参考了 [`2B-4G10/Re-TeleVIP`](https://github.com/2B-4G10/Re-TeleVIP) 的**产品形态**（Telegram for Android 的 Vector / Xposed 模块、按作用域勾选客户端）和**「按行为定位 hook 点」的思路**：不写死混淆后的名字，而是靠结构特征去找。

但该仓库**自 1.5.4 起已不再公开源码**（当前仓库只有 README / CHANGELOG / `compat.json` 兼容表 / 下载量统计 workflow），其 LICENSE 也禁止复用名称、Logo 与代码。因此本项目**完全从零编写**，不包含它的任何代码、资源、图标或商标。

---

## 为什么能「快速加载所有消息，却不加载内容」

这是本模块最关键的一点，也是 Telegram 协议本身决定的：

| 事实 | 含义 |
| --- | --- |
| `messages.getHistory` 返回 `messages.Messages`，其中 `messages` 是 `TLRPC.Message` 列表 | 每条消息自带 `reactions`（`TL_messageReactions.results` → `ReactionCount.count`），**回应数随元数据一起到达** |
| 媒体在 TL 里只是 `PhotoSize` / `Document` 的**引用**（几十字节的地址、尺寸） | 真正的图片/视频字节需要单独的 `upload.getFile` / CDN 请求，客户端只在消息**真正要显示**时才发 |
| `TL_messages_messagesSlice.count` 给出聊天总消息数 | 一开始就知道「全部」有多少条、需要翻多少页 |

所以「消息全部加载」和「内容全部加载」在 Telegram 里本来就是两件事——这正是你会看到**缩略图还空着、回应数字却已经显示**的原因。本模块只是主动把这个通道用到极致：

1. 用宿主自己的 `ConnectionsManager.sendRequest` 发 `TLRPC.TL_messages_getHistory`；
2. 请求只设 `peer / limit=100 / add_offset=k*100 / max_id=锚点`，**不触发任何下载**；
3. 回调里只把每条 `TLRPC.Message` 的 `id / date / reactions` 转成一个极小的 `MessageSnapshot`，其余字段一概不读、不缓存；
4. **不写 `cache4.db`、不 post NotificationCenter、不构造 `MessageObject`、不碰 `FileLoader`**，TL 响应对象随即被回收。

### 快在哪

串行「滚到哪加载哪」每次只取 50 条并要等上一批。这里改两点：

- **`add_offset` 直接寻址**：`offset_id=0` 时，`add_offset = k*100` 就是第 k 页，页与页无依赖 → 可以**并行**发（默认并发 4，最多 6）；
- **固定 `max_id` 锚点**：第一页拿到最新消息 id，后续所有页都用它锁定上界，扫描期间即使来了新消息也不会让分页错位；
- 每波 4~6 个请求同时往返，一个 1 万条消息的频道约 100 个请求、十几波即可完成，而非 100 次串行等待。

`messagesSlice.count` 让面板能显示「共 N 条 / 已取 M 条」，并在收齐后提前停止。遇到 `FLOOD_WAIT_x` 会解析限流、停止继续发请求并保留已取到的部分结果；单波 12 秒超时、总波数上限、消息数上限（默认 20000 / 硬上限 60000）共同防止失控。

### 与「本地库全量」的区别

0.2.0 曾实现过「扫描 `cache4.db` 覆盖全部历史」。但本地库只包含**客户端曾经取过并落库的消息**——从来没浏览过的早期历史根本不在里面，所以它只是「本地库消息回应数」，不是「该聊天的全部消息」。0.5.0 起：

| 数据源 | 覆盖 | 是否联网 | 用途 |
| --- | --- | --- | --- |
| 当前窗口 `MessageObject` 列表 | 屏幕上这一小段 | 否 | 最新状态（刚点的赞立即生效） |
| `cache4.db` 只读扫描 | 客户端已缓存的历史 | 否 | 即时兜底 / 离线可用 |
| **`messages.getHistory` 在线全量** | **服务器上该聊天的全部历史** | 是（只读元数据） | 主数据 |

三者按 id 去重合并，优先级：实时窗口 > 在线全量 > 本地库。合并只发生在内存，不写盘。

---

## 交互：为什么是「和搜索一样」

官方聊天内搜索（`ChatActivity.createSearchContainer()`）会在输入气泡位置挂一个 **44dp** 的 `searchContainer`：左侧 15dp 粗体计数（`R.string.Of` → `"%1$d of %2$d"`），右侧是 15dp 粗体蓝色的「**以列表显示**」切换（`SearchAsList` / `SearchAsChat`），图标按钮 44×44，左右各 7dp 边距、圆角气泡背景。

本模块对齐这套官方实现：

| 官方搜索 | 本模块 |
| --- | --- |
| 三点菜单里的搜索 | 三点菜单里的「回应排行」 |
| 频道名下方的副标题行 | 写入短状态 `回应排行 · 47 条 · 最高 30` |
| 底栏 `N of M` + 上/下图标 | 同一套 44dp / 7dp / 15dp 参数，计数用宿主 `Of` 文案，箭头用宿主 drawable |
| 右侧蓝色「以列表显示」 | 同位置同样式，文案直接用宿主 `SearchAsList` / `SearchAsChat` |
| 搜索结果 cell：头像、标题、预览、时间、徽章、缩进分隔线 | 同款布局，额外显示 `👍×30 ❤️×12`、总数徽章、`#消息ID` |
| 搜索过滤条件 | 底栏「排序方式」：只保留**回应最多/最少**两项 |

**默认「聊天模式」**：只有官方 ActionBar（频道名 + 我们的状态）和这一条官方样式底栏，中间透明、可触摸穿透，点「下一个」能真实看到消息流滚动。点「以列表显示」才按当前排序展开结果列表；点列表项自动收起并跳到原消息。

### 状态为什么在频道名那里

`ChatActivity` 的频道名/副标题在 `ChatAvatarContainer` 内（`SimpleTextView` / `AnimatedTextView`）；`ActionBar` 手动 layout 子 View（覆写 `onLayout` 且不调 `super`），往里 `addView` 不会被摆放。所以 `ActionBarInfo`：

1. 从 decor 做受限 BFS，找屏幕顶部、子树里有 ≥2 个文本 View 的容器（标题区），取最靠下、最靠左的那个当副标题；
2. 按方法签名 `setText(CharSequence)` 识别（`SimpleTextView` 返回 `boolean`、`AnimatedTextView` 返回 `void`，两者都覆盖）；
3. 用 `XposedBridge.hookMethod` 拦截，仅在 `thisObject` 是该副标题 View 时替换参数——宿主会不断刷新副标题，所以必须 hook 而非只调一次；
4. 关闭面板时 `unhook` 并恢复原文与可见性。文案由纯 Java `InfoText` 生成，硬上限 22 字符。

配色由 `HostTheme` 沿 parent 链找到官方 ActionBar 的 `ColorDrawable` 背景色再由亮度推深浅；字符串和图标通过 `getIdentifier` 按**资源名**取宿主的本地化文案与 artwork。找不到标题区时静默跳过状态显示，**绝不回退成自造顶栏**——完整汇总仍在列表头部。

### 三点菜单是怎么注入的

三点菜单由 `ActionBarPopupWindow`（**`PopupWindow` 子类**）显示。`org.telegram.ui.ActionBar.*` 会被混淆，但 `android.widget.PopupWindow` 是框架类、名字永远不变。`MenuEntry` hook 框架方法 `showAsDropDown` / `showAtLocation`，纯靠结构定位：≥3 个可点击带文字行的竖直容器 + 锚点在屏幕顶部 + 当前是真实聊天界面 + 排除格式菜单关键词 + tag 去重。注入行复制官方行的 padding、字号/字色/字体、selector 背景和高度，不依赖宿主资源 id 或混淆类名。

> 悬浮按钮保留为**兼容回退**，默认关闭：某些 fork 若改写了菜单实现，可在模块设置里打开。

---

## 实现原理

关键前提是 Telegram 官方 `TMessagesProj/proguard-rules.pro`：

```proguard
-keep class org.telegram.tgnet.**     { *; }   # 整个 TL 数据模型 + ConnectionsManager 不混淆
-keep class org.telegram.messenger.*  { *; }   # MessagesController 等顶层类不混淆
```

而 `org.telegram.ui.ChatActivity` / `ActionBar*` **会被混淆**。因此分层处理：

| 层 | 做法 | 稳定性 |
| --- | --- | --- |
| **数据层** | 直接反射读取保留原名：`TLRPC.Message.reactions` → `TL_messageReactions.results` → `ReactionCount.count` / `.reaction`，以及 `id` / `date` / `grouped_id` | 跨版本、跨 fork 稳定；每步另有结构兜底 |
| **在线层** | `Class.forName("org.telegram.tgnet.TLRPC$TL_messages_getHistory")` 构造请求，按名字设 `peer / offset_id / add_offset / limit / max_id / hash`；`MessagesController.getInputPeer(dialogId)` 取 peer；`ConnectionsManager.getInstance(account).sendRequest(req, callback, flags)` 发送 | 类名、字段名、方法名全在 keep 规则内，跨版本稳定 |
| **回调** | `java.lang.reflect.Proxy` 动态实现宿主的 `RequestDelegate` 接口（无需编译期依赖宿主类），在 `run(response, error)` 里读 `messages.Messages.messages` 与 `count` | 接口被 `-keep` 明确保留 |
| **本地库层** | 只读 `cache4.db`，运行时探测表/列，用宿主自己的 `TLdeserialize` 解 BLOB | 不依赖版本号、不依赖列顺序 |
| **菜单层** | hook 框架 `PopupWindow.show*`，按 View 结构 + 锚点位置 + 聊天上下文定位三点菜单 | 不依赖混淆类名/资源 id |
| **UI 层** | 从 Activity 出发做**受限对象图扫描**（只下钻 `org.telegram.*` 及集合，深度 ≤5、对象数 ≤2500），找「元素为 `MessageObject` 的 `ArrayList`」 | 不依赖任何混淆后的类名 |
| **跳转** | 按**方法签名指纹**找 `scrollToMessageId(int, int, boolean, int, boolean, int, ...)`：R8 改得了方法名，改不了参数类型 | 精确指纹 + 宽松兜底 |

> 正因为数据层不混淆，本模块不需要像 Re: TeleVIP 那样解析宿主 APK 的 dex 字节码来「按行为找 hook 点」；结构扫描 + 签名指纹已足够覆盖绝大多数官方版与分支版。

---

## 安装

1. 从 [Actions 的最新构建](../../actions) 或 [Releases](../../releases) 下载 `app-release.apk`（或 `app-debug.apk`）并安装。
2. 在 **LSPosed**（或 Vector / LSPatch）里：模块 → 勾选「消息回应排行」→ 在作用域中勾选你的 Telegram 客户端 → **强制停止客户端并重新打开**。
3. 进入任意聊天/频道，点右上角 **⋮（三个点）** → 菜单里出现「**回应排行**」即可使用。

模块设置页：LSPosed 模块列表中点开本模块（或打开「消息回应排行」应用图标）。

**要求**：Android 8.1（API 26）+、Xposed API 82 的框架（LSPosed / LSPatch）。Vector 2.2 使用新 Xposed API 102，本模块暂未适配。

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
| 三点菜单 →「回应排行」 | 打开搜索式面板；先出当前窗口 + 本地库结果，同时后台并行分页读取服务器全部消息回应，逐波刷新为全量排行 |
| 底栏 `▲` / `▼` | 上一个 / 下一个，官方计数格式同步更新并跳转 |
| 底栏「回应 ↓ / ↑」 | 在「回应最多 → 最少」与「回应最少 → 最多」间切换，保持当前选中项 |
| 底栏「以列表显示」 | 按当前排序展开官方搜索结果样式的列表（含回应明细与完整统计头部）；再点收起 |
| 点列表项 / 长按列表项 | 收起列表并跳转到原消息 / 复制消息 ID |
| 底栏 `⋮` | 重新扫描（服务器全量 + 本地库）/ 停止当前扫描 / 仅重新扫描本地库 / 仅当前窗口 ↔ 全量 / 关闭排行 |
| 返回键 | 关闭面板，官方副标题恢复原样 |

设置项：启用模块、三点菜单入口、悬浮入口（回退，默认关）、默认排序方式（两项）、默认降序（兼容）、只显示有回应的消息、合并相册、最小回应数、前 N 条、详细日志，**以及**：

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 在线全量：读取服务器上该聊天的全部消息回应 | **开** | 唯一能拿到「全部消息」而非「本地已缓存」的方式；关闭即完全离线 |
| 在线扫描并发请求数 | 4 | 1–6，越大越快但越容易触发限流 |
| 同时扫描本地消息库作为即时兜底 | 开 | 先秒出本地排行，在线结果随后补全 |
| 单次扫描消息数上限 | 20000 | 硬上限 60000，同时限制本地库行数与在线消息数 |

修改后**强制停止客户端再打开**才生效。

列表头部完整显示：汇总（条数 / 有回应数 / 回应合计 / 最高）、排序方式、范围、**「服务器：共 N 条 · 已取 M 条」**、本地库/窗口条数、以及当前阶段或结果说明，多行显示不截断。

---

## 构建（GitHub Actions）

`.github/workflows/build.yml` 在 `push` / `PR` / `workflow_dispatch` / `v*` 标签时触发：

1. `temurin` JDK 17 + Android SDK 35 / build-tools 35.0.0（使用 runner 自带 SDK）
2. `./gradlew :app:testDebugUnitTest` —— 排序模式、排行过滤、仓库合并、在线分页规划 / 限流解析、副标题短文案的 JVM 单测
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

- **在线全量 = 服务器上账号有权读取的全部历史**。私密/受限频道、已删除消息、`messagesService` 服务消息、以及开启 TTL 的消息可能不计入；服务器 `count` 与实际收到的条数可能略有出入，此时会标注「结果不完整」。
- **会触发 Telegram 的速率限制**：超大频道（数万条）可能需要数百个 `getHistory` 请求，遇到 `FLOOD_WAIT_x` 会解析限流并停止、保留已取到的部分，稍后可重新扫描（已并入的结果不会丢）。降低并发数可减少触发概率。
- 在线扫描依赖 `TLRPC.TL_messages_getHistory` / `ConnectionsManager.sendRequest` / `RequestDelegate` / `MessagesController.getInputPeer` 这些被 keep 的接口。个别 fork 若大改网络层或混淆了这些类，会明确提示「在线不可用」并**自动保留本地库结果**。
- 三点菜单注入依赖 `PopupWindow` 子类 + 结构判定。极少数 fork 若改用完全自绘菜单，可在模块设置里开启「悬浮入口按钮」。
- 频道名下方的状态依赖能定位到官方标题区的副标题 View；找不到时**静默不显示状态**（不弹自造顶栏），排行/底栏/列表全部照常，完整统计仍在列表头部。
- 结果列表仍是纯代码构建的 View：几何、字号、配色对齐官方搜索结果 cell，主色/蓝字/图标取自宿主资源或官方 ActionBar 背景色，但不逐像素复用官方 Drawable；左侧圆位显示排名序号而非头像。
- 跳转依赖 `scrollToMessageId` 的签名指纹；若某个 fork 大改该方法，会提示「未找到跳转接口」，但排行与列表仍可用（长按可复制消息 ID）。
- 若设备存储策略阻止只读打开 `cache4.db`，本地兜底会失败并提示，但在线全量不受影响。
- 不适配 Vector 2.2 的 Xposed API 102；LSPosed / LSPatch 正常。
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
│   ├── ReactionRanker.java        纯 Java 排序模式 / 过滤 / 合并（可单测）
│   ├── ReactionRepository.java    纯 Java 三源去重合并：窗口 / 在线 / 本地库（可单测）
│   ├── InfoText.java              纯 Java 生成官方副标题用的短状态（可单测）
│   ├── OnlineScanPlan.java        纯 Java 在线分页规划与 FLOOD_WAIT 解析（可单测）
│   ├── OnlineReactionScanner.java 在线全量扫描：getHistory 并行分页、只取元数据、渐进回调
│   ├── TlMessageDecoder.java      用宿主自己的 TLdeserialize 解 data BLOB
│   ├── MessageDatabase.java       只读 cache4.db，动态探测表/列，分页扫描（即时兜底）
│   └── ChatResolver.java          对象图结构发现 + 跳转方法签名指纹
├── hook/
│   ├── HostHook.java              Activity.onResume + 安装菜单 hook
│   └── MenuEntry.java             hook PopupWindow.show*，结构定位并注入三点菜单项
├── ui/
│   ├── FloatingEntry.java          兼容回退悬浮入口（默认关）
│   ├── HostTheme.java              复用官方配色、本地化文案与图标 drawable
│   ├── ActionBarInfo.java          把状态写进官方 ActionBar 副标题（按签名 hook setText）
│   └── RankingPanel.java           官方 44dp 底栏 / 以列表显示 / 两阶段扫描 / 官方样式结果列表
├── settings/                      设置页与 XSharedPreferences
└── util/                          日志与防御式反射工具
app/src/test/                       ReactionRanker / SortMode / ReactionRepository /
                                   OnlineScanPlan / InfoText 的 JVM 单测
```

## 隐私

无权限、**无文件上传/统计上报**。开启在线全量时，会由 Telegram 客户端自身向服务器发起**只读**的 `messages.getHistory` 请求——只读取消息元数据（含回应计数），**不下载图片/视频/文件**，不写入本地库，不改变聊天界面；在设置里关闭该开关即可完全离线。本地消息库以**只读**方式打开，消息文本只在内存中生成极短摘要并显示在你自己的屏幕上，不复制、不导出、不上传。所有结果仅存活于进程内存。

## 许可证

MIT，见 [LICENSE](LICENSE)。
