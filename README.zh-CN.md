# MahjongPaper

> 本项目完全由 AI 创作。

> **本项目不是官方 MINECRAFT 产品，未经 MOJANG 或 MICROSOFT 批准，也与其无关联。**
>
> 文档中的 `Mahjong Soul` / `雀魂` 仅用于描述兼容的规则和视觉风格。MahjongPaper 与雀魂及其权利人无关联，也未获其背书。各产品名称和商标归各自权利人所有。

英文说明见 [README.md](./README.md)。
贡献与代码边界说明见 [CONTRIBUTING.md](./CONTRIBUTING.md)。

`MahjongPaper` 是 `MahjongCraft` 的 Paper 插件重写版本，当前主要基于：

- Paper 显示实体
- CraftEngine 统一管理资源 bundle、自定义物品、家具交互和实体剔除

## 当前功能

当前分支以雀魂风格立直麻将为主要玩法，同时保留可选的国标麻将和四川麻将流程：

- 可持久化的大堂式牌桌，重启后可恢复
- 空桌创建、东南西北固定座位、点击入座、点击准备
- 4 个座位坐满且全部准备后自动开局
- 可补 Bot，且 Bot 默认视为已准备
- 桌主权限与牌桌控制 GUI，用于集中管理规则、Bot、开局、刷新和删除
- 开局前可直接离桌；开局后会在当前一局结束后离桌
- 发牌、摸牌、打牌、立直、自摸、荣和、吃、碰、明杠、暗杠、加杠
- 雀魂风格默认规则：半庄、25000 起始点、30000 返回点、三赤、食断、多人荣和、雀魂杠宝牌揭示时机
- 通过 `mahjong-utils` 进行立直麻将和牌与结算
- 国标麻将可通过 `GB` 模式启用，规则判定走 bundled JNI bridge 与 vendored `GB-Mahjong` 源码
- 观战、私有手牌显示、HUD 覆盖层和本地化提示
- 基于 CraftEngine 的座位 / 牌桌交互与 bundle 导出
- 默认使用 H2 持久化对局历史和段位，可选 MariaDB/MySQL
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
- `/mahjong state`：查看当前牌桌摘要
- `/mahjong riichi <index>`、`/mahjong tsumo`、`/mahjong ron`、`/mahjong pon`、`/mahjong minkan`、`/mahjong chii <tileA> <tileB>`、`/mahjong kan <tile>`、`/mahjong skip`、`/mahjong kyuushu`：对局中的动作指令，其中 `riichi` 与 `kyuushu` 仅用于立直麻将
- `/mahjong settlement`：重新打开最近一次结算界面
- `/mahjong rank`：查看分模式段位
- `/mahjong leaderboard [RIICHI|GB|SICHUAN]`：查看指定模式排行榜
- `/mahjong render`、`/mahjong clear`、`/mahjong inspect`：渲染维护与调试
- `/mahjong forceend [tableId]`：管理员强制结束当前对局
- `/mahjong deletetable [tableId]`：管理员删除牌桌

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

## 玩法规则

- 默认新建牌桌使用雀魂风格半庄立直规则。
- `/mahjong mode MAJSOUL_TONPUU` 切换为雀魂风格东风战，`/mahjong mode MAJSOUL_HANCHAN` 切回半庄。
- 默认开启三赤、食断和多人荣和，使用雀魂 profile 的杠宝牌揭示时机。
- 立直后只能打出刚摸入的牌；最后一张牌后只允许荣和反应，不能吃、碰、明杠。
- 国标麻将是额外可选模式，使用 `/mahjong mode GB` 切换；它不改变项目“以雀魂立直为主”的默认玩法定位。

## 三种玩法模式介绍（含实例）

插件内置三套独立规则，用 `/mahjong mode <模式>` 在开局前切换。下面用具体牌例分别讲解。

### 通用对局流程

无论哪种模式，一局的操作流程都一样：

1. 正式服先创建棋牌室；然后站在棋牌室内执行 `/mahjong create` 创建一张空牌桌。
2. 走到东/南/西/北四个悬浮座位牌前点击入座，或用 `/mahjong join <牌桌ID>`。
3. 缺人时由桌主/管理员用 `/mahjong table` 或 `/mahjong addbot` 补 Bot（Bot 默认视为已准备）。
4. `/mahjong start` 切换准备状态；4 个座位都坐满且全部准备后自动开局。
5. **打牌**：轮到你时，直接点击手牌中的某张牌把它打出去。
6. **响应别人的牌**：别人打牌后会出现反应窗口，用命令宣告动作：
   - `/mahjong pon`（碰）、`/mahjong chii <牌A> <牌B>`（吃，仅上家）、`/mahjong minkan`（明杠）
   - `/mahjong ron`（荣和，胡别人打的牌）、`/mahjong skip`（放弃这次响应）
7. **自己回合的特殊动作**：`/mahjong tsumo`（自摸胡）、`/mahjong kan <牌>`（暗杠或加杠）。
8. 一局结束自动弹出结算界面，可用 `/mahjong settlement` 重新查看。

> 立直专属命令：`/mahjong riichi <手牌序号>`（宣告立直并打出该牌）、`/mahjong kyuushu`（开局第一巡九种九牌流局）。这两个命令只在雀魂模式可用。

### 模式一：雀魂立直麻将（默认）

面向熟悉日麻 / 雀魂的玩家，是本插件的主打玩法。

- **对局长度**：`MAJSOUL_HANCHAN` 半庄（东 1 局到南 4 局，共 8 局）；`MAJSOUL_TONPUU` 东风战（东 1 到东 4，共 4 局）。
- **点数**：每人起始 25000，返回点 30000，结束时按分数排名。
- **赤宝牌**：万 / 筒 / 索 各 1 张红 5，共 3 张，红 5 自带 1 番宝牌。
- **起胡**：1 番起胡，必须有役（食断开启，副露后也能靠断幺九等成役）。
- **立直**：门清且牌墙还够摸时可宣告立直，押 1000 点，立直后只能打刚摸的牌。

**实例**：你立直后摸到一张红 5 万自摸。

```
门清自摸（1 番）+ 立直（1 番）+ 自摸的役 + 赤宝牌（红 5 万，1 番）...
```

役叠加越多番数越高，达到役满（如国士无双、大三元）按固定高分结算。这套玩法的乐趣在于赌立直、读牌和役的组合。

### 模式二：国标麻将（GB）

中国国家标准竞赛麻将，番种最丰富、门槛最高。用 `/mahjong mode GB` 切换。

- **牌张**：含万 / 筒 / 索 / 字牌（东南西北中发白），并支持花牌（梅兰竹菊）补牌。
- **起胡**：**8 番起胡**——番数不足 8 番的牌不能胡，这是国标和其他模式最大的区别。
- **计分**：按国标番种表累加番数，番数越高分越多。
- **判定**：番种判定走内置的 `GB-Mahjong` 原生规则库，严格遵循国标解释。

**实例**：你做了一副“混一色 + 碰碰和”。

```
混一色（6 番）+ 碰碰和（6 番）= 12 番 ≥ 8 番 → 可以胡
```

如果只有“碰碰和（6 番）”而凑不够 8 番，就**不能胡**，必须继续做大牌。国标逼着你往大牌方向打，适合追求复杂番种和深度的玩家。

### 模式三：四川麻将（血战到底）

川渝地区流行的快节奏玩法，用 `/mahjong mode SICHUAN` 切换。规则简单刺激。

- **只用序数牌**：只有万 / 筒 / 索，**没有字牌、没有花牌**。
- **缺一门（定缺）**：胡牌时手里必须缺掉一门花色（比如只剩万和筒、一张索子都没有）。
- **血战到底**：一局里点炮 / 自摸**不会立刻结束**，已经胡了的玩家退出，剩下的人继续打，直到第三家也胡牌才结算整局。也就是说一局最多能产生 3 个赢家。
- **任意番起胡**：不像国标要 8 番，四川凑齐基本牌型就能胡。
- **番数封顶**：最高 5 番（计分 32 倍）封顶。

**主要番种与番数**：

| 番种 | 番数 | 说明 |
| --- | --- | --- |
| 平胡 | 1 | 基本牌型 |
| 对对胡 | 1 | 全是刻子（碰 / 杠） |
| 清一色 | 2 | 整副牌只有一门花色 |
| 七对 | 2 | 七个对子 |
| 龙七对 | 3 | 七对里有 1 个“根”（4 张相同） |
| 双龙七对 | 4 | 七对里有 2 个根 |
| 豪华龙七对 | 5 | 七对里有 3 个根（封顶） |
| 将对 | +2 | 牌全是 2 / 5 / 8（与对对胡或七对叠加） |
| 根 | +1/个 | 每个杠额外加 1 番 |
| 金钩钓 | +1 | 对对胡单钓将 |
| 海底捞月 / 杠上花 / 抢杠胡等 | +1 | 各种特殊和牌方式 |

**实例**：你定缺索子，做成一副清一色的七对，其中有一组 4 张筒子（1 个根）。

```
清一色（2 番）+ 七对升级为龙七对（3 番，含 1 根）= 5 番 → 封顶，按 32 倍结算
```

**计分方式**：番数对应 $2^{番数}$ 倍的底分。自摸时另外三家各付一份；点炮时只有点炮的人付。所以四川麻将里“别人点炮你一个人赔”，打得格外紧张刺激。

## 规则参考

- 立直回合流程规则：[docs/riichi-round-flow.md](./docs/riichi-round-flow.md)
- 国标麻将规则来源：[docs/gb-mahjong-rules.md](./docs/gb-mahjong-rules.md)

## 构建

```powershell
.\gradlew.bat build
```

当前 [plugin.yml](./src/main/resources/plugin.yml) 将 CraftEngine 声明为必需依赖，因此运行时需要安装 CraftEngine。

## 配置

默认配置文件位于 [src/main/resources/config.yml](./src/main/resources/config.yml)。

当前推荐的配置结构：

- `database.connection`：数据库类型与 MariaDB 连接目标
- `database.credentials`：MariaDB 用户名与密码
- `database.h2`：本地 H2 配置
- `database.pool`：连接池参数
- `tables.persistence`：持久牌桌文件配置
- `gameRooms`：棋牌室系统——牌桌的空间容器，支持创建限制、进出提醒和离开倒计时
- `integrations.craftengine`：CraftEngine 导出与交互偏好
- `debug`：调试日志

说明：

- 插件会在启动时一次性读取配置
- 修改 `config.yml` 后需要重启服务器或插件进程
- 旧版扁平配置键目前仍然兼容，可平滑迁移

## CraftEngine

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
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | 自定义物品、家具、交互、剔除和资源下发所需的外部运行时插件；本项目不内置该插件 | [GPL-3.0](https://github.com/Xiao-MoMi/craft-engine/blob/main/LICENSE) |
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

构建脚本声明了以下直接运行时库；它们是独立作品，仍适用各自许可证：[MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) (LGPL-2.1)、[MySQL Connector/J](https://github.com/mysql/mysql-connector-j) (GPL-2.0，附 Oracle 额外许可与 Universal FOSS Exception)、[H2](https://github.com/h2database/h2database) (MPL-2.0 或 EPL-1.0)、[HikariCP](https://github.com/brettwooldridge/HikariCP) (Apache-2.0)、[Caffeine](https://github.com/ben-manes/caffeine) (Apache-2.0)、[Kotlin](https://github.com/JetBrains/kotlin) (Apache-2.0) 以及 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Apache-2.0)。Paper 插件加载器会解析 [MahjongPaperLoader.java](./src/main/java/top/ellan/mahjong/bootstrap/MahjongPaperLoader.java) 中列出的 Maven 库；[build.gradle.kts](./build.gradle.kts) 是直接依赖和版本的准确来源。

各平台发行包还包含编译后的 GB-Mahjong JNI 桥接库。Windows 发行包可能包含 [mingw-w64 winpthreads](https://github.com/mingw-w64/mingw-w64/tree/master/mingw-w64-libraries/winpthreads) 的 `libwinpthread-1.dll`，适用其上游 MIT/BSD 类许可通知。原生构建链接的 GCC 运行时部分适用 GPL-3.0 及 [GCC Runtime Library Exception 3.1](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html)。

### 商标与无关联声明

Minecraft 是 Microsoft 的商标。MahjongPaper 为独立开发项目，不是官方 Minecraft 产品，未经 Mojang 或 Microsoft 批准，也与其无关联。对 Mahjong Soul / 雀魂的引用只用于描述规则或风格，不构成关联、赞助或背书。各上游项目的名称和商标归各自权利人所有。

## 许可证

MahjongPaper 原创代码和项目自制资源适用 [MIT License](./LICENSE)，但文件或上述通知另有标注的除外。二次分发第三方内容时必须保留对应署名、许可证和使用条款。本节只用于项目署名和合规整理，不构成法律意见，也不替代具有约束力的许可证或服务条款原文。
