# 消息回应排行（Reaction Sorter）

一个 **LSPosed / Xposed 模块**，给 Telegram Android 客户端加一个功能：

> 像**聊天内搜索**一样使用回应排行：在聊天右上角「三个点」菜单里点「回应排行」，状态显示在**频道名下方**，用官方样式底栏的**上一个 / 下一个**依次定位到消息，切换**按回应数排序**，点「**以列表显示**」按当前排序方式以官方搜索结果那种列表查看每条消息的回应明细。

- 📍 **入口在三点菜单**：和官方搜索放在同一个溢出菜单里，不再是悬浮按钮
- 🏷️ **状态在频道名那里**：短状态写进官方 ActionBar 副标题行（频道名下方那行小字），**没有自造顶栏、不会再出现「...」截断**；完整统计在「以列表显示」的列表头部
- 🔼🔽 **上一个 / 下一个**：底栏显示官方 `Of` 计数格式，点一下就调用客户端自己的 `scrollToMessageId` 跳到排行里的下一条消息
- ⇅ **底栏排序方式**：只保留两个——**回应最多 → 最少** / **回应最少 → 最多**，切换后保持当前选中项
- ☰ **以列表显示**：用官方 `SearchAsList` / `SearchAsChat` 文案的蓝色切换按钮，点击按当前排序展开官方搜索结果样式的列表
- 📊 **回应信息**：每行显示 `👍×30 ❤️×12` 明细、总数徽章、消息 ID、时间
- 📚 **全量回应加载**：打开时自动读取客户端本地消息库，把该聊天**已缓存的全部历史**纳入排行，不受消息分段加载限制
- 🎯 点击列表项直接定位；长按复制消息 ID
- ⏫ 可选「加载更早」：让客户端自己补拉更早历史并落库后再扫描（默认关闭，需手动开启）
- 🖼️ 合并相册、最小回应数、只看有回应的消息、前 N 条过滤
- 🔒 不申请任何权限、不落盘、不上传聊天内容

---

## 与 Re: TeleVIP 的关系

参考了 [`2B-4G10/Re-TeleVIP`](https://github.com/2B-4G10/Re-TeleVIP) 的**产品形态**（Telegram for Android 的 Vector / Xposed 模块、按作用域勾选客户端）和**「按行为定位 hook 点」的思路**：不写死混淆后的名字，而是靠结构特征去找。

但该仓库**自 1.5.4 起已不再公开源码**（当前仓库只有 README / CHANGELOG / `compat.json` 兼容表 / 下载量统计 workflow），其 LICENSE 也禁止复用名称、Logo 与代码。因此本项目**完全从零编写**，不包含它的任何代码、资源、图标或商标，也没有使用它的功能代码。

---

## 交互：为什么是「和搜索一样」

官方聊天内搜索（`ChatActivity.createSearchContainer()`）会在输入气泡位置挂一个 **44dp** 的 `searchContainer`：左侧 15dp 粗体 `AnimatedTextView` 计数（用 `R.string.Of` → `"%1$d of %2$d"`），右侧是 15dp 粗体蓝色的「**以列表显示**」切换（`R.string.SearchAsList` / `SearchAsChat`），图标按钮 44×44，整体左右各 7dp 边距、圆角气泡背景。点「以列表显示」会展开 `messagesSearchListContainer`（`RecyclerListView` + `MessagesSearchAdapter`）。

**0.4.0 起本模块不再自造顶栏和底栏**，而是直接对齐这套官方实现：

| 官方搜索 | 本模块 |
| --- | --- |
| ActionBar 上的搜索图标 / 三点菜单里的搜索 | 三点菜单里的「回应排行」 |
| 频道名下方的副标题行 | 写入短状态：`回应排行 · 47 条 · 最高 30`（官方 `SimpleTextView`，颜色和主题自动跟随） |
| 底栏 `N of M` + 上/下图标 | 同一套 44dp / 7dp / 15dp 参数，计数用宿主 `Of` 文案，箭头用宿主 drawable（缺失则同色自绘） |
| 右侧蓝色「以列表显示」 | 同一位置、同样式，文案直接用宿主 `SearchAsList` / `SearchAsChat`（中文自动是「以列表显示 / 以聊天显示」） |
| 搜索结果 cell：头像、标题、预览、时间、徽章、缩进分隔线 | 同款布局，并额外显示 `👍×30 ❤️×12`、总数徽章、`#消息ID` |
| 搜索过滤条件 | 底栏「排序方式」：只保留**回应最多/最少**两项 |

**默认进入「聊天模式」**：界面上只有官方 ActionBar（频道名 + 我们的状态）和这一条官方样式底栏，中间透明、可触摸穿透到聊天，点「下一个」能真实看到消息流滚动。点底栏「以列表显示」才按当前排序展开结果列表；点列表项自动收起并跳到原消息。返回键或底栏 `⋮ → 关闭排行` 退出。

### 状态为什么在频道名那里

`ChatActivity` 的频道名和副标题并不在 `ActionBar` 自己的 `titleTextView` 里，而是 `ChatAvatarContainer` 内的 `SimpleTextView` / `AnimatedTextView`；而 `ActionBar` **手动 layout 子 View**（覆写 `onLayout` 且不调 `super`），所以往里 `addView` 一个自定义标题条是不会被摆放的——这也是旧版自造顶栏只能盖一条、文字还被截断的原因。

`ActionBarInfo` 换了个思路：

1. 从 decor 出发做受限 BFS，找屏幕顶部、子树里有 ≥2 个「文本 View」的容器（= 标题区），再取其中**最靠下、最靠左**的那个当副标题 View（排除右侧菜单）；文本 View 靠**方法签名** `setText(CharSequence)` 识别，不看混淆后的类名；
2. 按签名收集该 View 的 `setText` 方法（`SimpleTextView` 返回 `boolean`、`AnimatedTextView` 返回 `void`，两者都覆盖）；
3. 用 `XposedBridge.hookMethod` 拦截它们，**只**当 `thisObject` 是那个副标题 View 时把参数换成我们的短状态。之所以要 hook 而不是只调一次 `setText`，是因为宿主会不断刷新副标题（成员数、"typing…"）；
4. 关闭面板时 `unhook` 并恢复原文与可见性。文案由纯 Java 的 `InfoText` 生成，硬上限 22 字符，绝不会出现「...」。

配色同样不猜：`HostTheme` 沿 parent 链找到官方 ActionBar 的 `ColorDrawable` 背景色（含自定义主题），由亮度推出深浅色系；字符串和图标通过 `getIdentifier` 按**资源名**取宿主的本地化文案与 artwork（R8 不重命名资源名）。找不到标题区时静默跳过状态显示，**绝不回退成自造顶栏**——完整的汇总信息仍在列表头部。

### 三点菜单是怎么注入的

Telegram 的三点菜单由 `ActionBarMenuItem.addSubItem(...)` 生成 `ActionBarMenuSubItem`，装进 `ActionBarPopupWindow`（**`PopupWindow` 的子类**），显示时调用 `showAsDropDown` / `showAtLocation`。`org.telegram.ui.ActionBar.*` 会被混淆，但 `android.widget.PopupWindow` 是框架类、名字永远不会变。

所以 `MenuEntry` hook 的是**框架方法** `PopupWindow.showAsDropDown(...)`（3/4 参）和 `showAtLocation(...)`，再纯靠结构定位菜单：

1. 遍历 `getContentView()` 的 View 树，找「直接子项里 ≥3 个可点击且带文字的 TextView」的那个竖直容器（官方 `ActionBarPopupWindowLayout` 把 `addView` 转发到内部 `LinearLayout`）；
2. 弹窗锚点须靠近屏幕顶部（ActionBar 区域）——排除输入框弹出的附件/格式菜单；
3. 当前 Activity 能被 `ChatResolver` 解析成真实聊天界面；
4. 文案命中 ≥2 个格式词（加粗/斜体/等宽/Bold/Italic/Mono…）则判定为文本格式菜单，跳过；
5. 用自身 tag 去重，避免重复注入。

注入的行**复制官方行的 padding、文字大小/颜色/字体、selector 背景和 LayoutParams 高度**，因此看起来是原生的，且不依赖任何宿主资源 id 或混淆后的类名；能找到「搜索」项时插在它后面。整段逻辑全包 `try/catch`，任何不匹配都只是跳过，绝不会中断宿主菜单的显示。

> 悬浮按钮保留为**兼容回退**，默认关闭：某些 fork 若改写了菜单实现，可在模块设置里打开。

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
| 上限与线程 | 默认最多 20000 行、每批 250 行、后台线程执行并回报进度，关闭面板即中止 |

> **覆盖范围 = 客户端本地库里已缓存的消息。** 如果某段历史客户端从未取过，库里自然没有。此时可以在设置里开启「允许联网补全更早历史」，用「⋮ 更多 → 加载更早」让客户端自己向服务器补拉；或在聊天里手动上翻几屏后重新打开排行，新落库的消息会被自动并入。

---

## 实现原理

关键前提是 Telegram 官方 `TMessagesProj/proguard-rules.pro` 里的两条规则：

```proguard
-keep class org.telegram.tgnet.**     { *; }   # 整个 TL 数据模型不混淆
-keep class org.telegram.messenger.*  { *; }   # MessageObject 等顶层类不混淆
```

而 `org.telegram.ui.ChatActivity` / `ActionBar*` **不在保留列表里**，类名、字段名、方法名每个版本都会变。所以模块分层处理：

| 层 | 做法 | 稳定性 |
| --- | --- | --- |
| **数据层** | 直接反射读取保留原名：`MessageObject.messageOwner` → `TLRPC.Message.reactions` → `TL_messageReactions.results` → `ReactionCount.count` / `.reaction`，以及 `id` / `date` / `message` / `grouped_id` | 跨版本、跨 fork 稳定；每一步另有结构兜底 |
| **本地库层** | 只读 `cache4.db`，运行时探测表/列，用宿主自己的 `TLdeserialize` 解 BLOB；`SerializedData(byte[])`、`readInt32(boolean)`、`TLdeserialize(stream,int,boolean)` 全部按**签名**定位（`AbstractSerializedData` 在新版已改名 `InputSerializedData`） | 不依赖版本号、不依赖列顺序 |
| **菜单层** | hook 框架 `PopupWindow.show*`，按 View 结构 + 锚点位置 + 聊天上下文定位三点菜单并注入官方样式行 | 不依赖混淆类名/资源 id |
| **UI 层** | 从 Activity 出发做**受限的对象图扫描**（只下钻 `org.telegram.*` 及集合，深度 ≤ 5、对象数 ≤ 2500），找到「元素为 `MessageObject` 的 `ArrayList`」= 当前聊天已加载的消息列表 | 不依赖任何混淆后的类名 |
| **跳转** | 在该类里按**方法签名指纹**找 `scrollToMessageId(int, int, boolean, int, boolean, int, ...)`：R8 会改方法名，但改不了参数类型 | 以 `(int,int,boolean,int,boolean,int)` 为精确指纹，另有宽松兜底 |

「加载更早」同样按签名形状定位 `MessagesController.loadMessages(long dialogId, ..., int count, int max_id, ...)`（该方法的参数个数随版本变化），并使用一个不会与宿主 UI 冲突的 `classGuid`：客户端照常把拉到的消息写进本地库，但自己的聊天界面不会因这次回调而插入消息。这一步**默认关闭、只在用户点击时执行、失败自动回退**。

> 正因为数据层不混淆，本模块不需要像 Re: TeleVIP 那样解析宿主 APK 的 dex 字节码来「按行为找 hook 点」；结构扫描 + 签名指纹已足够覆盖绝大多数官方版与分支版。

---

## 安装

1. 从 [Actions 的最新构建](../../actions) 或 [Releases](../../releases) 下载 `app-release.apk`（或 `app-debug.apk`）并安装。
2. 在 **LSPosed**（或 Vector / LSPatch）里：模块 → 勾选「消息回应排行」→ 在作用域中勾选你的 Telegram 客户端 → **强制停止客户端并重新打开**。
3. 进入任意聊天/频道，点右上角 **⋮（三个点）** → 菜单里出现「**回应排行**」即可使用。

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
| 三点菜单 →「回应排行」 | 打开搜索式面板；频道名下方出现短状态，先用当前窗口出结果，同时后台扫描本地库，完成后自动刷新为全量排行 |
| 底栏 `▲` / `▼` | 上一个 / 下一个，官方计数格式同步更新并跳转到聊天里那条消息 |
| 底栏「回应 ↓ / ↑」 | 在「回应最多 → 最少」与「回应最少 → 最多」间切换，保持当前选中的消息 |
| 底栏「以列表显示」 | 按当前排序展开官方搜索结果样式的列表（含回应明细与完整统计头部）；按钮变「以聊天显示」，再点收起 |
| 点列表项 | 收起列表并跳转到该条原消息 |
| 长按列表项 | 复制该消息 ID 到剪贴板 |
| 底栏 `⋮` | 重新扫描 / 仅当前窗口 ↔ 全量 / 加载更早 / 关闭排行 |
| 返回键 | 关闭面板，官方副标题恢复原样 |
| 长按悬浮按钮（若已开启） | 隐藏按钮，重新进入界面恢复 |

设置项：启用模块、在三点菜单显示入口、悬浮入口（回退，默认关）、默认排序方式（两项）、默认降序（兼容）、只显示有回应的消息、合并相册、最小回应数、前 N 条、详细日志，**以及**「打开排行时自动扫描本地消息库」（默认开）、「单次扫描行数上限」（默认 20000）、「允许联网补全更早历史」（默认关）。修改后**强制停止客户端再打开**才生效。

频道名下方显示的是**短状态**（条数、最高回应数、扫描中/范围/问题）；展开「以列表显示」后，列表头部会完整显示覆盖范围（本地库 N 条 / 窗口 M 条）、汇总、排序方式、扫描进度和结果状态，多行显示不截断。本地库因权限或版本原因打不开时，会明确提示并自动回退到「仅当前窗口」。

---

## 构建（GitHub Actions）

`.github/workflows/build.yml` 在 `push` / `PR` / `workflow_dispatch` / `v*` 标签时触发：

1. `temurin` JDK 17 + Android SDK 35 / build-tools 35.0.0（使用 runner 自带 SDK）
2. `./gradlew :app:testDebugUnitTest` —— 跑排序模式、排行过滤、仓库合并与副标题短文案的 JVM 单测
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
- 三点菜单注入依赖 `PopupWindow` 子类 + 结构判定。极少数 fork 若改用完全自绘的菜单（非 `PopupWindow`），菜单项不会出现——此时在模块设置里开启「显示悬浮入口按钮（兼容回退）」即可正常使用全部功能。
- 结果列表仍是纯代码构建的 View：几何、字号、配色对齐官方搜索结果 cell，主色/蓝字/图标取自宿主资源或官方 ActionBar 背景色，但不逐像素复用官方 cell 的 Drawable；不含头像图片，左侧圆位显示排名序号。
- 频道名下方的状态依赖能定位到官方标题区的副标题 View（`SimpleTextView` / `AnimatedTextView`）。若某个 fork 把标题区完全改写、找不到该 View，模块会**静默不显示状态**（不弹自造顶栏），排行、底栏、列表功能全部照常；完整统计仍可在「以列表显示」的列表头部查看。
- 若客户端把消息库放在非标准路径、使用了自定义 SQLite 封装或加密，或当前设备的 SELinux / 存储策略阻止只读打开，扫描会失败并明确提示，功能自动回退到「仅当前窗口」。
- 跳转依赖 `scrollToMessageId` 的签名指纹。若某个 fork 大改该方法，会提示「当前客户端未找到跳转接口」，但排行与列表仍可用（长按可复制消息 ID）。
- 「加载更早」按签名形状反射调用 `loadMessages`，个别 fork 可能不兼容；失败时只提示，不影响排行，也不会写入任何数据。
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
│   ├── ReactionRepository.java    纯 Java 全量结果与实时窗口的去重合并（可单测）
│   ├── InfoText.java              纯 Java 生成官方副标题用的短状态（可单测，硬上限 22 字）
│   ├── TlMessageDecoder.java      用宿主自己的 TLdeserialize 解 data BLOB
│   ├── MessageDatabase.java       只读 cache4.db，动态探测表/列，分页扫描
│   ├── HistoryLoader.java         按签名反射调用 loadMessages 补拉更早历史（可选）
│   └── ChatResolver.java          对象图结构发现 + 跳转方法签名指纹
├── hook/
│   ├── HostHook.java              Activity.onResume + 安装菜单 hook
│   └── MenuEntry.java             hook PopupWindow.show*，结构定位并注入三点菜单项
├── ui/
│   ├── FloatingEntry.java          兼容回退悬浮入口（默认关）
│   ├── HostTheme.java              复用官方配色、本地化文案与图标 drawable
│   ├── ActionBarInfo.java          把状态写进官方 ActionBar 副标题（按签名 hook setText）
│   └── RankingPanel.java           官方 44dp 底栏 / 以列表显示 / 官方样式结果列表
├── settings/                      设置页与 XSharedPreferences
└── util/                          日志与防御式反射工具
app/src/test/                       ReactionRanker / SortMode / ReactionRepository / InfoText 的 JVM 单测
```

## 隐私

无权限、无网络（除用户显式开启并点击「加载更早」时由客户端自身发起）、无文件写入（除模块自身设置）、无统计上报。本地消息库以**只读**方式打开，消息文本只在内存中生成摘要并显示在你自己的屏幕上，不复制、不导出、不上传。

## 许可证

MIT，见 [LICENSE](LICENSE)。
