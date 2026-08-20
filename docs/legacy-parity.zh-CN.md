# 2.0 与旧版体验对齐矩阵

本文件以 Git 标签 `v1.5.0` 定义 MahjongPaper 2.0 的迁移口径：玩家能感知的合理体验默认保留，规则错误、隐私泄漏、阻塞式实现和旧架构不保留。这里的“对齐”是行为契约，不是复制旧代码。

本矩阵只覆盖插件主玩法、桌面交互与玩家手感。1.5 的棋牌室区域管理、InvSync 段位后端以及调试/维护命令属于外围运营集成，不作为主玩法一致性的阻塞项，后续应按独立功能跟踪。保护插件软依赖已不再列为外围项：见下表「领地保护」行。

## 判定原则

1. 规则裁定以各规则包声明的权威规范和金标测试为准。旧版结果与规范冲突时修正规则，不复刻错误。
2. 操作手感、视角、布局、资产、声音、提示、房间管理和机器人属于核心体验，应由通用模块复用。
3. 桌子、椅子、牌、点棒、命中区和静态姿态优先由 CraftEngine 配置；Java 只投影随比赛状态变化的语义。
4. Paper/CraftEngine 事件线程只做 O(1) 路由和有界入队。任何单桌规则、数据库、渲染或自动化故障只能暂停该桌。
5. 不允许 `LEGACY`、`SHADOW`、运行时 fallback、旧混合控制器或具体玩法类型重新进入核心生产路径。

## 可执行矩阵

| 能力 | 旧版基线 | 2.0 契约 | 当前状态 | 验收证据 |
| --- | --- | --- | --- | --- |
| 暗手出牌 | 首击选牌、再击确认；选中牌上抬 | 三玩法共用同一选择器；上抬 `0.06`；40ms 重复事件抑制；Shift 取消 | 已对齐 | interaction 单元测试与 CE 私有投影测试 |
| 看牌方式 | 座位内进入桌面俯视，Shift 返回 | 高度 `4.5`、16 tick 过渡、只读、离座/离线强制清理；默认由客户端完成后 10 tick 插值，协议不兼容时退回 16 个服务端关键帧 | 已对齐 | overhead camera 生命周期、插值成员选择与到达时序测试 |
| 桌面几何 | 四向现实麻将桌、手牌/牌河/副露相对座位排列 | 唯一 `UniversalTableLayout`；规则只提供牌数、墙长、语义顺序 | 已对齐 | 三规则表面适配器和布局缓存测试 |
| 布局尺寸 | `TableRenderConstants` 的牌宽高厚、间距、桌半长、抬升、动作行距 | 同值改为 `config.yml` 的 `layout.geometry`，12 项与 1.5.0 逐一相同；牌河仍为每行 6 张，动作按钮仍为每行 4 个 | 已对齐 | `SceneGraphTest`（含 `discardRiverWrapsAfterSixTiles`）、`ActionLabelPolicy.BUTTONS_PER_ROW` |
| 最新弃牌提示 | 桌心悬浮一张 2 倍大的最新弃牌，便于全场判断是否鸣牌 | 同样在桌心 `y=0.68`、`scale=2.0` 投影一份公开副本；无待处理弃牌时节点缺席由 differ 回收 | 已对齐 | `SceneGraphTest.newestDiscardIsEchoedAtTableCentreLikeV15` |
| 桌椅牌资产 | 同一套桌、椅、麻将牌与点棒 | 全玩法复用 CraftEngine furniture/item；静态 pose、hitbox、culling 留在 YAML | 已对齐 | bundle manifest 与 CE 配置契约测试 |
| 私有信息 | 本人看正面，其他人看牌背 | 世界实体永不包含暗手正面；仅授权客户端收到私有投影 | 已改正并对齐手感 | 私有场景授权测试 |
| 动作按钮 | 吃、碰、杠、和、跳过等按当前玩法出现 | 规则包产生合法动作和稳定排序；核心只使用通用槽位 | 已对齐主链路 | rule-pack TCK、action projection 测试 |
| 房主离桌 | 控制权转给仍在座的真人 | 同一 lobby revision 原子转给座位序最前的剩余玩家；空桌保留持久桌所有者直到清理 | 已对齐 | `LobbyReducerTest` |
| 房主主动转让 | 可在开局前把房主交给另一名玩家 | 仅当前房主可转给在线真人；命令和 revision-bound 桌面动作都进入同一 lobby actor，不允许机器人或离线座位接管 | 已对齐 | `LobbyReducerTest`、locale/bundle 门禁 |
| 断线重连 | 保留活动牌局座位并恢复桌面 | 保留路由和规则状态；清理临时选择/镜头；重连后重放最新授权投影 | 已对齐基础链路 | presence、private desired-state 测试 |
| 骰子与开门 | 开局掷骰并按玩法决定开门位置 | 规则包只给出确定性骰点、开门座位和断墙栈；CE 固定槽位使用客户端 20Hz 动画纹理滚动，服务端只发送开始与权威点数揭示并旋转墙索引 | 已对齐并修正规范差异 | 三规则固定种子测试、SPI TCK、CE variant/资源包生成与阶段时序测试 |
| 规则音效 | 摸、打、吃、碰、杠、和、立直、骰子开门等反馈 | SPI/开门端口发出瞬时语义 cue；同版本规则资源 ZIP 映射本规则命名空间 event，Paper 每玩家异步批量播放，失败不影响 actor 或 CE 动画 | 已实现并完成代码/资源分离 | cue TCK、资源 ZIP 完整性测试、开门阶段测试、声音参数校验 |
| 机器人/托管 | 可补机器人、机器人自动行动；掉线可托管 | `BOT` 座位可持久恢复；掉线/重连与 `/mahjong auto` 只投递有界消息；三规则包各自选牌，核心每桌只保留一个 revision-bound 任务 | 已实现主链路 | rule-pack TCK、actor 定时/陈旧任务、lobby bot 恢复测试 |
| 机器人牌力 | 会吃碰杠、按听牌/番数取舍、九种九牌满 11 种才流局 | 三规则包各自实现听牌感知大脑，镜像 1.5.0 打分 `1_000_000 + 最大番*10_000 + 合法听牌数*100 + 番数合计`，鸣牌只在严格优于过牌时成立；杠改为「不使手牌变差即杠」（1.5.0 只在杠能制造听牌时才杠，实际几乎永不杠） | 已对齐并有意超越一处 | `McrAutomationQualityTest`、`RiichiAutomationQualityTest`、`SichuanAutomationQualityTest`（各驱动 6~8 局全自动对局并断言确有鸣牌与杠） |
| 思考时间手感 | 出牌 60/30/15/10 秒防挂机阶梯；其他决策 5 秒基础 + 每手 20 秒共享加时池 | 出牌阶梯一致；其他决策同样为 5 秒基础加 20 秒**按手共享**加时池，答得快不扣池，池耗尽后只剩基础时间 | 已对齐 | `HumanDecisionDeadlineControllerTest`（池扣减、快答免扣、超时清池、按手重置） |
| 自动代打预警 | 行动栏倒计时 `Auto-discard in <n>s` / `Auto-skip in <n>s`，不会无声被代打 | 同一桌第二个有界定时器在截止前 5 秒通过 `HumanDecisionWarningPort` 发出预警，插件投递到玩家自己的调度器；窗口本身不长于 5 秒时不预警 | 已对齐 | `HumanDecisionDeadlineControllerTest.aSeatIsWarnedBeforeItIsPlayedAutomatically`、`aWindowNoLongerThanTheWarningLeadIsNotWarnedAbout` |
| 领地保护 | 通过 AntiGriefLib 适配约 30 个领地插件，并以 `load: BEFORE` 软依赖保证先于本插件加载 | 同一 AntiGriefLib（1.0.16）适配器 + fail-closed；两份清单均恢复全部软依赖声明，否则后加载的领地插件不会被识别 | 已对齐 | `paper-plugin.yml` / `plugin.yml` 软依赖清单与 `ProtectionService` |
| 命令体验 | 创建、加入、观战、房主转让、机器人、规则、排行、管理命令 | 命令只调用 application use case；房主转让和旧规则别名已保留，不绕过 actor | 主链路已对齐；排行入口另见排名行 | lobby reducer、异步命令边界、locale/bundle 门禁 |
| 多语言提示 | 完整中文/英文/日文消息键 | 命令、动作与交互反馈使用客户端翻译；内置简中、繁中、英文、日文，控制台英文回退 | 已实现核心链路 | 构建期 locale 键集与占位符完整性门禁 |
| 排名与历史 | 对局结果、排行与个人查询 | `/mahjong history [page]` 与 `/mahjong rank [rule] [page]` 走只读异步 SQL projection；不得在 region/actor 线程同步查询 | 已实现玩家入口 | `JdbcPlayerRecordQueryTest`、SQL schema/event-store 测试、locale 键集门禁 |
| 段位阶梯 | 六段位 × 3 级、升降段、魂天 SP、平均顺位与一位率 | 已完整移植并接线：`domain/match/RankLadder` 规则、`application/history/RankProgression` 策略、`RankProgressionPort` 端口、`player_rank_summary` 扩列建索引、`ranking.*` 配置、`/mahjong rank` 展示段位与顺位率 | 已对齐 | `RankLadderTest` 12 项、`RankProgressionTest` 6 项、`RankLadderPersistenceTest` 5 项（含重放不二次升段、排行按阶梯排序） |

## 段位阶梯实现口径

**权威归属**：段位阶梯是三种玩法共用的**通用**进度系统，归主插件；规则包只报规则特有的 `placement` 与 `score`。三个包的 `rankingPointsMilli` 全部等于 `score * 1000`，不携带任何阶梯信息，因此**不参与段位推进**（保留在 SPI 与 canonical payload 中，因为它参与哈希，但不再作为排行依据）。

分层与线程模型：

- **规则**（`mahjong-domain`）：`RankTier`/`RankMatchLength`/`RankRoom`/`RankStage`/`RankProfile`/`RankLadder`。纯不可变值 + 静态方法。
- **策略**（`mahjong-application`）：`RankProgression` 负责全桌视图——「四人皆魂天」判定与强场加成都需要同时看四家档案，不能逐人算。`RankProgressionPort` 是函数式端口，`NONE` 表示不启用段位（仍记分数与局数）。
- **配置**（`mahjong-plugin`）：`ConfiguredRankProgression` 按 `ranking.enabled` / `ranking.east-room`（默认 SILVER）/ `ranking.south-room`（默认 GOLD）解析房间档位，并从 profile 名判定东/南场。未知房间名让配置加载失败，而不是静默按 SILVER 计算。
- **落库**（`mahjong-persistence-sql`）：在**已有的终局 JDBC 事务内**完成——先用一次索引查询批量读四家档案，调端口，再写回。不新开线程、不新开事务，比原来只多一次读。沿用原有的「已存在 ledger 行则跳过」幂等机制，因此重放终局结果不会二次升段（有测试）。

两个易错点已用测试固定：

1. **tier 必须同时存名字与序号**。只按名字排序会把「雅士 ADEPT」排到「魂天 CELESTIAL」之前。排行走 `idx_rank_summary_ladder`（`tier_ordinal DESC, tier_level DESC, stage_points DESC, total_score DESC, player_id`），个人名次的 `COUNT(*)` 用同一套键，两处必须一致。
2. **排行按阶梯排序，不按累计分**。同一段位内才用 `total_score` 做平手判定——所以一个九百万分的初心仍排在雅士之后，但会赢过另一个初心。

已移植的 1.5.0 权威阶梯表，供核对：

```
NOVICE 1/2/3   起始   0 /  80 /  200   升段   20 /  80 /  200
ADEPT  1/2/3   起始 300 / 400 /  500   升段  600 / 800 / 1000
EXPERT 1/2/3   起始 600 / 700 / 1000   升段 1200 / 1400 / 2000
MASTER 1/2/3   起始1400 /1600 / 1800   升段 2800 / 3200 / 3600
SAINT  1/2/3   起始2000 /3000 / 4500   升段 4000 / 6000 / 9000
CELESTIAL      起始分 100，升级阈值 200（超出部分结转）
```

注意：初心 2/3 级的起始分与升段分相同（80/80、200/200），因此任何一次升段都会从初心 1 直接连跳到雅士 1——这是 1.5.0 阶梯表的既有行为，已由测试固定，不是缺陷。

“骰子与开门已实现”采用唯一口径：规则包已给出确定性骰点、开门座位、断墙位置和摸牌顺序，核心已编排阶段与声音，CraftEngine 配置已提供稳定骰子槽位、客户端滚动纹理和点数 variant。规则包不保存实体模型与动画几何是明确的 CE 职责边界，不得再被写成“骰子开门未实现”。

## 明确修正的旧行为

- MCR 计番以 Green Book/EMA 补充规程和逐番金标为准；旧 native 差异只作审计样本。
- 四川补牌方向、即时雨、过胡、查叫/退杠、呼叫转移和物理牌合法性以 T/TFMJ 01—2024 为准。
- 四川牌墙按标准修正为东/西 13 墩、南/北 14 墩；MCR 显示规范要求的第二次掷骰，不复刻旧版隐藏第二掷的错误。
- 日麻的振听、抢杠、杠宝、包牌、终局和赤牌物理供给由规则包验证，核心不做猜测。
- 陈旧 action token、重复点击、第五张同牌、越权私有视图和伪造结算必须 fail closed。
- 不恢复 Paper Display Entity 桌面、全桌逐 tick 扫描、阻塞 SQL、每桌线程、JNI 或旧 Kotlin 生产规则。
- 日麻反应窗口在玩家已应答后不得再列出该玩家的动作；已立直的手牌只允许摸切。两者原先都会给出被引擎拒绝的「幽灵按钮」。
- 副露顺子的基牌取最小张，不取鸣牌存储顺序的首张；后者在吃顺子最小张时会让计分抛异常。

## 兼容代码清除口径

2.0 不保留任何面向旧契约的兼容分支：SPI 只接受 `SpiVersion.CURRENT`，激活状态文件只接受当前字段集，注册表只接受当前格式，动作标签只解析当前键格式，CI 只放行当前 SPI 版本。旧版本号字符串不得残留在生产代码、测试夹具或工作流中。

以下不属于「旧兼容代码」，刻意保留：MCR `Tile.code()` / `Tile.parse(String)` 是 Green Book 文本记号（全部 81 番金标测试依赖它）；`CraftEngineVersion` 是现役 26.7+ 版本门禁；各处 `1.5.0-aligned` 文档注释用于记录对齐目标。

## 完成定义

“主玩法、手感与旧版对齐”只有在上表所有非“明确修正”项目完成、三个规则包 TCK 与金标通过，并且核心 GitHub/Linux 构建及测试通过后才能声明。并发吞吐和压力门槛属于独立的发布性能验收，不改变本矩阵的功能一致性结论。
