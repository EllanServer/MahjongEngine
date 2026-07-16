# MahjongPaper Wiki

本页整理 MahjongPaper 当前支持的三种玩法模式，以及插件在服务器中的安装、配置和日常使用方式。它面向两类读者：玩家可以照着开桌、入座、操作；服主可以照着部署、授权、排查。

## 快速概览

| 项目 | 当前行为 |
| --- | --- |
| 服务端 | Paper/Folia，当前构建使用 Paper `1.20.1` 开发基线与 `api-version: 1.20` |
| Java | Java 21 |
| 必需依赖 | `CraftEngine` |
| 可选依赖 | `InvSync 2.x`（保存玩家段位 profile；当前自动更新仅限四真人立直对局） |
| 默认存储 | 已启用的本地 H2；全局牌桌、对局/排名历史和排行榜投影由 SQL 管理 |
| 主命令 | `/mahjong` |
| 普通权限 | `mahjongpaper.command`，默认所有玩家可用 |
| 管理权限 | `mahjongpaper.admin`，默认 OP 可用 |
| 默认模式 | `MAJSOUL_HANCHAN`，雀魂风格半庄立直麻将 |
| 可选模式 | `MAJSOUL_TONPUU`、`MAJSOUL_HANCHAN`、`GB`、`SICHUAN` |
| 配置文件 | `plugins/MahjongPaper/config.yml` |
| CraftEngine 导出目录 | `plugins/CraftEngine/resources/mahjongpaper` |

## 开服推荐路径：先建棋牌室

正式服建议把“创建棋牌室”作为开桌前的第一步。棋牌室是牌桌的空间容器，用来规定牌桌能放在哪里，并提供进出提醒和对局中离开倒计时。默认配置下，`gameRooms.enabled: true` 且 `gameRooms.restrictNewTables: true`，因此管理员需要站在棋牌室内才能创建新牌桌。

推荐流程：

1. 确认管理员拥有 `mahjongpaper.admin`。
2. 使用 `/mahjong room wand` 获取选区魔棒。
3. 左键点击房间一个角，右键点击对角。
4. 观察青色粒子边框，确认棋牌室范围覆盖整个游玩区域。
5. 使用 `/mahjong room create main-hall 主厅` 保存棋牌室。
6. 站在棋牌室内执行 `/mahjong create` 创建牌桌。
7. 用 `/mahjong room info main-hall` 检查世界、坐标和大小。

这个流程适合大厅、包间、活动区等固定场地。临时测试时，可以不使用魔棒，直接站在中心点执行 `/mahjong room create test-room`，插件会按 `gameRooms.defaultRadius` 和 `gameRooms.defaultHeight` 自动生成一个区域。

## 安装与首次启动

完整步骤、升级边界、InvSync 回退和排障见[安装、升级与首次开服](./installation.zh-CN.md)。最低要求是 Java 21、Paper/Folia 与 CraftEngine 26.7+；Java 17 不受支持。InvSync 2.x 可选；缺失或不可用时，只有 SQL 已启用且健康才会按默认策略接管段位 profile，否则段位 backend 不可用。

## 牌桌生命周期

一张牌桌从创建到结束大致是这个流程：

1. 管理员先创建棋牌室；如果启用了创建限制，则站在棋牌室内使用 `/mahjong create` 创建空牌桌。
2. 玩家点击东、南、西、北四个座位悬浮标签入座，也可以使用 `/mahjong join <table_id>` 加入。
3. 开局前可使用 `/mahjong mode <mode>` 切换玩法，或用 `/mahjong rule <key> <value>` 调整部分规则。
4. 缺人时可用 `/mahjong addbot` 补 Bot。Bot 默认视为已准备。
5. 玩家点击自己的座位悬浮标签，或使用 `/mahjong start` 切换准备状态。
6. 四个座位坐满并全部准备后，牌局自动开始。
7. 一局结束后弹出结算界面；可用 `/mahjong settlement` 重新打开最近一次结算。
8. 一局或整场结束后，玩家需要再次准备，才会开始下一局或新一场。

开局前离开会立即退座。开局后使用 `/mahjong leave` 会标记为本局结束后离开。

## 三种玩法模式

| 模式 | 快速识别 | 规则文档 |
| --- | --- | --- |
| 雀魂风格立直 | 136 张、三赤、王牌、必须有役；基础东风 4 手/半庄 8 手，但连庄和终局延长会增加手数 | [立直麻将完整规则](./riichi-rules.zh-CN.md) |
| 国标 `GB` | 144 张、8 花、补花可选、两次各掷两骰、8 个非花牌 fan 起胡；严格预设净分 0、固定 16 手且不连庄 | [国标麻将完整规则](./gb-mahjong-rules.zh-CN.md) |
| 四川 `SICHUAN` | 108 张、直接定缺、不能吃、血战到底；T/TFMJ 默认 8 副、净分 0 | [四川麻将完整规则](./sichuan-rules.zh-CN.md) |

三份规则文档都包含现实书面来源、视频学习路径和 Bilibili 备用教程。外部视频只用于理解现实牌桌，可能采用不同房规；插件内最终以 MahjongPaper profile 为准。

新配置使用 `EARLY_KAN_DORA` 表示只改变立直杠宝牌揭示时机；旧 `TOURNAMENT` 仅为存档兼容，不是完整 WRC。国标的通用起始分、目标分或赛长覆盖只用于旧桌/房规兼容，修改后不再是严格 MCR。四川以 T/TFMJ 01—2024 为基线，3 番/8 单位封顶、无默认换三张或末四强制胡；不提供模糊的 `SICHUAN_TOURNAMENT`。

## 玩家操作指南

### 查找和进入牌桌

| 目的 | 操作 |
| --- | --- |
| 查看附近或活动牌桌 | `/mahjong list`，需要管理员权限 |
| 加入指定牌桌 | `/mahjong join <table_id>` |
| 点击入座 | 点击座位悬浮标签 |
| 观战 | `/mahjong spectate <table_id>` |
| 退出观战 | `/mahjong unspectate` |
| 离开座位 | `/mahjong leave` |
| 打开牌桌面板 | `/mahjong table [table_id]` |

### 准备和开始

| 目的 | 操作 |
| --- | --- |
| 切换准备状态 | 点击自己的座位标签，或使用 `/mahjong start` |
| 查看当前桌状态 | `/mahjong state` |
| 查看当前规则 | `/mahjong rule` 或 `/mahjong table` |
| 切换模式 | 桌主/管理员在 `/mahjong table` 或 `/mahjong mode <MAJSOUL_TONPUU|MAJSOUL_HANCHAN|GB|SICHUAN>` 中调整 |

建议只在开局前或两局之间切换模式。牌局进行中切换模式容易让玩家误解当前局的实际规则。

### 打牌和反应

自己的回合可以点击手牌打出。别人打出牌后，如果你能吃、碰、杠或胡，插件会给出反应提示，使用对应命令提交。

牌名参数使用内部牌名的小写形式，例如：

| 牌 | 参数 |
| --- | --- |
| 1 万 | `m1` |
| 赤 5 万 | `m5_red` |
| 9 筒 | `p9` |
| 3 索 | `s3` |
| 东 | `east` |
| 中 | `red_dragon` |

例子：

```text
/mahjong chii m3 m4
/mahjong kan p5
```

### 结算和段位

| 目的 | 命令 |
| --- | --- |
| 重新打开最近结算 | `/mahjong settlement` |
| 查看雀魂风格段位 | `/mahjong rank` |
| 查看模式排行榜 | `/mahjong leaderboard [RIICHI|GB|SICHUAN]` |

段位系统要求 `ranking.enabled: true`，并需要一个可用的段位 backend。InvSync 2.x 已启用且兼容时由 InvSync 管理；否则只有回退已开启且 SQL 健康时才由数据库接管。两者都不可用时，`/mahjong rank` 会提示不可用。

## 服主管理指南

### 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mahjongpaper.command` | true | 允许使用 `/mahjong` 主命令 |
| `mahjongpaper.admin` | OP | 允许创建牌桌、测试桌、维护渲染、强制结束、删除牌桌、重载配置 |

管理员命令：

| 命令 | 用途 |
| --- | --- |
| `/mahjong create` | 在当前位置创建牌桌；开启棋牌室限制时必须站在棋牌室内 |
| `/mahjong botmatch [hanchan|tonpuu]` | 创建 4 Bot 立直测试桌并进入观战 |
| `/mahjong render` | 重新渲染当前牌桌 |
| `/mahjong inspect` | 显示渲染锚点和方向诊断 |
| `/mahjong clear` | 清理当前牌桌展示实体 |
| `/mahjong forceend [table_id]` | 强制结束当前或指定牌桌 |
| `/mahjong deletetable [table_id]` | 删除当前或指定牌桌 |
| `/mahjong reload` | 重载配置并重渲染活动牌桌 |

`forceend` 和 `deletetable` 的目标解析顺序是：显式传入的 `table_id`，玩家当前所在或观战的牌桌，最后是玩家附近最近的牌桌。

### 桌主与牌桌面板

`/mahjong create` 创建的牌桌会记录创建者为桌主，但不会自动让创建者入座。桌主或管理员可使用 `/mahjong table [table_id]` 打开牌桌控制面板，统一管理：

- 查看桌主、座位、准备和规则概况。
- 打开规则 GUI，切换模式或调整规则。
- 开局前添加/移除 Bot。
- 在 4 人满员且全部准备后手动开始。
- 刷新展示实体。
- 删除尚未开始的牌桌。
- 使用 `/mahjong table owner <玩家名> [table_id]` 把桌主转让给已经入座的在线玩家。

普通玩家也可以打开面板查看状态、准备、离桌、查看规则或结算，但不能修改规则、Bot 或删除牌桌。若桌主离开座位，桌主会转交给下一位真人玩家；重启后旧桌若没有记录桌主，第一位真人入座会成为桌主。

牌桌持久化恢复的是牌桌位置、桌主、玩法和规则配置。当前不会恢复服务器关闭瞬间的中途手牌、牌墙、分数或反应窗口；重启后的玩家需要重新准备开局。

### 配置重点

配置文件位于 `plugins/MahjongPaper/config.yml`。当前主要配置块如下：

| 配置块 | 用途 |
| --- | --- |
| `database` | 数据库总开关、失败策略、连接类型 |
| `database.connection` | MariaDB/MySQL 地址、端口、库名和连接参数 |
| `database.credentials` | MariaDB/MySQL 用户名和密码 |
| `database.h2` | 本地 H2 数据库路径和参数 |
| `database.pool` | 数据库连接池大小和超时 |
| `tables.startupRebuildBatchSize` | 启动时分批恢复牌桌展示的批量大小 |
| `tables.allowFreeMoveDuringRound` | 是否允许牌局中自由移动 |
| `tables.persistence` | SQL 持久牌桌恢复开关；旧 `file` 字段仅保留配置兼容 |
| `gameRooms` | 棋牌室系统——牌桌的空间容器，创建限制、进出提醒、离开倒计时 |
| `ranking` | 雀魂风格段位、房间档位，以及 InvSync 探测/数据库回退策略 |
| `integrations.craftengine` | CraftEngine bundle 导出、物品、家具和兼容性设置 |
| `debug` | 调试日志分类 |

配置会在插件加载时读取。使用 `/mahjong reload` 可以重载配置、重建 CraftEngine 桥接并刷新活动牌桌。

### 数据库与段位

默认配置已启用 H2 本地数据库，适合小服或测试。全局持久牌桌、`round_history`、`rank_history` 和排行榜投影始终由 MahjongPaper 的 SQL 管理；需要共享、长期统计或外部备份时，可以把 `database.connection.type` 调整为 MariaDB/MySQL，并填写连接信息。

`ranking.enabled: true` 时，插件会保存雀魂风格段位 profile。InvSync 2.x 已启用且兼容时由它保存；InvSync 缺失、禁用、不兼容或回调运行失败时，只有 `fallbackToDatabase: true` 且 SQL 已启用、健康才会回退数据库，否则 backend 为 `UNAVAILABLE`。当前仅四名真人完成的立直对局自动更新段位与个人统计；`GB`、`SICHUAN` 选择器只读取已有或迁移数据。项目不声明未经提供的 InvSync Maven 坐标，也不打包其 API。

InvSync 公开 addon API 只能在 `onSave` 回调中写入。段位更新后到下一次 InvSync 保存前若服务器进程异常崩溃，本次更新可能尚未落盘，因此生产环境必须开启 InvSync auto-save 与 world-save。由于仓库没有链接其付费离线 API jar，InvSync 模式下 `/mahjong rank` 只读取已经同步进本地缓存的在线玩家。完整部署与迁移说明见 [InvSync 玩家段位接入](./invsync-player-rank.zh-CN.md)。

### CraftEngine 与资源

MahjongPaper 使用 CraftEngine 处理以下内容：

- 自定义麻将牌物品。
- 牌桌和座位家具 hitbox。
- 家具交互事件路由。
- tracked entity culling 兼容。
- 可选的 PacketEvents 兼容映射注入，用于部分反作弊环境。

常用配置：

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `integrations.craftengine.exportBundleOnEnable` | `true` | 启动时导出 MahjongPaper 的 CraftEngine bundle |
| `integrations.craftengine.bundle.folder` | `mahjongpaper` | 导出到 CraftEngine `resources` 下的文件夹名 |
| `integrations.craftengine.items.preferCustomItems` | `true` | 优先使用 CraftEngine 自定义物品 |
| `integrations.craftengine.items.riichiTileItemIdPrefix` | `mahjongpaper:` | 立直牌物品 ID 前缀 |
| `integrations.craftengine.items.gbTileItemIdPrefix` | `mahjongpaper:` | 国标和四川牌物品 ID 前缀 |
| `integrations.craftengine.furniture.preferHitboxInteraction` | `true` | 优先使用家具 hitbox 交互 |
| `integrations.craftengine.furniture.tableFurnitureId` | `mahjongpaper:table_visual` | 牌桌家具 ID |
| `integrations.craftengine.furniture.seatFurnitureId` | `mahjongpaper:seat_chair` | 座位家具 ID |

如果牌桌交互异常，优先确认 CraftEngine 已加载、bundle 已导出、资源包已正确生成并下发给玩家。

## 命令速查

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/mahjong help` | 玩家 | 显示帮助 |
| `/mahjong join <table_id>` | 玩家 | 加入牌桌 |
| `/mahjong leave` | 玩家 | 离开座位或退出观战 |
| `/mahjong spectate <table_id>` | 玩家 | 观战牌桌 |
| `/mahjong unspectate` | 玩家 | 退出观战 |
| `/mahjong table [table_id]` | 玩家 | 打开牌桌控制面板 |
| `/mahjong table owner <玩家名> [table_id]` | 桌主/管理员 | 转让桌主给已入座玩家 |
| `/mahjong mode <mode>` | 桌主/管理员 | 应用模式预设 |
| `/mahjong rule [key] [value]` | 玩家；修改需桌主/管理员 | 查看规则 GUI，或修改开局前规则 |
| `/mahjong start` | 玩家 | 切换准备状态 |
| `/mahjong state` | 玩家 | 查看当前状态 |
| `/mahjong riichi <hand_index>` | 玩家 | 立直专用 |
| `/mahjong kyuushu` | 玩家 | 立直专用九种九牌 |
| `/mahjong tsumo` | 玩家 | 自摸 |
| `/mahjong ron` | 玩家 | 荣和 |
| `/mahjong pon` | 玩家 | 碰 |
| `/mahjong minkan` | 玩家 | 明杠 |
| `/mahjong chii <tile_a> <tile_b>` | 玩家 | 吃，四川模式不可用 |
| `/mahjong kan <tile>` | 玩家 | 暗杠或加杠 |
| `/mahjong skip` | 玩家 | 放弃当前反应 |
| `/mahjong settlement` | 玩家 | 打开最近结算 |
| `/mahjong rank` | 玩家 | 查看段位 |
| `/mahjong leaderboard [mode]` | 玩家 | 查看分模式排行榜 |
| `/mahjong addbot` | 桌主/管理员 | 开局前添加 Bot |
| `/mahjong removebot` | 桌主/管理员 | 开局前移除 Bot |
| `/mahjong create` | 管理员 | 创建牌桌；开启棋牌室限制时必须站在棋牌室内 |
| `/mahjong botmatch [MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN]` | 管理员 | 创建 4 Bot 测试桌 |
| `/mahjong list` | 管理员 | 列出活动牌桌 |
| `/mahjong render` | 管理员 | 强制刷新牌桌展示 |
| `/mahjong inspect` | 管理员 | 渲染诊断 |
| `/mahjong clear` | 管理员 | 清除展示实体 |
| `/mahjong forceend [table_id]` | 管理员 | 强制结束牌桌 |
| `/mahjong deletetable [table_id]` | 管理员 | 删除牌桌 |
| `/mahjong room wand` | 管理员 | 获取魔棒选区工具 |
| `/mahjong room create <id> [名称]` | 管理员 | 用选区或当前位置创建棋牌室 |
| `/mahjong room delete <id>` | 管理员 | 删除棋牌室 |
| `/mahjong room list` | 管理员 | 列出棋牌室 |
| `/mahjong room info <id>` | 管理员 | 查看棋牌室详情 |
| `/mahjong reload` | 管理员 | 重载配置 |

## 棋牌室系统

棋牌室是牌桌的空间容器，用于限制牌桌的创建位置、提供进出提醒和对局中离开倒计时。

### 核心功能

| 功能 | 说明 |
| --- | --- |
| 牌桌创建限制 | `gameRooms.restrictNewTables: true` 时，新牌桌只能在棋牌室内创建；棋牌室外的已有牌桌仍可正常使用 |
| 进出提醒 | `gameRooms.enterExitMessages: true` 时，玩家进出棋牌室会收到提示消息 |
| 离开倒计时 | 对局中的玩家离开棋牌室后开始倒计时（默认 60 秒），超时则强制结束对局；玩家返回棋牌室则取消倒计时 |

### 倒计时警告节奏

- 前段：每 15 秒提醒一次（如 60s、45s、30s、15s）
- 最后 10 秒：10、8、6、5、4、3、2、1 逐秒倒计时

### 配置项

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gameRooms.enabled` | `true` | 启用棋牌室系统 |
| `gameRooms.restrictNewTables` | `true` | 限制新牌桌只能在棋牌室内创建 |
| `gameRooms.enterExitMessages` | `true` | 进出棋牌室时显示提示 |
| `gameRooms.leaveCountdownSeconds` | `60` | 离开倒计时秒数（最小 5） |
| `gameRooms.defaultRadius` | `10` | 中心点创建棋牌室的默认半径 |
| `gameRooms.defaultHeight` | `8` | 中心点创建棋牌室的默认高度 |
| `gameRooms.file` | `game-rooms.yml` | 棋牌室持久化文件 |

棋牌室数据保存在 `plugins/MahjongPaper/game-rooms.yml` 中。

### 创建棋牌室

有两种方式创建棋牌室：使用游戏内魔棒工具（推荐）或手动编辑配置文件。推荐优先使用魔棒，因为它能即时显示粒子边框，方便确认区域是否覆盖完整。

#### 方式一：魔棒工具（推荐）

魔棒工具类似 WorldEdit 的选区功能，通过两点选区定义棋牌室区域。

**获取魔棒**：

```
/mahjong room wand
```

执行后会获得一根烈焰棒（Blaze Rod），物品名和描述会标明这是麻将选区工具。

**使用魔棒选区**：

| 操作 | 效果 |
| --- | --- |
| 左键点击方块 | 设置选区第一点（最小角） |
| 右键点击方块 | 设置选区第二点（最大角） |

两点确定一个长方体（AABB）区域。设置成功后会收到提示消息，显示当前选区坐标；同时会出现青色粒子边框，用来确认棋牌室的实际范围。粒子边框只会显示给当前管理员，不会影响其他玩家。

选区建议：

- 第一、第二点可以任意先后点击，插件会自动计算最小/最大坐标。
- 选区高度要覆盖玩家活动高度和牌桌展示高度，不要只框地面一层。
- 如果棋牌室是大厅，建议比墙体内侧略大一圈，避免牌桌中心点贴边时被判定在区域外。
- 如果右键后发现粒子边框不对，重新左键/右键选择即可，旧预览会被刷新。

**创建棋牌室**：

选好两点后，执行：

```
/mahjong room create <id> [名称]
```

- `id`：棋牌室唯一标识，只能包含小写字母、数字、下划线和短横线。
- `名称`：可选的显示名称，不填则使用 id 作为名称。

如果已有选区，会使用选区坐标创建；如果没有选区，则以玩家当前位置为中心，按配置中的 `defaultRadius` 和 `defaultHeight` 创建。

**完整示例：创建一个主厅棋牌室**：

```
/mahjong room wand                          # 获取魔棒
# 左键点击主厅地面一角
# 右键点击主厅对角的上方或地面方块
# 确认青色粒子边框覆盖整个主厅
/mahjong room create main-hall 主厅          # 用选区创建棋牌室
/mahjong room info main-hall                # 检查保存后的范围
/mahjong create                             # 站在主厅内创建牌桌
```

**快速测试示例**：

```
/mahjong room create quick-room             # 无选区时以玩家位置为中心创建
/mahjong create                             # 在 quick-room 内创建牌桌
```

**其他棋牌室命令**：

| 命令 | 说明 |
| --- | --- |
| `/mahjong room delete <id>` | 删除指定棋牌室 |
| `/mahjong room list` | 列出所有棋牌室 |
| `/mahjong room info <id>` | 查看棋牌室详情（世界、坐标、大小、所有者） |

#### 方式二：手动编辑配置文件

直接编辑 `plugins/MahjongPaper/game-rooms.yml`：

```yaml
rooms:
  my-room:                          # 棋牌室 ID（小写字母、数字、下划线、短横线）
    name: "我的棋牌室"               # 显示名称
    world: world                     # 世界名称
    minX: -50                        # 区域最小 X
    minY: -10                        # 区域最小 Y
    minZ: -50                        # 区域最小 Z
    maxX: 50                         # 区域最大 X
    maxY: 20                         # 区域最大 Y
    maxZ: 50                         # 区域最大 Z
    owner: "玩家UUID"                # 可选，所有者 UUID
```

**步骤**：

1. 停止服务器或确保没有活跃牌桌。
2. 打开 `plugins/MahjongPaper/game-rooms.yml`。
3. 在 `rooms:` 下添加新条目，填写 ID、名称、世界名和区域坐标。
4. 保存文件，重启服务器或使用 `/mahjong reload` 重载。

**确定区域坐标**：

- 站在棋牌室的一个角落，记下坐标作为 `minX/minY/minZ`。
- 走到对角线的另一个角落，记下坐标作为 `maxX/maxY/maxZ`。
- 两个角定义一个长方体（AABB），牌桌中心必须在这个长方体内才算"在棋牌室内"。

**示例**：创建一个以 (0, 64, 0) 为中心、半径 15、高度 10 的棋牌室：

```yaml
rooms:
  main-hall:
    name: "主厅"
    world: world
    minX: -15
    minY: 60
    minZ: -15
    maxX: 15
    maxY: 69
    maxZ: 15
```

## 常见问题

### 为什么我不能创建牌桌？

`/mahjong create` 需要 `mahjongpaper.admin`。普通玩家只能加入、观战和进行牌局操作。此外，如果棋牌室系统已启用且 `gameRooms.restrictNewTables` 为 `true`，则牌桌只能在棋牌室内创建，在棋牌室外尝试创建会提示"必须在棋牌室内"。

### 为什么国标胡不了？

国标模式要求至少 8 番。番数不足时，即使牌型完成也不能和牌。

### 为什么四川不能吃？

当前四川模式按血战到底方向实现，不开放吃牌。能用的主要副露操作是碰和杠。

### 为什么 `/mahjong rank` 不可用？

先确认 `ranking.enabled: true`。使用数据库后端时，数据库必须可用；使用 InvSync 后端时，玩家必须在线且已完成同步。InvSync 不可用但同时关闭了 `fallbackToDatabase` 时，段位后端会进入 `UNAVAILABLE`。

### 为什么座位或牌桌点不动？

优先检查 CraftEngine 是否加载成功、MahjongPaper bundle 是否导出、资源包是否正确下发。然后使用 `/mahjong render` 重渲染牌桌；需要进一步定位时使用 `/mahjong inspect` 查看锚点和方向。

### 修改配置后要重启吗？

普通配置可以先尝试 `/mahjong reload`。如果更换了插件 jar、CraftEngine 本体、服务端版本或资源包生成方式，建议完整重启。
