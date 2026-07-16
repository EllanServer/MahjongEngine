# 安装、升级与首次开服

语言： [English](./installation.md) · **简体中文** · [日本語](./ja-JP/installation.md)

本文面向服主，说明 MahjongPaper 的生产环境安装与升级。玩法规则请分别阅读：[立直麻将](./riichi-rules.zh-CN.md)、[国标麻将](./gb-mahjong-rules.zh-CN.md)、[四川麻将](./sichuan-rules.zh-CN.md)。

## 运行要求

- **Java 21 或更高版本；不再支持 Java 17。** Gradle 构建和服务器运行时都使用 Java 21 字节码。
- Paper 或 Folia。开发基线为 Paper 1.20.1，插件声明 `api-version: 1.20`。
- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) **26.7 或更高版本**。它是必需的服务端插件，必须先于 MahjongPaper 加载。
- InvSync 2.x 可选，用于保存玩家段位档案；当前自动段位/统计更新只支持四名真人完成的立直对局。它不替代牌桌持久化和历史记录数据库。

AntiGriefLib 与 Sparrow 系列是 MahjongPaper 的运行库，不是服主需要另外安装的服务端插件。MahjongPaper 会按各库的接入方式，将其内嵌并重定位，或交给 Paper library loader 解析。发行包也已经包含国标原生桥，服主无需自行编译 JNI。

## 安装步骤

1. 关闭服务器。
2. 把 CraftEngine 26.7+ 放入 `plugins/`。
3. 把 MahjongPaper 的 `*-universal.jar` 发行包放入 `plugins/`；不要安装 `-dev.jar`、`-sources.jar`，也不要在发行说明未要求时使用单平台构建。
4. 如需由 InvSync 管理段位，可安装兼容的 InvSync 2.x。
5. 使用 Java 21 启动服务器，等待依赖解析与资源导出完成。
6. 确认生成了 `plugins/MahjongPaper/config.yml`，日志中没有 MahjongPaper 或 CraftEngine 兼容性错误。

首次启动需要能访问 Maven 仓库。MahjongPaper 会把 CraftEngine bundle 导出到：

```text
plugins/CraftEngine/resources/mahjongpaper
```

其中包含麻将牌物品、桌椅家具、交互碰撞箱和资源包资产。若座位或牌张无法交互，先检查该目录、CraftEngine 加载状态和客户端资源包下发，再排查牌局逻辑。

## 创建第一个棋牌室和牌桌

正式服建议先建棋牌室。默认配置已启用棋牌室，并限制新牌桌只能在棋牌室范围内创建。

```text
/mahjong room wand
# 左键选择一个角，右键选择对角。
/mahjong room create main-hall 主厅
/mahjong room info main-hall
# 站在棋牌室内。
/mahjong create
```

快速测试时可站在房间中心执行 `/mahjong room create quick-room`；没有魔棒选区时会使用配置中的默认半径和高度。

牌桌创建后，玩家点击东/南/西/北固定座位入座，桌主按需补 Bot，所有真人座位使用 `/mahjong start` 准备。下一场开始前可用 `/mahjong mode <MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN>` 切换玩法。

## 数据库与 InvSync

默认启用本地 H2，也可在 `database` 下配置 MariaDB/MySQL。SQL 已启用且健康时，以下数据由它管理：

- 持久牌桌的位置、桌主、玩法和规则配置；
- `round_history` 与 `rank_history`；
- 排行榜投影。

InvSync 存在时保存玩家段位档案。默认回退策略下，InvSync 缺失、禁用、不兼容或运行期 API 失败时，只有 SQL 后端已启用且健康才会接管；关闭回退或 SQL 启动失败时，段位后端会进入不可用状态，不会静默写入另一处。默认 `database.failOnError: false` 允许数据库启动失败后继续运行但关闭持久化；要求牌桌、历史和段位必须落盘的生产服应设为 `true` 并监控启动日志。

当前只有四名真人完成的立直对局会更新段位和个人统计；`GB`、`SICHUAN` 排行榜选择器可读取已有或迁移数据，但这两种对局不会自动写入。InvSync 公开 addon API 在 `onSave` 回调中写入。若段位更新后、下一次 InvSync 保存前进程崩溃，最后一次更新可能丢失，因此应开启 InvSync auto-save 与 world-save。InvSync 模式下，`/mahjong rank` 仅支持已同步到本地缓存的在线玩家。

迁移步骤和故障边界见 [InvSync 玩家段位接入](./invsync-player-rank.zh-CN.md)。

## 语言

玩家消息按 Minecraft 客户端 locale 选择，包含英语、简体中文、多个繁体中文地区和日语（`ja-JP`）。配置注释目前只有英语、简体中文和繁体中文，暂不承诺日文配置注释。

## 重载还是重启？

普通配置修改和重新渲染可使用 `/mahjong reload`。以下变更必须完整关闭并重新启动：

- 更换 MahjongPaper、CraftEngine 或 InvSync JAR；
- 更换 Java 或 Paper/Folia 版本；
- 修改 InvSync 的数据所有权/回退策略；
- 修改依赖或资源 bundle 的交付方式。

不要在服务器运行时覆盖插件 JAR。重启会恢复空桌/大厅牌桌及其规则配置，但不会恢复进行中的手牌、牌墙或反应窗口。

## 升级检查表

1. 正常关闭服务器。
2. 备份 `plugins/MahjongPaper/`、SQL 数据库；若 CraftEngine bundle 有本地改动，也备份对应目录。
3. 只替换发行 JAR，并按需更新必需依赖。
4. 用 Java 21 启动，检查日志并确认 CraftEngine bundle 已重新导出。
5. 执行 `/mahjong room list`、`/mahjong list`，再为每个启用玩法跑一桌四 Bot 冒烟测试。

## 常见问题

- **出现 class version / Java 错误：**检查真正启动 Paper 的进程是否使用 Java 21+，不能只看运行 Gradle 的终端。
- **MahjongPaper 拒绝加载：**确认 CraftEngine 已安装、启用、版本不低于 26.7 且 API 兼容。
- **座位或牌张点不动：**确认 CraftEngine 先加载、bundle 存在且客户端已收到资源包。
- **`/mahjong create` 被拒绝：**站进已保存的棋牌室，或调整 `gameRooms.restrictNewTables`。
- **段位指令不可用：**检查 `ranking.enabled`、SQL 状态或 InvSync 同步/回退状态。
- **国标无法启动：**使用受支持平台的发行 JAR，不要使用开发 JAR，也不要手工替换发行包中的原生库。

配置来源：[默认模板](../src/main/config-template/config.template.yml)、[Bukkit 描述](../src/main/resources/plugin.yml)、[Paper 描述](../src/main/resources/paper-plugin.yml)。
