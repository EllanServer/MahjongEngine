# Changelog

## 1.5.0 - 2026-07-16

Feature release for table interaction, river viewing, rules accuracy, storage integration, runtime compatibility, and performance.

中文更新日志：

- **升级要求**：发行包改用 Java 21 字节码，并要求 CraftEngine 26.7 或更高版本。更换 MahjongPaper、CraftEngine 或 InvSync 后必须完整重启服务器。
- **牌河俯视与操作恢复**：新增无需客户端 Mod 的平滑客户端俯视镜头。按 Shift 返回座位后会按当前实时状态恢复定缺、吃碰杠、出牌等操作；俯视期间 ActionBar 倒计时继续运行，超时操作仍会正常执行。
- **倒计时与托管**：连续自动出牌后的下一次时限依次为 60、30、15、10 秒，之后保持 10 秒；玩家手动出牌后恢复 60 秒。只有实际离座或掉线才会交给机器人托管，快速按 Shift 或切换俯视镜头不再夺走玩家控制权。
- **射线交互与隐藏信息**：麻将牌和扁平文字改由精确服务端射线盒决定操作，并仅向相关观察者发送必要的客户端代理，修复加入、定缺、吃碰和出牌偶发无法点击的问题。自己的手牌保持明牌，其他玩家手牌和牌墙只发送未知牌面。
- **牌桌显示与反馈**：修复牌墙上层失去下层支撑后仍悬空、牌面和交互区域不一致、提示文字重叠等布局问题，并统一 ActionBar、聊天提示、牌桌音效和玩家操作反馈。
- **三种玩法校正**：GB 模式对齐 144 张牌、花牌、8 个非花番门槛、单和与固定 16 局流程；立直模式修正鸣牌优先级、振听、杠宝牌时机、流局与延长赛边界；四川模式对齐 T/TFMJ 的直接定缺、无换三张、三番封顶、血战到底、杠分转移和查叫流程。
- **段位与服务器集成**：新增可选 InvSync 2.x 玩家段位存储，并在 InvSync 缺失或不兼容时仅向已启用且健康的 SQL 后端回退。当前自动段位与统计更新仍只适用于四名真人完成的立直对局。
- **资源与本地化**：加入 GB、立直和四川模式对应的摸牌、出牌、鸣牌、流局与和牌音效；新增完整日语游戏消息、安装文档和三种玩法说明。
- **性能与稳定性**：减少牌桌区域更新、展示实体复用、观察者集合和 GB Bot 决策热路径中的排序、装箱及临时分配；缓存调度器反射解析，并加入由八个 runner 并行采样、可信汇总的 GitHub Actions 配对 A/B 性能回归门禁。
- **分发说明**：发行 JAR 现在内嵌并重定位 Sparrow Reflection 与 ASM。第三方声明和运行软件分发条款已更新，重新分发时应保留对应许可证与源码义务说明。

English Release Notes:

- **Upgrade requirements**: Release artifacts now use Java 21 bytecode and require CraftEngine 26.7 or newer. Fully restart the server after replacing MahjongPaper, CraftEngine, or InvSync.
- **River view and control restoration**: Added a smooth client-only overhead camera that requires no client mod. Returning with Shift rebuilds ding-que, reaction, and discard controls from live game state. ActionBar deadlines continue running while viewing the river, and expired decisions still resolve normally.
- **Deadlines and bot ownership**: Consecutive automatic discards shorten the next turn from 60 to 30, 15, and then 10 seconds; later automatic turns remain at 10 seconds. A manual discard restores 60 seconds. Bot control now begins only after a real dismount or disconnect, not a quick Shift press or camera transition.
- **Ray interaction and hidden information**: Mahjong tiles and flat text now use exact server-side ray geometry, backed only by the per-viewer client proxies needed to make vanilla clients report a click. This fixes intermittent join, ding-que, reaction, and discard input failures. Owners receive their visible hand while other hands and wall tiles use unknown faces.
- **Table rendering and feedback**: Fixed unsupported upper wall tiles remaining suspended, mismatched visual and interaction geometry, overlapping controls, and inconsistent ActionBar, chat, sound, and table feedback.
- **Rules corrections**: GB now follows the 144-tile flower flow, eight non-flower-fan floor, single-ron priority, and fixed 16-hand profile. Riichi fixes cover reaction priority, furiten, kan-dora timing, draws, and match extensions. Sichuan now follows the documented T/TFMJ direct ding-que, no exchange-three, three-fan cap, Bloody Battle, kong-transfer, and cha-jiao flow.
- **Rank and server integrations**: Added optional InvSync 2.x player-rank storage, with fallback only to an enabled and healthy SQL backend. Automatic rank and personal-stat updates remain limited to completed four-human Riichi matches.
- **Assets and localization**: Added mode-specific draw, discard, reaction, draw-result, and win sounds for GB, Riichi, and Sichuan, plus complete Japanese game messages and player documentation.
- **Performance and reliability**: Reduced sorting, boxing, and temporary allocations in region updates, display reconciliation, viewer membership handling, and GB bot decisions; cached scheduler reflection resolution and added a paired GitHub Actions A/B gate with eight parallel measurement runners and trusted aggregation.
- **Distribution notice**: Release jars now embed and relocate Sparrow Reflection and ASM. Third-party notices and runnable-distribution terms were updated; redistributors must preserve the applicable license and source-obligation notices.

## 1.4.1 - 2026-06-18

Hotfix for game room exit countdown behavior.

中文更新日志:

- **棋牌室离开倒计时结束时踢出玩家**: 修复玩家离开棋牌室后倒计时结束仅强制结束对局、但未将该玩家从牌桌移除的问题。现在倒计时到期时会同时把离开房间的玩家从牌桌无位移移除。

English Release Notes:

- **Game room exit countdown now removes the player**: Fixed an issue where the countdown only force-ended the match when a player left the game room, but left the player attached to the table. The countdown expiry now also removes the leaving player from the table without moving them.

## 1.4.0 - 2026-06-18

Visual asset release for the MahjongPaper table furniture, seat chair, and dice resources.

中文更新日志:

- **棋牌桌视觉重做**: 使用新的 Blockbench 源文件重建 CraftEngine 牌桌家具模型，加入木纹桌脚、绿色毡面、低矮护边、对称中央底座和四面腰线，让桌面在游戏内更接近真实棋牌桌。
- **桌面对齐优化**: 桌面绿布扩大到与外圈贴合，外圈护边压薄并加高 1 像素，毡面与牌底齐平，避免悬空和 z-fighting。
- **椅子模型自制**: 替换之前的占位 `seat_chair`，自制带木腿、横档、坐板、绿色坐垫、黄铜边和低靠背的胡桃木风格凳子，与新牌桌风格统一。
- **资源包贴图补全**: 新增 `item/table` 系列表格贴图，并将模型引用切换到项目自生成资源，避免继续依赖占位纹理。
- **骰子资源刷新**: 更新骰子贴图和基础模型显示参数，使开局骰子动画在手持、地面、GUI 和固定展示场景下尺寸更稳定。
- **家具锚点调整**: 把牌桌视觉锚点从 0.5 调到 0.375，模型本体保持在 Minecraft 合法坐标范围内，避免越界导致紫色缺失方块。
- **资源归属说明**: 更新资源包 attribution，明确骰子、牌桌贴图、椅子模型与家具模型均为 MahjongPaper 项目生成资产。

English Release Notes:

- **Table visual overhaul**: Rebuilt the CraftEngine table furniture from the new Blockbench source with walnut legs, green felt, low rim guards, a centered base column, and four-sided waistlines so in-game tables look closer to a real mahjong table.
- **Tabletop alignment**: Expanded the felt to meet the rim, raised the rim by 1 px, and aligned the felt top with the tile bottom plane to remove the floating gap and z-fighting.
- **Custom seat chair**: Replaced the placeholder `seat_chair` with a hand-built walnut-style stool featuring wood legs, stretchers, a seat board, a green felt cushion, brass trim, and a low backrest matching the new table.
- **Resource pack textures**: Added the `item/table` texture set and switched the table model to project-generated textures instead of placeholder assets.
- **Dice asset refresh**: Updated dice textures and base-model display transforms for steadier sizing across animation, handheld, ground, GUI, and fixed-display contexts.
- **Furniture anchor tuning**: Lowered the table visual anchor from 0.5 to 0.375 while keeping every cube within Minecraft's legal element range so the model never falls back to a missing-texture purple block.
- **Asset attribution**: Updated the resource-pack attribution to identify dice textures, table textures, the seat chair model, and the table furniture model as MahjongPaper-generated assets.


## 1.3.2 - 2026-06-18

Maintenance release for release-build formatting and clearer game room documentation.

中文更新日志:

- **发布构建修复**: 格式化 UI 测试文件，修复 Release 工作流在 Windows 平台执行 `spotlessKotlinCheck` 时失败的问题。
- **棋牌室教程强化**: README 和中文 wiki 新增“先创建棋牌室，再创建牌桌”的开服推荐流程，补充魔棒选区、青色粒子范围确认、主厅创建和快速测试示例。

English Release Notes:

- **Release build fix**: Formatted UI test sources so the Windows Release build no longer fails in `spotlessKotlinCheck`.
- **Game room documentation**: README and the Chinese wiki now emphasize creating a game room before placing tables, with wand selection, particle-outline confirmation, main-hall setup, and quick-test examples.

## 1.3.1 - 2026-06-18

Hotfix and usability release for CI reliability, command help navigation, and game room selection confirmation.

中文更新日志:

- **Java 编译修复**: 修复 `TableEventCoordinator` 对 Caffeine `Cache` 的旧式 `get(key)` 调用，改为 `getIfPresent`，恢复 Java 编译。
- **Gradle 下载稳定性**: 提高 Gradle Wrapper 下载超时时间并启用重试，降低 GitHub Actions 首次拉取 Gradle 发行包时的超时概率。
- **命令帮助排版**: `/mahjong help` 改为分页式帮助列表，加入标题、副标题、页码状态以及上一页/下一页点击导航。
- **棋室选区粒子预览**: 借鉴 Residence 的领地选择可视化思路，使用粒子描出已选择方块或完整长方体边框，方便创建棋室前确认范围。

English Release Notes:

- **Java compilation fix**: Fixed the outdated Caffeine `Cache#get(key)` call in `TableEventCoordinator` by switching to `getIfPresent`, restoring Java compilation.
- **Gradle download reliability**: Increased the Gradle Wrapper network timeout and enabled retries to reduce first-download timeouts in GitHub Actions.
- **Command help layout**: `/mahjong help` now renders as a paged command list with header, subtitle, page status, and clickable previous/next navigation.
- **Game room selection preview**: Added Residence-inspired particle outlines for the selected block or full cuboid, helping admins confirm the room range before creation.

## 1.3.0 - 2026-06-17

Game room system with core restrictions, wand selection tool, room management commands, bossbar/text mode adaptation, and documentation alignment.

中文更新日志:

- **棋牌室系统（核心限制）**: 新增 `GameRoom` 领域模型、`GameRoomManager` 管理器、`GameRoomListener` 监听器和 YAML 持久化。当 `gameRooms.enabled=true` 且 `gameRooms.restrictNewTables=true` 时，牌桌只能在棋牌室内创建；玩家进出棋牌室有提醒消息；对局中玩家离开棋牌室后启动倒计时，超时强制结束对局。
- **魔棒选区工具**: 新增 `GameRoomWandListener`，使用烈焰棒（Blaze Rod）作为魔棒，左键点击方块设置选区第一点，右键设置第二点，通过 PersistentDataContainer 标识魔棒物品。
- **棋牌室管理命令**: 新增 `/mahjong room` 命令（别名 `gameroom`），支持 `wand`（获取魔棒）、`create`（创建棋牌室）、`delete`（删除棋牌室）、`list`（列出棋牌室）、`info`（查看详情）五个子操作。`create` 优先使用魔棒选区，无选区时以玩家位置为中心按配置半径创建。
- **倒计时警告节奏**: 前段每 15 秒提醒，最后 10 秒按 10/8/6/5/4/3/2/1 逐秒倒计时。玩家返回棋牌室则取消倒计时。
- **Bossbar/桌上文字模式适配**: Bossbar 和桌上中心文字根据游戏模式（RIICHI/GB/SICHUAN）显示不同内容，不再统一显示日麻特有的供托/宝牌。
- **配置模板补全**: 默认配置模板新增 `gameRooms` 配置段，包含 enabled、restrictNewTables、enterExitMessages、leaveCountdownSeconds、defaultRadius、defaultHeight、file 七个配置项。
- **文档矫正**: README、wiki 和 CHANGELOG 与代码实际行为对齐，包括 botmatch 命令支持 GB/SICHUAN 预设、棋牌室系统说明等。

English Release Notes:

- **Game room system (core restrictions)**: Added `GameRoom` domain model, `GameRoomManager`, `GameRoomListener`, and YAML persistence. When `gameRooms.enabled=true` and `gameRooms.restrictNewTables=true`, tables can only be created inside game rooms; enter/exit messages are shown; active players who leave a game room trigger a countdown that force-ends the match on expiry.
- **Wand selection tool**: Added `GameRoomWandListener` using Blaze Rod as the wand. Left-click sets the first corner, right-click sets the second corner. Wand items are identified via PersistentDataContainer.
- **Game room management commands**: Added `/mahjong room` command (alias `gameroom`) with five sub-operations: `wand` (get wand), `create` (create room), `delete` (delete room), `list` (list rooms), `info` (room details). `create` uses the wand selection if available, falling back to player position with configured radius.
- **Countdown warning cadence**: Early phase warns every 15 seconds; final 10 seconds count down at 10, 8, 6, 5, 4, 3, 2, 1. Returning to the room cancels the countdown.
- **Bossbar/table text mode adaptation**: Bossbar and center table text now display mode-appropriate content (RIICHI/GB/SICHUAN) instead of always showing riichi-specific indicators.
- **Config template completion**: Default config template now includes the `gameRooms` section with enabled, restrictNewTables, enterExitMessages, leaveCountdownSeconds, defaultRadius, defaultHeight, and file settings.
- **Documentation alignment**: README, wiki, and CHANGELOG corrected to match actual code behavior, including botmatch supporting GB/SICHUAN presets and game room system documentation.

## 1.2.0 - 2026-06-16

GB/SICHUAN opening procedure and wall management overhaul to match real Chinese Official / Sichuan Mahjong rules.

中文更新日志:

- **两次掷骰开门**: GB/SICHUAN 开局现在按真实国标流程，庄家先掷两骰确定开门方位，被指定方位的玩家再掷两骰确定开门位置。`OpeningDiceRoll` 扩展为 4 参数（两组骰子），渲染层同步更新。
- **14 张王牌区（dead wall）**: 牌墙末尾 14 张分离为 dead wall，仅用于补花/补杠替换。正常摸牌只从 live wall 前端取，live wall 摸完即流局。补花/补杠从 dead wall 尾部取牌，同时从 live wall 尾部补一张到 dead wall 前端，维持 dead wall 大小。
- **门风正确轮转**: `buildFanRequest` / `buildWinRequest` / `toNativeMelds` 均已使用 `logicalSeatOf()` 将物理座位映射为逻辑门风（庄家 = EAST），从第二局起门风刻、圈风刻及原生引擎输入不再错位。
- **四川定缺准备阶段**: 已有完整的交换→定缺两阶段准备流程，定缺后缺门花色牌必须先打。
- **四川一炮多响**: 四川模式下 `allowMultiRon = true`，同一放铳可被多人同时荣和。

English Release Notes:

- **Two dice rolls for wall break**: GB/SICHUAN opening now follows real Chinese Official rules — the dealer rolls two dice to determine which wall side to open, then the player at that side rolls two more dice to determine the break position. `OpeningDiceRoll` expanded to 4 parameters (two pairs of dice); rendering layer updated accordingly.
- **14-tile dead wall**: The last 14 tiles of the wall are reserved as the dead wall for flower/kong replacement draws only. Normal draws come from the live wall front; when the live wall is empty, the hand ends in exhaustive draw. Replacement draws take from the dead wall tail and replenish from the live wall tail to maintain dead wall size.
- **Correct seat wind rotation**: `buildFanRequest` / `buildWinRequest` / `toNativeMelds` all use `logicalSeatOf()` to map physical seats to logical winds (dealer = EAST), so seat wind, round wind, and native engine inputs are correct from the second hand onward.
- **Sichuan dingque preparation**: Full exchange → dingque two-phase preparation flow; after choosing the missing suit, tiles of that suit must be discarded first.
- **Sichuan multi-ron**: In Sichuan mode, `allowMultiRon = true`, allowing multiple players to win on the same discard.

## 1.1.3 - 2026-06-16

Hotfix for Paper 1.21.4+ servers where clicking any custom inventory (settlement, rule settings, table control) threw `IncompatibleClassChangeError`.

中文更新日志:

- InventoryView 兼容性修复: Paper 1.21.4+ 将 `org.bukkit.inventory.InventoryView` 从 interface 改为 abstract class，导致编译期按 interface 调用 `event.getView().getTopInventory()` 的字节码在运行时抛 `IncompatibleClassChangeError`。新增 `PaperCompatibility.getTopInventory()` 反射桥接方法，替换所有 8 处直接调用，低版本和高版本均可正常工作。
- Bot 调度/渲染预计算容错: 为 bot 调度、异步渲染预计算和 tick 分发添加 try/catch 保护，避免单次瞬态异常导致整桌 4-bot 对局冻结或显示永久卡死。

English Release Notes:

- InventoryView compatibility fix: Paper 1.21.4+ changed `org.bukkit.inventory.InventoryView` from an interface to an abstract class, causing `IncompatibleClassChangeError` whenever `event.getView().getTopInventory()` was called on compiled-against-old-Bukkit bytecode. Added a reflective `PaperCompatibility.getTopInventory()` bridge and replaced all 8 direct call sites, restoring inventory UI across all supported Paper versions.
- Bot/render error recovery: wrapped bot scheduling, async render precompute, and tick dispatch in try/catch so a single transient failure cannot freeze a 4-bot match or leave the display permanently stuck.

## 1.1.2 - 2026-06-16

Follow-up hotfix to 1.1.1. The Paper 1.21+ `NoSuchMethodError` regression had two more call sites that were not migrated in 1.1.1.

中文更新日志:

- 漏修补全: 1.1.1 只迁移了 `DisplayEntities` 里的 4 处 `World.spawn(..., Consumer)`，但 `TableDiceAnimationCoordinator` 里另有 2 处（开局骰子动画的骰子实体和结果文本），它们仍然命中已被移除的 `org.bukkit.util.Consumer` 重载。在 Paper 1.21+ 上一执行 `/mahjong botmatch` 等会触发开局动画的命令就抛 `NoSuchMethodError`。本版本把这两处改为同样的 spawn-then-configure 模式。
- 防回归门禁: 新增 `ArchitectureBoundaryTest.World spawn callers do not use the removed Consumer overload`，扫描所有生产 Java/Kotlin 源码，禁止任何 `World.spawn(Location, Class, lambda)` 三参数调用形式。下次有人无意把 lambda 写回 spawn 调用就会在 CI 失败，避免再次引入相同 bug。

English Release Notes:

- Missed callsites: 1.1.1 only migrated the four `World.spawn(..., Consumer)` calls in `DisplayEntities`, but `TableDiceAnimationCoordinator` had two more (the opening-roll dice entities and the result text label) that still bound to the removed `org.bukkit.util.Consumer` overload. Any command that triggers the opening dice animation, e.g. `/mahjong botmatch`, threw `NoSuchMethodError` on Paper 1.21+. Both sites now use the same spawn-then-configure pattern.
- Regression gate: added `ArchitectureBoundaryTest.World spawn callers do not use the removed Consumer overload`. It scans every production Java/Kotlin source file and rejects any three-argument `World.spawn(Location, Class, lambda)` call site, so the same class of bug fails CI before it can ship again.

## 1.1.1 - 2026-06-16

Hotfix release for Paper 1.21+ servers. 1.1.0 was unusable on Paper 1.21.x because every entity render path tripped a `NoSuchMethodError`.

中文更新日志:

- Paper 1.21+ 渲染崩溃修复: 在 1.21 上每次刷新桌面渲染都会抛 `NoSuchMethodError: org.bukkit.World.spawn(Location, Class, org.bukkit.util.Consumer)`，因为 Paper 1.21 已经移除了已弃用的 `org.bukkit.util.Consumer` 重载，而 1.20 的开发包让 lambda 在编译期被解析到这个 forRemoval 的方法上。改成两步 spawn（先 `World.spawn(Location, Class)`，再立即配置实体）后，麻将牌、文本标签、交互盒和方块展示在 1.20.1-26.x 全版本上都能正常生成。
- 可见性次序加固: spawn 之后第一时间设置 `setVisibleByDefault`，防止 entity tracker 在某些版本上抢先广播一帧默认可见状态。

English Release Notes:

- Paper 1.21+ render crash fix: every render tick threw `NoSuchMethodError: org.bukkit.World.spawn(Location, Class, org.bukkit.util.Consumer)` on Paper 1.21.x because Paper removed the deprecated `org.bukkit.util.Consumer` overload while the 1.20 dev bundle had bound our lambdas to it at compile time. The four entity factories (tile/label/interaction/block) now use the long-stable two-argument `World.spawn(Location, Class)` and configure the entity immediately afterwards, restoring rendering across the full 1.20.1-26.x range.
- Visibility ordering hardening: `setVisibleByDefault` is now called first so the entity tracker cannot publish a one-frame window of default visibility before the plugin restricts viewers.

## 1.1.0 - 2026-06-16

Compatibility and architecture release for the post-1.0.0 Paper/Folia line.

- Added Paper 1.21.11 runtime compatibility by registering plugin commands through the Paper lifecycle command registry when available, with the Bukkit command map as the fallback path.
- Updated the Paper plugin loader to resolve libraries from Paper's Maven Central mirror metadata when present, avoiding the newer server warning about direct Maven Central CDN use.
- Refactored table variant dispatch around `VariantVisitor`, including Sichuan-specific visitation, so callers branch through `variant()` instead of open-coded variant checks.
- Moved render snapshot models into the render package and moved `MahjongVariant` into the model package to tighten table/render boundaries.
- Added `TableRenderSubject` plus architecture guards that block render code from depending back on table internals.
- Introduced `TableSessionContext` and `TableSessionMutator`, then migrated session coordinators onto the narrower session-facing contracts.
- Reworked plugin-held runtime collaborators into explicit constructor-injected services for clearer startup wiring and easier testing.
- Added focused architecture, command, context, reload-supplier, and compatibility tests covering the new boundaries and registration paths.
- Verified the built jar on live Paper servers: Paper 1.21.11 build 132 and Paper 26.1.2 build 70 with CraftEngine 26.6.2.

## 1.0.0 - 2026-06-15

First stable release. MahjongPaper 1.0.0 covers Japanese riichi, Chinese national-standard (GB), and Sichuan blood-battle mahjong on a single plugin jar that runs on Paper/Folia 1.20.1 through 26.2.

中文更新日志:

- 全版本兼容：单 jar 同时面向 Paper/Folia 1.20.1 到 26.2，固定 Java 17 字节码与 `api-version: 1.20`，新版 API 通过 `PaperCompatibility` 反射在运行时解析。
- 资源包同源：`pack.mcmeta` 使用 `supported_formats: [15, 75]`，同一份资源包覆盖 1.20 到当前最新 Minecraft 版本。
- 测试基建：测试 classpath 不再锁定 Adventure 版本，跟随 paperDevBundle 自带 Adventure，1.20.1/1.20.4/1.21.1/1.21.4 等多版本测试均能跑通。
- CI 流水线：构建/发布工作流统一在 1.20.1 baseline 编译，跨平台并行打包并合并为 universal jar，自动校验 `api-version` 与字节码 major 版本，避免兼容性回退。
- 三种规则完整实现：日麻立直、国标麻将（含 JNI 加速规则引擎）与四川血战到底，包含番种本地化、机器人策略分层、跨规则回合恢复、加杠/七对/将对等正确性修正。
- 桌面与渲染：CraftEngine 集成、自定义家具锚点、私有手牌交互、可见性同步性能优化、Folia 区域线程安全已稳定。
- 数据与排行：H2/MySQL/MariaDB 跨方言一致性、HikariCP 连接池、麻将之魂风格段位与排行榜服务可用。
- 命令与 UI：`/mahjong` 全套对局、旁观、立直、自摸/荣和、暗杠/明杠、跳过、九种九牌、结算、排行、调试、reload 等命令；MahjongPlay 风格的中央公共动作播报与浮空 UI。
- 国际化：简体、繁体（台/港/澳）、英文配置注释模板与消息包覆盖全部规则术语。
- 数据库：MySQL/MariaDB/H2 支持，结算、积分、段位与排行均使用同一套抽象。
- 文档：CONTRIBUTING、README、玩家 wiki 与多模式玩法指南到位，CI/Release/手动 Test workflow 各司其职。

English Release Notes:

- Single-jar compatibility: one jar runs on Paper/Folia 1.20.1 through 26.2, with Java 17 bytecode and `api-version: 1.20`; newer APIs are dispatched at runtime through `PaperCompatibility`.
- Resource pack: `pack.mcmeta` declares `supported_formats: [15, 75]` so the bundled pack works on Minecraft 1.20 through the current release.
- Test infrastructure: the test classpath now follows whichever Adventure version paperDevBundle ships, so tests pass against 1.20.1, 1.20.4, 1.21.1, and 1.21.4 dev bundles.
- CI pipeline: Build and Release workflows compile against the 1.20.1 baseline, build on Linux/Windows/macOS in parallel, merge into a universal jar, and verify both `api-version` and class-file major versions before publishing.
- Three full rule sets: Japanese riichi, Chinese GB (with the JNI-accelerated rules engine), and Sichuan blood-battle, including localized fan names, layered bot strategies, cross-rule turn-stall recovery, and correctness fixes for kakan, seven pairs, and Jiang Dui.
- Tables and rendering: CraftEngine integration, configurable furniture anchors, dedicated hand-tile interaction entities, visibility-sync performance, and Folia region-thread safety are all production-stable.
- Data and ranking: cross-dialect storage on H2/MySQL/MariaDB through HikariCP, with Mahjong Soul-style tier/rank progression and leaderboards.
- Commands and UI: full `/mahjong` command surface (table, spectate, riichi, tsumo/ron, kan, skip, kyuushu, settlement, leaderboard, debug, reload, …) plus MahjongPlay-style center action announcements and floating UI.
- Localization: Simplified Chinese, Traditional Chinese (TW/HK/MO), and English bundles for all rule sets and config templates.
- Documentation: CONTRIBUTING, README, the player wiki, and the gameplay guide for all three modes are in place; Build, Release, and manual Test workflows are clearly separated.

## 0.9.1-beta.1 - 2026-06-15

Test release focused on Sichuan Mahjong scoring correctness, fan localization, and rules-engine consolidation.

中文更新日志:

- 四川算番修正: 参考 lonng/nanoserver 实现, 修复七对计分逻辑, 不再对带杠七对重复累加“根”。
- 七对分层完善: 七对按“根”数量分为四档, 新增双龙七对(2根, 4番)与豪华龙七对(3根, 5番), 与七对(2番)、龙七对(1根, 3番)形成完整阶梯。
- 将七对支持: 七对牌全部为 2/5/8 时叠加“将对”番, 与对对胡保持同一判定口径。
- 规则引擎收敛: `GbTableRoundController` 的四川算番改为复用 `DefaultSichuanRulesEngine`, 移除重复的私有算番方法与未使用常量, 让算番口径单一来源。
- 番名本地化: 结算界面的四川/国标番名接入 i18n, 原本直接显示的英文标签现按 `fan.*` 键翻译, 并保留 `xN` 计数后缀。
- 翻译补全: 为简体、繁体(台/港/澳)与英文补齐全部四川番名翻译键, 覆盖新增的双龙七对与豪华龙七对。
- 测试补强: 新增七对分层、将七对与“根不重复计分”的规则引擎单元测试, 通过 i18n 键一致性校验。

English Release Notes:

- Sichuan scoring fix: aligned with lonng/nanoserver so seven-pair hands no longer double-count "roots" when a kong is present.
- Seven-pair tiers: layered seven pairs by root count, adding Double Dragon Seven Pairs (2 roots, 4 fan) and Deluxe Dragon Seven Pairs (3 roots, 5 fan) alongside Seven Pairs (2 fan) and Dragon Seven Pairs (1 root, 3 fan).
- All-2/5/8 seven pairs: seven-pair hands made entirely of 2/5/8 tiles now add the "Jiang Dui" fan, matching the all-triplets check.
- Rules-engine consolidation: `GbTableRoundController` now reuses `DefaultSichuanRulesEngine` for Sichuan scoring, removing the duplicated private scoring methods and unused constants so fan composition has a single source of truth.
- Fan localization: settlement fan labels are now translated through i18n `fan.*` keys instead of rendering raw English identifiers, preserving any `xN` count suffix.
- Translations: added every Sichuan fan key across Simplified, Traditional (TW/HK/MO), and English bundles, including the new double/deluxe dragon seven pairs.
- Tests: added rules-engine unit coverage for seven-pair tiers, all-2/5/8 seven pairs, and root non-duplication; passes i18n key-consistency checks.

## 0.9.0-beta.1 - 2026-06-15

Test release for single-jar Paper 1.21.11 through 26.2 compatibility, Sichuan flow validation, and render/network performance hardening.

中文更新日志:

- 单 jar 多版本兼容: 默认发布包固定为 Java 21 字节码与 `api-version: 1.21.11`, 用同一个 jar 面向 Paper 1.21.11 到 26.2, 避免为 26.x 单独打包。
- Paper 兼容验证: 升级 paperweight 到 2.0.0-SNAPSHOT, 默认使用 Paper 1.21.11 开发包构建, CI 额外使用 Paper 26.2 开发包做源码/API 兼容测试。
- 26.1.2 实服验证: 在 Paper 26.1.2 build 70 + CraftEngine 26.6.2 上完成启动冒烟测试, MahjongPaper 正常加载、初始化 H2 数据库、完成 GB 原生规则预热并导出 CraftEngine 资源包。
- Adventure 兼容: Adventure 依赖改为 `compileOnly`, 并对齐 Paper 1.21.11 到 26.2 当前 API 使用的 Adventure 4.26.1, 避免插件 jar 内携带冲突的 Adventure 运行时。
- 运行库更新: 更新 MariaDB、MySQL、H2、HikariCP、Kotlin 与 kotlinx-serialization 等运行依赖, 降低新 Paper/Java 环境下的兼容风险。
- 性能与网络优化: 渲染区域刷新先检查预算再生成实体规格, 减少无效渲染计算、展示实体重刷和潜在发包压力。
- 可见性同步优化: 减少私有/排除可见玩家集合比较时的临时集合分配, 降低高频展示实体同步的内存抖动。
- 旁观者覆盖层优化: 只扫描 `viewer-overlay:` 区域, 不再每次复制并过滤全部渲染区域 key。
- 区块刷新优化: 牌桌 3x3 区块加载检查改为直接查询 `World#isChunkLoaded`, 避免刷新循环中创建临时集合。
- 四川麻将规则验证: 增加血战到底流程测试, 确认点炮胡后对局继续, 已胡玩家不再行动, 第三位玩家胡牌后结算整手牌。
- 国标/四川测试稳定性: 修正暗杠补牌测试牌山、明杠优先级测试手牌和测试用碰牌状态注入方式, 避免测试误判。
- 结算 UI 测试修复: 补充当前玩法变体 mock, 保持立直结算 UI 集成测试与多玩法接口一致。
- 发布护栏: 新增单 jar 兼容测试, 自动检查打包后的 `plugin.yml` / `paper-plugin.yml` 固定最低 API, 并校验字节码目标。
- 文档与 CI: 更新贡献文档中的 26.2 验证命令, CI 同时覆盖 1.21.11 默认发布目标和 26.2 高版本 API 兼容目标。

English Release Notes:

- Single-jar compatibility: release artifacts stay on Java 21 bytecode with `api-version: 1.21.11`, so one jar targets Paper 1.21.11 through 26.2.
- Paper compatibility: upgraded paperweight to 2.0.0-SNAPSHOT, kept the default build on the 1.21.11 dev bundle, and added CI/API validation against the 26.2 dev bundle.
- Server smoke test: verified startup on Paper 26.1.2 build 70 with CraftEngine 26.6.2; MahjongPaper enabled, initialized H2, warmed the GB native bridge, and exported its CraftEngine bundle.
- Adventure alignment: Adventure dependencies are now compile-only and aligned to Paper's current 4.26.1 API line to avoid shipping conflicting Adventure runtime classes.
- Runtime dependency refresh: updated database, pool, Kotlin, and serialization libraries for newer Paper/Java compatibility.
- Render/network performance: render-region updates now check budgets before building entity specs, reducing unnecessary render work, display respawns, and packet pressure.
- Visibility performance: reduced temporary set allocation while reconciling private/excluded display viewers.
- Viewer overlay and chunk checks: overlay cleanup scans only viewer-overlay regions, and table-area chunk checks avoid temporary neighborhood sets.
- Sichuan rules validation: added blood-battle flow coverage where discard win continues the hand and the hand settles after the third winner.
- Test hardening: stabilized GB/Sichuan round tests, settlement UI variant mocks, and added single-jar descriptor/classfile compatibility checks.

## 0.8.0-beta.1 - 2026-04-01

Test release for post-0.7.5 architecture and gameplay fixes.

Version list after `0.7.5`:

- `0.8.0-beta.1` (this release)
- No other tagged versions were published between `0.7.5` and this beta.

中文更新日志（简体）:

- 会话状态架构重构：引入 `SessionState` 抽象，统一会话生命周期、规则切换、渲染刷新、轮转控制与延迟离桌处理，降低 `MahjongTableSession` 的耦合复杂度。
- 机器人策略分层：新增 `BotStrategy` / `BotStrategyFactory`，将立直与国标 AI 行为拆分为 `RiichiBotStrategy` 与 `GbBotStrategy`，便于后续扩展四川规则与变体特化策略。
- 跨规则回合防卡死：修复机器人出牌重试逻辑，针对“不可选手牌位”“出牌失败后仍在当前回合”等边界场景添加恢复路径，避免回合卡住。
- 国标出牌可选性联动：新增 `canSelectHandTile(...)` 对外校验通道，机器人不再盲打非法索引，确保与 UI/规则限制一致。
- 听牌计算稳定性增强：`RiichiPlayerState` 增加 `shanten` 失败降级策略，在主策略抛出 `NoSuchElement` 等异常时自动回退到稳定全量扫描策略，减少异常中断。
- JNI 桥接与文档同步：调整国标 JNI 相关包结构与文档，统一本地规则网关、预热与调用边界，提升维护可读性。
- 数据与性能测试补强：补充/强化 H2 与 MariaDB 跨方言一致性测试、GB 原生网关与预热服务测试、渲染协调器指标与性能基准测试。
- 加杠渲染修复（重点）：加杠牌不再“抬高叠放”，改为“与目标牌同平面并向桌心内移”，修复牌面突出桌边的视觉问题。
- 渲染回归用例更新：`TableRendererTest` 从“高度差断言”改为“同平面 + 距桌心更近断言”，并补齐距离计算辅助函数，确保 `dev` 分支可编译通过。

English Release Notes:

- Session-state architecture refactor: introduced `SessionState` to centralize lifecycle, rule mutation, render refresh, round-flow control, and deferred-leave handling with lower `MahjongTableSession` coupling.
- Bot strategy layering: added `BotStrategy` and `BotStrategyFactory`, splitting variant logic into `RiichiBotStrategy` and `GbBotStrategy` for cleaner extension toward Sichuan/variant-specific AI.
- Cross-variant turn-stall prevention: fixed bot turn retry/recovery paths for edge cases like non-selectable discard slots and failed discard retries while the bot is still the current actor.
- GB discard legality alignment: exposed `canSelectHandTile(...)` so bot discards obey the same hand-index legality constraints as player UI/rule validation.
- Shanten stability improvements: `RiichiPlayerState` now promotes to fallback full-scan strategies when primary shanten evaluation fails (notably `NoSuchElement` class failures), reducing runtime interruption risk.
- JNI bridge and docs alignment: updated GB JNI package/layout boundaries and documentation for clearer warmup, gateway, and native invocation responsibilities.
- Test and reliability coverage expansion: added/updated cross-dialect DB consistency tests, GB native warmup/gateway tests, and render coordinator/performance benchmark coverage.
- Added-kan render fix (key): `kakan` tile placement is now planar and shifted inward toward table center, instead of being raised/stacked above the claimed tile.
- Render regression assertions updated: tests now validate same-plane placement plus center-inward movement, and include the helper needed for stable `dev` branch compilation.

## 0.7.5 - 2026-03-31

Riichi declaration behavior and rule-alignment release.

- Enforced post-riichi tsumogiri behavior: after declaring riichi, only the freshly drawn tile can be discarded.
- Updated hand-tile selection rules so UI interaction also restricts riichi players to the drawn tile.
- Aligned declaration-ron handling with standard riichi rules: if the declaration tile is ron'ed, the riichi deposit is refunded and that riichi declaration is cancelled.
- Added/updated regression tests covering riichi discard constraints, controller tile-selection behavior, and declaration-ron deposit refund handling.

## 0.7.4 - 2026-03-31

Table direction and release stability update.

- Switched table display direction mapping to the intended riichi-style counterclockwise seat flow.
- Aligned wall/hand/meld rendering orientation so the in-game east/south/west/north placement now matches the configured direction model.
- Fixed table bounds anchoring so the table body follows tile-layout direction changes instead of staying in the old position.
- Updated riichi and render regression tests to lock the new orientation behavior.
- Stabilized GB `minkan` priority test assertions by filtering flower-display rows from meld-count checks.

## 0.7.2 - 2026-03-25

Round-visibility and localization release.

- Added `tables.allowFreeMoveDuringRound` so players can leave seats and walk around during active rounds without being treated as having left the table.
- Updated seat dismount/sneak handling and round-start seat watchdog behavior to respect free-move mode.
- Localized the new free-move config comments in `config_zh_CN.yml` and `config_zh_TW.yml`.
- Added `mahjong-utils` to upstream references in `README.md` and `README.zh-CN.md`.

## 0.7.1 - 2026-03-25

Match flow stabilization release.

- Fixed a round-start regression where the previous hand settlement could reopen when a new hand began.
- Added MahjongPlay-style public action announcements in center text for resolved actions (`Pon`/`Chii`/`Kan`/`Ron`/`Riichi`/`Tsumo`/`Kyuushu`), with automatic fade-out timing.
- Improved action resolution detection to announce only when meld/reaction changes are actually applied.
- Cleared stale public action state at discard and new-round boundaries to avoid carry-over overlays.
- Added `table.last_action` localization key across default/Chinese bundles to keep wording consistent.

## 0.7.0 - 2026-03-25

MahjongPlay-style interaction alignment release.

- Aligned action overlay layout with MahjongPlay: fixed-facing action labels, seat-relative placement, and improved row arrangement.
- Added robust dynamic action-button spacing and hitbox sizing using wide-character aware width estimation to prevent label overlap in CJK locales.
- Reworked waiting controls so `Join` / `Ready` / `Leave` hide immediately when round-start dice flow begins (`roundStartInProgress`).
- Optimized submenu transitions to reduce flicker and keep button switching consistent.
- Simplified center/public text output to reduce overload while preserving last-discard ownership.
- Migrated actionable prompts out of chat-click buttons and into in-world controls while keeping suggestion text in chat.
- Added MySQL compatibility support alongside existing database options.
- Fixed and normalized Chinese localization bundles (zh_CN / zh_HK / zh_MO / zh_TW), including action labels and overlay wording consistency.

## 0.6.4 - 2026-03-24

Table and chair stability release / 牌桌与椅子稳定性版本

- Kept table and chair visual regions static during normal gameplay updates, so ready-state and game-info refreshes no longer trigger table/chair re-spawns.
- During regular render updates, MahjongPaper now refreshes board tiles and state overlays while preserving existing table/chair entities.
- Fixed chair interaction mapping to keep seat furniture focused on `JOIN_SEAT`, while ready toggles remain on seat labels/status layers.
- 在常规对局状态刷新中，牌桌与椅子区域保持静态，不再因准备状态或游戏信息更新而重建。
- 常规渲染只刷新牌桌上的麻将牌与状态信息图层，保留已有牌桌/椅子实体不动。
- 修正椅子交互映射：椅子保持用于 `JOIN_SEAT`（入座），准备切换由座位标签/状态层处理。

## 0.6.2 - 2026-03-22

Riichi added-kong display hotfix release.

- Fixed Riichi `kakan` meld projection so display data consistently exports three base tiles plus one stacked tile.
- Added Riichi regression coverage to lock the `kakan` shape (`3 base + 1 stacked`) and prevent future visual regressions.

## 0.6.1 - 2026-03-22

Hotfix and maintenance release.

- Fixed GB added-kong visual output so the meld renders as three base tiles with one stacked tile, matching expected table notation instead of showing an extra base tile.
- Tightened hand interaction hitbox dimensions to align with tile geometry for more precise click targeting.
- Added Dependabot configuration for weekly Gradle and GitHub Actions dependency update PRs.
- Added regression coverage for GB added-kong visual data shape.

## 0.6.0 - 2026-03-22

Turn-transition and meld-visual consistency release.

- Fixed a round-transition bug where replacing a seated player between hands could leave stale previous-hand seat ids in the active round controller, causing wrong discard ownership and blocked turns.
- Updated round startup to recreate the controller whenever live seat assignments no longer match controller seats.
- Fixed added-kong (`kakan`) table rendering so the fourth tile is stacked on top of the called horizontal tile and no longer disappears due to claim-anchor reset.
- Aligned `pon`/`open-kong` source-position mapping with left/middle/right display rules across both Riichi and GB controllers.
- Tightened tsumo-action prompts so actionable UI only shows tsumo when declaration is actually legal.
- Added regression tests for seat-change controller rebuilds, claim-source slot mapping, and kakan stacking behavior.

## 0.5.2 - 2026-03-18

Folia dice animation hotfix release.

- Fixed opening dice animation display teleports so Folia region tasks now use async-safe entity teleport handling instead of synchronous teleports.
- Kept the existing Paper compatibility path unchanged by routing the dice animation updates through the shared scheduler teleport wrapper.

## 0.5.1 - 2026-03-18

Folia compatibility hotfix release.

- Fixed CraftEngine viewer visibility scheduling so Folia no longer reads display-entity state from the viewer region thread during culling show and hide callbacks.
- Reworked tracked cullable metadata to use cached entity ids and UUIDs in cross-thread visibility paths, avoiding `Thread failed main thread check` crashes on active tables.

## 0.5.0 - 2026-03-18

Folia performance and opening flow release.

- Reduced redundant private-entity visibility synchronization so unchanged private viewer sets no longer trigger full `showEntity` and `hideEntity` passes.
- Cached per-viewer render snapshot metadata and switched wall-size consumers to count-only accessors to cut avoidable render flush allocations.
- Memoized GB bot ting evaluation inside discard, kan, and reaction searches to reduce repeated JNI-style scoring work during a single decision window.
- Added benchmark coverage for render snapshot creation and duplicated-hand GB bot discard suggestion so optimization work can be verified before release.

## 0.4.3 - 2026-03-17

Interaction and CraftEngine furniture alignment release.

- Added `/mahjong reload` so MahjongPaper can reload config/runtime services and re-render active tables without a full server restart.
- Fixed duplicate display-action handling so seat joins and ready toggles are no longer processed twice from overlapping entity interaction events.
- Reworked private hand interaction back to dedicated `Interaction` entities so hand tiles remain clickable while the tile visuals keep their movement animation.
- Adjusted custom CraftEngine table and chair furniture anchors so replacing `tableFurnitureId` or `seatFurnitureId` no longer leaves external furniture floating about 1 block and 6 pixels too high.

## 0.4.2 - 2026-03-17

Config template hotfix release.

- Fixed the default `tileItemIdPrefix` YAML value by quoting `mahjongpaper:` in all bundled config templates.
- Added a regression test that loads every bundled config template through Bukkit YAML parsing to prevent invalid defaults from shipping again.

## 0.4.1 - 2026-03-17

Config documentation clarification release.

- Expanded the `integrations.craftengine.items.tileItemIdPrefix` comments in all config templates.
- Clarified that the setting resolves CraftEngine custom item ids, not furniture entities.
- Documented the automatic tile-name suffix rules, including the `back` id for face-down tiles.
- Documented the fallback behavior when `preferCustomItems` is disabled or a CraftEngine custom item cannot be built.

## 0.4.0 - 2026-03-17

CraftEngine and localization update release.

- Fixed the wall render layout so drawing from the live wall no longer causes all wall regions to shift and visually shake.
- Added localized config templates so a newly generated `config.yml` can use English, Simplified Chinese, or Traditional Chinese comments based on the system locale.
- Added `integrations.craftengine.items.tileItemIdPrefix` so all Mahjong tile visuals can resolve CraftEngine custom item ids from a configured prefix.
- Moved all message bundles into `src/main/resources/language/` and updated message index generation and runtime loading to match.
- Updated exported metadata authors to `openai and ellan`.

## 0.3.3 - 2026-03-17

Post-match seat and table state fix release.

- Fixed a bug where leaving your seat after a match ended could still leave you logically stuck in the table.
- Seat occupancy now falls back to the live participant registry once a round is no longer in progress, instead of reading stale finished-match engine seats.
- Added a regression test covering both finished-match seat removal and active-round engine seat usage.

## 0.3.2 - 2026-03-17

CraftEngine table furniture behavior update.

- Merged the built-in 3x3 table hitbox into `mahjongpaper:table_visual` so the default CraftEngine table is a single furniture entity.
- Changed table furniture handling so when `tableFurnitureId` is configured, MahjongPaper uses that furniture as-is, including all of its own hitboxes and behavior.
- Kept the fallback standalone table hitbox only for the built-in block-display table path when `tableFurnitureId` is left empty.

## 0.3.1 - 2026-03-17

Stability and interaction fix release.

- Fixed outdated test imports/packages so `./gradlew test jacocoTestReport` passes again in CI.
- Removed the green felt layer from both the built-in table render path and the CraftEngine `table_visual` model.
- Narrowed the CraftEngine hand-tile hitbox so adjacent hand tiles no longer overlap and mis-select under the pointer.

## 0.3.0 - 2026-03-17

Critical fix release.

- Fixed a severe bug in previous versions where the chair hitbox was positioned too high.
- Lowered the chair collision box by 0.5 blocks so seat interaction and collision align correctly.
- Users running any previous 0.2.x build should upgrade to 0.3.0 immediately.

## Release note

MahjongPaper 0.3.0 is a critical stability release.

Previous versions contained a severe bug in the chair collision/hitbox configuration, which could cause incorrect interaction and placement behavior around seats.

If you are using a 0.2.x version, upgrade to 0.3.0 as soon as possible.
