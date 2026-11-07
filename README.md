# 消息回应排行（Reaction Sorter）

一个 **LSPosed / Xposed 模块**，给 Telegram Android 客户端加一个功能：

> 统计**当前聊天窗口里每条消息收到的表情回应（reactions）总数**，按数量排序展示，点击任意一项直接**跳转定位到原消息**。

- 📊 排行：降序 / 升序一键切换，显示每种表情的明细（如 `👍×30 ❤️×12`）
- 🎯 定位：点击列表项调用客户端自己的 `scrollToMessageId`，回到原消息位置
- 🖼️ 合并相册：同一组（grouped id）消息只保留回应最多的一条
- 🔎 过滤：最小回应数、只看有回应的消息、只显示前 N 条
- 📋 长按列表项复制消息 ID；长按悬浮按钮隐藏按钮
- 🔒 不申请任何权限、不联网、不落盘、不上传任何聊天内容

---

## 与 Re: TeleVIP 的关系

参考了 [`2B-4G10/Re-TeleVIP`](https://github.com/2B-4G10/Re-TeleVIP) 的**产品形态**（Telegram for Android 的 Vector / Xposed 模块、按作用域勾选客户端）和**「按行为定位 hook 点」的思路**：不写死混淆后的名字，而是靠结构特征去找。

但该仓库**自 1.5.4 起已不再公开源码**（当前仓库只有 README / CHANGELOG / `compat.json` 兼容表 / 下载量统计 workflow），其 LICENSE 也禁止复用名称、Logo 与代码。因此本项目**完全从零编写**，不包含它的任何代码、资源、图标或商标，也没有使用它的功能代码。

---

## 实现原理

关键前提是 Telegram 官方 `TMessagesProj/proguard-rules.pro` 里的两条规则：

```proguard
-keep class org.telegram.tgnet.**     { *; }   # 整个 TL 数据模型不混淆
-keep class org.telegram.messenger.*  { *; }   # MessageObject 等顶层类不混淆
```

而 `org.telegram.ui.ChatActivity` **不在保留列表里**，类名、字段名、方法名每个版本都会变。所以模块分两层处理：

| 层 | 做法 | 稳定性 |
| --- | --- | --- |
| **数据层** | 直接反射读取保留原名：`MessageObject.messageOwner` → `TLRPC.Message.reactions` → `TL_messageReactions.results` → `ReactionCount.count` / `.reaction`，以及 `id` / `date` / `message` / `grouped_id` | 跨版本、跨 fork 稳定；每一步另有结构兜底 |
| **UI 层** | 从 Activity 出发做**受限的对象图扫描**（只下钻 `org.telegram.*` 及集合，深度 ≤ 5、对象数 ≤ 2500），找到「元素为 `MessageObject` 的 `ArrayList`」= 当前聊天已加载的消息列表 | 不依赖任何混淆后的类名 |
| **跳转** | 在该类里按**方法签名指纹**找 `scrollToMessageId(int, int, boolean, int, boolean, int, ...)`：R8 会改方法名，但改不了参数类型 | 以 `(int,int,boolean,int,boolean,int)` 为精确指纹，另有宽松兜底 |

扫描只在**点击悬浮按钮时**执行一次，结果用弱引用缓存；每次读取消息列表都是实时读取宿主那个 `ArrayList`，因此新加载的消息会自然进入统计。

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
| 点「回应排行」按钮 | 扫描当前聊天已加载的消息，弹出按回应数排序的列表 |
| 点列表项 | 跳转到该条原消息（成功会有 Toast 提示） |
| 「切换排序」 | 降序 ↔ 升序，无需重新扫描 |
| 长按列表项 | 复制该消息 ID 到剪贴板 |
| 长按悬浮按钮 | 隐藏按钮，重新进入界面恢复 |

设置项：启用模块、显示悬浮按钮、默认降序、只显示有回应的消息、合并相册、最小回应数、前 N 条、详细日志。修改后**强制停止客户端再打开**才生效。

---

## 构建（GitHub Actions）

`.github/workflows/build.yml` 在 `push` / `PR` / `workflow_dispatch` / `v*` 标签时触发：

1. `temurin` JDK 17 + Android SDK 35 / build-tools 35.0.0
2. `./gradlew :app:testDebugUnitTest` —— 跑排序逻辑的 JVM 单测
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

- **统计范围是「当前已加载的消息」**，不是整段聊天历史。想覆盖更多消息，先在聊天里向上翻页加载，再点排行。读数据库 / 拉取全量历史不在本模块范围内。
- 跳转依赖 `scrollToMessageId` 的签名指纹。若某个 fork 大改该方法，会提示「当前客户端未找到跳转接口」，但排行功能仍可用（列表长按可复制消息 ID）。
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
│   ├── ReactionCounter.java       反射提取 reactions 总数与明细
│   ├── MessageSnapshot.java       与 Android 无关的消息快照
│   ├── ReactionRanker.java        纯 Java 排序 / 过滤 / 合并（可单测）
│   └── ChatResolver.java          对象图结构发现 + 跳转方法签名指纹
├── hook/HostHook.java             唯一一个 Activity.onResume hook
├── ui/FloatingEntry.java          悬浮入口
├── ui/RankingDialog.java          排行列表与定位交互
├── settings/                      设置页与 XSharedPreferences
└── util/                          日志与防御式反射工具
app/src/test/                       ReactionRanker 的 JVM 单元测试
```

## 隐私

无权限、无网络、无文件写入（除模块自身设置）、无统计上报。消息文本只在内存中生成摘要并显示在你自己的屏幕上。

## 许可证

MIT，见 [LICENSE](LICENSE)。
