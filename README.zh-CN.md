# MahjongPaper

> 本项目完全由 AI 创作。

> **本项目不是官方 MINECRAFT 产品，未经 MOJANG 或 MICROSOFT 批准，也与其无关联。**
>
> 文档中的 `Mahjong Soul` / `雀魂` 仅用于描述兼容的规则和视觉风格。MahjongPaper 与雀魂及其权利人无关联，也未获其背书。各产品名称和商标归各自权利人所有。

语言： [English](./README.md) · **简体中文** · [日本語](./README.ja-JP.md)

安装指南：[English](./docs/installation.md) · [简体中文](./docs/installation.zh-CN.md) · [日本語](./docs/ja-JP/installation.md)

贡献与代码边界说明见 [CONTRIBUTING.md](./CONTRIBUTING.md)。

游戏内消息支持英文、简体中文、繁体中文（台湾、香港、澳门）和日语（`ja-JP`）。服务端配置注释仍提供英文、简体中文和繁体中文版本。

`MahjongPaper` 是 `MahjongCraft` 的 Paper 插件重写版本，当前主要基于：

- Paper 显示实体
- CraftEngine 统一管理资源 bundle、自定义物品、家具交互和实体剔除

## 当前功能

当前分支以雀魂风格立直麻将为主要玩法，同时保留可选的国标麻将和四川麻将流程：

- 可持久化的大堂式牌桌；重启恢复牌桌位置、桌主、玩法和规则配置，不恢复中途手牌、牌墙或反应窗口
- 空桌创建、东南西北固定座位、点击入座、点击准备
- 4 个座位坐满且全部准备后自动开局
- 可补 Bot，且 Bot 默认视为已准备
- 桌主权限与牌桌控制 GUI，用于集中管理规则、Bot、开局、刷新和删除
- 开局前可直接离桌；开局后会在当前一局结束后离桌
- 发牌、摸牌、打牌、立直、自摸、荣和、吃、碰、明杠、暗杠、加杠
- 雀魂官方段位战基线：半庄、25000 起始点、25000 返还点、30000 一位必要点数、三赤、食断、多人荣和
- 通过 `mahjong-utils` 进行立直麻将和牌与结算
- 国标麻将可通过 `GB` 模式启用，规则判定走 bundled JNI bridge 与 vendored `GB-Mahjong` 源码
- 观战、私有手牌显示、HUD 覆盖层和本地化提示
- 已入座玩家可在对局中通过右侧“看牌河”按钮进入动画俯视视角；俯视时桌心上方会显示“返回座位”，Shift 仅保留为恢复兜底，无需客户端 Mod
- 基于 CraftEngine 的座位 / 牌桌交互与 bundle 导出
- 默认启用 H2；全局牌桌、对局/排名历史和排行榜投影始终由 SQL 管理。InvSync 2.x 可选，用于保存玩家段位档案；当前只有四名真人完成的立直对局会更新段位与个人统计。按默认策略，InvSync 不可用时仅在 SQL 已启用且健康的前提下回退自托管数据库
- 棋牌室系统：牌桌的空间容器，支持牌桌创建限制、进出提醒和对局中离开倒计时

## 推荐开服流程：先创建棋牌室

正式开服时，建议先创建至少一个棋牌室，再在棋牌室内创建牌桌。棋牌室是牌桌的允许摆放区域；当 `gameRooms.restrictNewTables` 开启时，管理员只有站在棋牌室内才能使用 `/mahjong create` 创建新牌桌。

快速教程：

1. 确认你拥有管理员权限：`mahjongpaper.admin`。
2. 执行 `/mahjong room wand` 获取棋牌室选区魔棒。
3. 左键点击房间的一个角，右键点击对角线另一角。
4. 观察青色粒子边框，确认整个游玩区域都被框住。
5. 执行 `/mahjong room create main-hall 主厅` 创建棋牌室。
6. 站在这个棋牌室内，执行 `/mahjong create` 创建牌桌。
7. 用 `/mahjong room list` 和 `/mahjong room info main-hall` 检查保存结果。

如果只是快速测试，也可以站在房间中心直接执行 `/mahjong room create quick-room`；没有魔棒选区时，插件会按 `gameRooms.defaultRadius` 和 `gameRooms.defaultHeight` 自动生成一个区域。

更完整的操作说明见中文 wiki 的 [棋牌室系统](./docs/wiki.zh-CN.md#棋牌室系统)。

## 指令概览

- `/mahjong help`：显示游戏内帮助
- `/mahjong room wand`：获取棋牌室选区魔棒
- `/mahjong room create <id> [名称]`：用当前选区创建棋牌室；没有选区时以当前位置为中心创建
- `/mahjong room list`：列出已保存的棋牌室
- `/mahjong room info <id>`：查看棋牌室世界、边界、大小和所有者
- `/mahjong create`：在当前位置创建一个空牌桌
- `/mahjong botmatch [MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN]`：创建一桌 4 Bot 测试对局并进入观战
- `/mahjong mode <MAJSOUL_TONPUU|MAJSOUL_HANCHAN|GB|SICHUAN>`：桌主/管理员在下一局开始前应用预设规则；默认玩法以 `MAJSOUL_HANCHAN` 为主
- `/mahjong join <tableId>`：加入牌桌
- `/mahjong leave`：开局前直接离开；开局后标记为本局结束后离开
- `/mahjong list`：查看活动牌桌及位置
- `/mahjong start`：切换当前座位的准备状态
- `/mahjong spectate <tableId>`：观战牌桌
- `/mahjong unspectate`：结束观战
- `/mahjong table [tableId]`：打开牌桌控制面板
- `/mahjong table owner <玩家名> [tableId]`：把桌主转让给已经入座的玩家
- `/mahjong addbot`、`/mahjong removebot`：桌主/管理员在开局前增减 Bot
- `/mahjong rule [key] [value]`：打开规则 GUI，或由桌主/管理员修改下一局生效的规则
- `/mahjong rule riichiProfile <MAJSOUL|EARLY_KAN_DORA>`：只切换立直杠宝牌揭示时序；旧存档 `TOURNAMENT` 可读，但不是完整 WRC
- `/mahjong state`：查看当前牌桌摘要
- `/mahjong riichi <index>`、`/mahjong tsumo`、`/mahjong ron`、`/mahjong pon`、`/mahjong minkan`、`/mahjong chii <tileA> <tileB>`、`/mahjong kan <tile>`、`/mahjong skip`、`/mahjong kyuushu`：对局中的动作指令，其中 `riichi` 与 `kyuushu` 仅用于立直麻将
- `/mahjong settlement`：重新打开最近一次结算界面
- `/mahjong rank`：查看雀魂风格段位摘要
- `/mahjong leaderboard [RIICHI|GB|SICHUAN]`：读取指定模式已保存的排行榜；当前对局结算只自动写入 `RIICHI`

- `/mahjong render`、`/mahjong clear`、`/mahjong inspect`：渲染维护与调试
- `/mahjong forceend [tableId]`：管理员强制结束当前对局
- `/mahjong deletetable [tableId]`：管理员删除牌桌

InvSync 2.x 用于保存在线玩家的段位档案。全局牌桌、`round_history`、`rank_history` 和非权威排行榜投影仍保存在 MahjongPaper 配置的 SQL 中，默认配置已启用本地 H2。InvSync 缺失、禁用、不兼容或回调运行失败时，只有同时启用回退且 SQL 后端已启用、健康，才会改用自托管数据库；否则段位存储为不可用。当前只有四名真人完成的立直对局会更新段位和个人统计；`GB`、`SICHUAN` 选择器只能读取已有或迁移数据，不会由这两种对局自动写入。公开 addon API 只能在 `onSave` 回调中写入：段位更新后到下一次 InvSync 保存前若进程崩溃，本次更新可能丢失，因此必须开启 InvSync auto-save 与 world-save。仓库不链接其付费 API jar，故 InvSync 模式下 `/mahjong rank` 只支持已同步进本地缓存的在线玩家；项目不编造 Maven 坐标，也不打包 InvSync API。详见 [InvSync 玩家段位接入](./docs/invsync-player-rank.zh-CN.md)。

管理员目标解析规则：

- 优先使用你显式传入的 `tableId`
- 未传时，先尝试你当前所在或正在观战的牌桌
- 如果你不在任何牌桌中，则回退到你附近最近的牌桌

## 大厅与准备流程

- 正式服建议先建棋牌室，再站在棋牌室内使用 `/mahjong create` 创建牌桌。
- `/mahjong create` 只会创建空桌，不会自动把创建者加入牌局
- 创建者会成为桌主，可通过 `/mahjong table` 管理规则、Bot、刷新、开局和删除
- 桌主/管理员可用 `/mahjong table owner <玩家名> [tableId]` 把桌主转让给已经入座的在线玩家
- 玩家通过东南西北悬浮字附近的交互加入固定风位
- 同一套悬浮字交互也用于开局前切换准备状态
- 只有 4 个座位坐满且全部准备后，才会自动开始
- 一局结束或整场结束后，都需要再次准备，才会开始下一局

## 安装与三种玩法

开服请先阅读[安装、升级与首次开服](./docs/installation.zh-CN.md)。插件内置三套独立规则引擎；建桌、入座、准备、打牌和结算入口共用，但开局阶段、可用动作、胡牌优先级和计分并不相同。

| 预设 | 牌张与开局 | 场次长度 | 起胡与副露 | 完整规则 |
| --- | --- | --- | --- | --- |
| `MAJSOUL_TONPUU` / `MAJSOUL_HANCHAN` | 136 张、三赤、14 张王牌 | 基础 4/8 手；连庄和终局延长可能增加手数 | 必须有役；默认 1 役番；可吃碰杠；多人荣/头跳可配置 | [立直麻将](./docs/riichi-rules.zh-CN.md) |
| `GB` | 144 张含八花；补花可选，也可保留后弃出 | 严格 MCR：净分 0、固定 16 手、不连庄 | 至少 8 个非花牌 fan；可吃碰杠；单响 | [国标麻将](./docs/gb-mahjong-rules.zh-CN.md) |
| `SICHUAN` | 108 张序数牌；发牌后直接定缺，不换三张 | T/TFMJ 房间默认 8 副、净分 0 | 平胡 0 番、3 番封顶；不能吃；血战到底、最多三家和 | [四川麻将](./docs/sichuan-rules.zh-CN.md) |

每份完整规则都提供现实书面来源、实体/现实玩法视频学习路径和 **Bilibili 备用教程**。外部教程可能采用不同地区或房间规则；插件行为以规则页中的 **MahjongPaper profile** 为准。

需要特别区分：

- 立直 `MAJSOUL` 对齐雀魂官方段位战；`EARLY_KAN_DORA` 只改杠宝时序，不是 WRC。
- 国标以 EMA/WMO Green Book 为权威，vendored 后端冲突时修正后端，不让实现反向定义规则。
- 四川以 T/TFMJ 01—2024 为权威：无默认换三张/末四强制胡，3 番 8 分封顶，自摸加底、呼叫转移与查叫；MIL/地方赛不混入默认 profile。

开发者证据见[全玩法规则验证矩阵](./docs/rule-verification-matrix.zh-CN.md)，服主管理和玩家操作见[中文 Wiki](./docs/wiki.zh-CN.md)。

## 构建

MahjongPaper 的构建环境和服务端运行环境现在都要求 Java 21 或更高版本。发行包使用 Java 21 字节码，同时保留 `api-version: 1.20` 和 Paper 1.20.1 API 基线。

```powershell
.\gradlew.bat build
```

当前 [plugin.yml](./src/main/resources/plugin.yml) 将 CraftEngine 声明为必需依赖。必须安装 CraftEngine 26.7 或更高版本；启动和 `/mahjong reload` 会在构造直接 API bridge 前拒绝旧版或 API 不兼容的构建。

## 配置

生成默认配置所用的模板见 [src/main/config-template/config.template.yml](./src/main/config-template/config.template.yml)。

当前推荐的配置结构：

- `database.connection`：数据库类型与 MariaDB 连接目标
- `database.credentials`：MariaDB 用户名与密码
- `database.h2`：本地 H2 配置
- `database.pool`：连接池参数
- `tables.persistence`：SQL 持久牌桌恢复开关
- `gameRooms`：棋牌室系统——牌桌的空间容器，支持创建限制、进出提醒和离开倒计时
- `integrations.craftengine`：CraftEngine 导出与交互偏好
- `debug`：调试日志

说明：

- 插件会在启动时读取配置；普通设置修改可用 `/mahjong reload` 重新解析并刷新牌桌
- 更换 JAR、Java、Paper/Folia、CraftEngine，或修改 InvSync backend / 回退策略后需要完整重启
- 旧版扁平配置键目前仍然兼容，可平滑迁移

## CraftEngine

MahjongPaper 要求 CraftEngine 26.7 或更高版本。兼容检查会按数值分段比较版本（例如 `26.10` 高于 `26.7`），并通过 CraftEngine 自身的 classloader 反射探测所需的 26.7 public Bukkit/core API，确认后才注册集成。

安装 CraftEngine 后，MahjongPaper 会把 bundle 导出到：

- `plugins/CraftEngine/resources/mahjongpaper`

导出内容包括：

- `pack.yml`
- `configuration/items/mahjong_tiles.yml`
- `resourcepack/assets/mahjongcraft/...`

当前 CraftEngine 集成主要覆盖：

- 自定义麻将牌物品
- 牌桌与座位 hitbox 家具
- tracked entity 剔除桥接
- 牌桌交互事件路由

## 致谢、资源与上游项目

MahjongPaper 是独立重写/移植项目，不主张拥有第三方代码、美术、录音、名称或商标。项目根目录的 MIT 许可证只适用于本项目作者有权授权的内容；第三方组件仍受各自的许可证或使用条款约束。

### 代码、规则与平台

| 项目 | 与 MahjongPaper 的关系 | 许可证 / 条款 |
| --- | --- | --- |
| [MahjongCraft](https://github.com/doublemoon1119/MahjongCraft)，作者 `doublemoon1119` | 原始 Fabric Mod；主要玩法/架构灵感来源，也是本项目复用麻将牌纹理和基础物品模型的直接来源 | [MIT](https://github.com/doublemoon1119/MahjongCraft/blob/main/LICENSE) |
| [MahjongPlay](https://github.com/7yunluo/MahjongPlay)，作者 `7yunluo` | Paper 插件实现参考 | [MIT](https://github.com/7yunluo/MahjongPlay/blob/main/LICENSE) |
| [mahjong-utils](https://github.com/ssttkkl/mahjong-utils)，作者 `ssttkkl` | 立直麻将和牌判定与计分的直接运行时库 | [MIT](https://github.com/ssttkkl/mahjong-utils/blob/main/LICENSE) |
| [GB-Mahjong](https://github.com/zheng-fan/GB-Mahjong)，作者 Zheng Fan | 以源码形式内置，编译进随包发行的国标麻将 JNI 原生库 | [MIT；仓库内许可证](./native/gbmahjong/vendor/GB-Mahjong/LICENSE) |
| [Paper](https://github.com/PaperMC/Paper) / [Folia](https://github.com/PaperMC/Folia) | 支持的服务器平台与 API，由服务器运营者另行提供 | 以各自上游许可证为准 |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | 自定义物品、家具、交互、剔除和资源下发所需的外部运行时插件（26.7+）；本项目不内置该插件 | [GPL-3.0](https://github.com/Xiao-MoMi/craft-engine/blob/main/LICENSE) |
| [Adventure](https://github.com/PaperMC/adventure) | 由 Paper 提供的文本/组件 API | [Apache-2.0](https://github.com/PaperMC/adventure/blob/main/5/license.txt) |

### 资源包美术与音效

[resourcepack](./resourcepack) 中的素材会被打包到导出的 CraftEngine bundle 中。每个文件的来源、授权和用途以 [resourcepack/ATTRIBUTION.md](./resourcepack/ATTRIBUTION.md) 为准；二次分发时必须保留该文件。

- 麻将牌纹理和基础物品模型复用自 [MahjongCraft](https://github.com/doublemoon1119/MahjongCraft)，适用 MIT 许可证。相关牌面美术谱系同时致谢 `lietxia` 的 [mahjong_graphic](https://github.com/lietxia/mahjong_graphic)（M+ 字体授权条款）；其文档还记录了部分图形源自 [I.Mahjong](https://github.com/SyaoranHinata/I.Mahjong) 与 GL-MahjongTile。
- 真实牌/桌面音效取材自 Freesound：**Macif** 的 `329098`、`329099`、`329100`，**Millavsb** 的 `197868`，以及 **poenia** 的 `745024`；这些录音均标记为 [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)。原始链接和文件对应关系详见资源署名文件。
- 立直模式的动作人声 `chii_01.wav`、`pon_01.wav`、`kan_01.wav`、`ri-chi_01.wav` 和 `ron_01.wav` 来自 [あみたろの声素材工房](https://amitaro.net/voice/game_01/)，适用该网站的[专用使用条款](https://amitaro.net/voice/voice_rule/)，**不适用**本项目的 MIT 或 CC0 授权。

必须保留的人声素材署名：

> 音声素材：あみたろの声素材工房 (<https://amitaro.net/>)<br>
> Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>)

あみたろ人声只能在遵守当前条款的前提下，作为插件/资源 bundle 这类作品的组成部分分发。二次分发者必须保留署名和条款链接/Readme，不得将录音作为独立人声包或音效包提供，并应当按条款规定的期限完成发布后使用报告。日文报告草稿见 [docs/amitaro-usage-report.ja.md](./docs/amitaro-usage-report.ja.md)。

### 直接运行时库

构建脚本声明了以下直接运行时库；它们是独立作品，仍适用各自许可证：[MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) (LGPL-2.1)、[MySQL Connector/J](https://github.com/mysql/mysql-connector-j) (GPL-2.0，附 Oracle 额外许可与 Universal FOSS Exception)、[H2](https://github.com/h2database/h2database) (MPL-2.0 或 EPL-1.0)、[HikariCP](https://github.com/brettwooldridge/HikariCP) (Apache-2.0)、[Caffeine](https://github.com/ben-manes/caffeine) (Apache-2.0)、[Kotlin](https://github.com/JetBrains/kotlin) (Apache-2.0) 以及 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Apache-2.0)。

本次上游同步还使用 [AntiGriefLib](https://github.com/Xiao-MoMi/AntiGriefLib) (MIT) 统一保护插件判定、[sparrow-heart](https://github.com/Xiao-MoMi/sparrow-heart) (MIT) 创建俯视视角的客户端实体、[sparrow-reflection](https://github.com/Xiao-MoMi/sparrow-reflection) (GPL-3.0) 与 [ASM](https://gitlab.ow2.org/asm/asm) (BSD-3-Clause) 完成跨版本发包，以及 [sparrow-yaml](https://github.com/Xiao-MoMi/sparrow-yaml) (GPL-3.0) 加载配置。按 Sparrow Reflection 的接入要求，Reflection、其中已着色的 [Mapping-IO](https://github.com/FabricMC/mapping-io) 0.8.0（Apache-2.0）与 ASM 会内嵌并重定位包名；其余 Maven 库由 Paper 插件加载器按 [MahjongPaperLoader.java](./src/main/java/top/ellan/mahjong/bootstrap/MahjongPaperLoader.java) 解析。[build.gradle.kts](./build.gradle.kts) 是直接依赖和版本的准确来源。

`sparrow-metadata`、`sparrow-nbt` 与 `sparrow-redis-message-broker` 没有作为未使用依赖空挂。全局牌桌、对局/排名历史和排行榜投影继续使用事务型 SQL；可选 InvSync 2.x 通过公开 addon 事件保存玩家段位档案，当前自动段位/统计更新仅支持立直对局。metadata/broker 面向另一套 Redis/MongoDB 模型，现有 Bukkit PDC 标记也不等同于通用 NBT 文档 API。

各平台发行包还包含编译后的 GB-Mahjong JNI 桥接库。Windows 发行包可能包含 [mingw-w64 winpthreads](https://github.com/mingw-w64/mingw-w64/tree/master/mingw-w64-libraries/winpthreads) 的 `libwinpthread-1.dll`，适用其上游 MIT/BSD 类许可通知。原生构建链接的 GCC 运行时部分适用 GPL-3.0 及 [GCC Runtime Library Exception 3.1](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html)。

### 商标与无关联声明

Minecraft 是 Microsoft 的商标。MahjongPaper 为独立开发项目，不是官方 Minecraft 产品，未经 Mojang 或 Microsoft 批准，也与其无关联。对 Mahjong Soul / 雀魂的引用只用于描述规则或风格，不构成关联、赞助或背书。各上游项目的名称和商标归各自权利人所有。

## 许可证

MahjongPaper 原创源代码和项目自制资源仍适用 [MIT License](./LICENSE)，但文件或上述通知另有标注的除外。由于可运行插件链接 GPL 的 Sparrow YAML，并内嵌 GPL 的 Sparrow Reflection，组合后的可运行软件发行物按 GPL-3.0-only 提供；重新分发二进制时还必须按该许可提供对应源码及构建脚本，并保留所有第三方通知。详见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。本节不构成法律意见，也不能替代实际许可文本。
