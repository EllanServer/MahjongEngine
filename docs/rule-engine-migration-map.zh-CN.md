# 麻将规则引擎迁移映射

本文记录 MahjongPaper 主仓与三套候选 Java 规则制品之间的真实集成边界。审计快照日期为
2026-08-08；它描述的是当前工作树，而不是发布承诺。

结论先行：三套规则仓目前均为 `0.1.0-SNAPSHOT`，主仓的构建文件和生产源码尚未引用
`top.ellan.mahjong.rules.*`。因此当前没有任何玩法已经切换到新引擎，也没有任何旧规则实现可以
立即删除。国标和四川的纯计算 API 已适合进入影子比对；日麻可影子比对牌形与计分，但完整牌局
切换仍有明确的功能阻断项。

本文中的状态含义如下：

- **未接入**：候选 API 已存在，但主仓没有制品依赖或适配器。
- **可影子比对**：可以在不影响线上决策和分数的前提下并跑，不能成为权威结果。
- **阻断切换**：现有主仓会走到新引擎明确不支持的路径，或仍缺少必要的边界映射。
- **可切换**：只有本文件的门槛全部通过后才能标记；当前没有这一状态。

相关的拆仓范围和规则基线见 [规则引擎拆仓说明](rule-engine-repositories.zh-CN.md)。

## 目标集成形态

主仓的命令、Paper/Folia 调度、计时器、机器人、渲染、数据库和本地化继续留在 MahjongPaper；
规则制品只接收类型化输入并返回不可变结果或事件。`TableRoundController` 可以继续作为主仓面向
上层的门面，但其实现应成为窄适配器，不能再次复制和牌、番种、反应优先级或结算算法。

```text
Paper / Session / UI / Bot
            |
   主仓玩法适配器（UUID、显示顺序、调度、DTO）
            |
   已发布且锁定版本的 Java 规则 JAR
            |
     类型化结果 / 事件 / 违规原因
```

适配器提交命令时必须先构造完整规则输入；只有规则调用成功后才能更新主仓的展示投影、播放动画
或写入分数。引擎失败时不得把“计算异常”伪装成“不能和”，也不得在一次动作已经部分生效后自动
回退到旧引擎。

## 公共集成面

| 旧入口或类型 | 新仓对应面 | 主仓适配责任 | 当前是否可切换 |
| --- | --- | --- | --- |
| `MahjongTableSession#createRoundController` | 日麻 `RiichiRound`；四川 `RoundEngine`/`RoundState`；国标 `McrRulesEngine` | 按玩法创建适配器和规则对象；保留 UUID、显示名、机器人标识及桌级生命周期 | 否；当前仍直接创建 `RiichiRoundEngine` 或 `GbTableRoundController` |
| `TableRoundController` | 三仓没有共同父接口，也不应依赖主仓接口 | 保留为 Paper 侧门面；把布尔返回值转换成类型化违规，并把规则事件投影成现有查询接口 | 否；尚无任何适配实现 |
| `MahjongTile` | 日麻 `Tile`/`TileKind`/`TileInstance`；国标 `Tile`/`TileCounts`；四川 `Tile`/`HandCounts` | 建立逐项、无默认分支的转换器；保留赤牌和物理牌 ID 语义；对各玩法不允许的牌关闭失败 | 否；三个牌枚举的合法集合不同 |
| `SeatWind` 与玩家 `UUID` | 日麻 `PlayerId`/`Wind`；国标 `Wind`；四川 `Seat` | 冻结一局内的座位映射；国标继续区分物理座位与随庄家旋转的逻辑风位 | 否；尚无映射 TCK |
| `MahjongRule` | 日麻 `RiichiRules`；国标和四川为固定规则档案 | 把时限、旁观、局长、房间配置留在主仓；只把规则字段传入制品；迁移旧 `TOURNAMENT` 为 `EARLY_KAN_DORA` | 否；旧类型仍由会话、UI 和控制器公开 |
| `ReactionOptions`、`ReactionResponse` | 日麻和四川各自的 `ReactionOptions`/`Reaction` 或 `ReactionWindow`/`ReactionChoice` | 命令参数必须映射到确切物理牌或牌种；不跨玩法复用日麻 Kotlin DTO | 否；主仓公共接口仍泄漏旧日麻类型 |
| `RoundResolution`、`YakuSettlement`、`ScoreSettlement`、`SettlementPayment` | 日麻 `ScoreResult`/`AggregatedSettlement`；国标 `WinEvaluation`/`Payment`；四川 `WinResult`/`ScoreDelta`/`RoundEvent` | 生成平台中立的展示 DTO，再交给本地化和 UI；校验每次分差零和、无溢出、无重复账户行 | 否；现有展示层直接消费旧 Kotlin DTO |
| `MeldView`、手牌/牌河/牌墙查询 | 各仓的 `Meld`、快照或计数类型 | 规则状态与渲染状态分层；隐藏信息按查看者授权；排序、横置和盖牌仅由主仓投影 | 否；不能让渲染反向成为规则事实源 |
| `SessionActionDeadlineCoordinator`、各 `BotStrategy` | 三仓均不负责平台超时或机器人策略 | 超时只提交一个合法的类型化命令；机器人读取公开分析结果，不直接改规则状态 | 保留主仓，不属于删除目标 |

## 日麻迁移映射

候选制品坐标为 `top.ellan.mahjong.rules:riichi-mahjong-java:0.1.0-SNAPSHOT`。牌形、向听和
物理听牌已经是纯 Java 实现，并用 12,000 手牌对固定旧后端做确定性差分；计分仍依赖固定的
`mahjong-utils-jvm:0.7.7`，`MahjongUtilsBridge` 只通过反射调用其和牌计分入口。它隔离了 Kotlin
类型，却尚未形成“纯 Java、无反射”的完整运行时。

| 旧类或职责 | 新仓类型或职责 | 主仓适配责任 | 当前是否可切换 |
| --- | --- | --- | --- |
| `RiichiTableRoundController` | `RiichiRound`、`RoundCommand`、`CommandResult`、`RoundSnapshot`、`RoundEvent` | 把 UUID 和手牌索引映射为稳定的 `PlayerId`/`TileId`；仅在命令接受后更新展示；实现私有手牌授权 | 阻断；没有主仓适配器，快照查询也不是一一对应 |
| `RiichiRoundEngine` 的单局动作、振听、一发、杠、流局和多家荣和 | `RiichiRound`、`FuritenState`、`KanTracker`、`AbortiveDrawRules`、`ReactionWindow` | 用确定性墙、岭上牌和指示牌序列构造 `Scenario`；把事件顺序接到动画、计时器和托管 | 阻断；补杠、立直后暗杠保持听牌、国士无双抢暗杠仍关闭失败 |
| `RiichiRoundEngine` 的开局、局次推进、延长局与比赛结束 | `Scenario` 只从摸牌或弃牌边界启动；`RiichiRules` 保存部分点数规则 | 主仓继续负责掷骰、发牌、场风/局数、连庄、延长局、终局与桌级重建，或先扩展新仓的受测 API | 阻断；新仓明确拒绝比赛长度延长和最终排名策略 |
| `CoreModels.kt` 中 `MahjongTile`、`TileInstance`、`Fuuro`、`MeldType`、`Wind` | `TileKind`、`Tile`、`TileId`、`TileInstance`、`Meld`、`MeldType`、`Wind` | 保证赤五数量、同种牌与物理牌 ID 双重语义；花牌、未知牌和第五张牌必须拒绝 | 类型可映射，尚未接线 |
| `MahjongRule`、`MahjongRound` | `RiichiRules` | 转换起始/目标点、最低役番、赤牌、食断、和了头跳；思考时间、旁观和局长留在主仓 | 部分；新仓不承接完整比赛流程 |
| `RiichiPlayerAnalysisState`、`RiichiPlayerScoringState`、`RiichiShantenRuntime` | 原生 Java `HandEvaluator`/`HandAnalysis`；后端适配的 `ScoreCalculator`/`ScoreRequest`/`ScoreResult`；`RiichiServices` | 建立牌形/计分影子端口，记录 `EvaluationException`；不能将异常降级为无役 | 向听/听牌已具备独立实现和差分语料；计分可影子比对，均尚未替代线上结果 |
| `RiichiPlayerState#discardSuggestions` 与 `RiichiDiscardSuggestion` | 仅有 `HandAnalysis(shanten, waits)`，没有等价的进张、良形和改良枚举 | 主仓暂留旧建议器，或在新仓新增正式 API 和性能回归后再迁移；机器人和 HUD 不能丢字段 | 阻断 |
| 旧 `ReactionType`、`ReactionOptions`、`ReactionResponse`、`PendingReaction`、`ReactionResponses` | `ReactionType`、`ReactionOptions`、`Reaction`、`ReactionWindow`、`ReactionResolution` | 由手牌索引解析两张/三张确切 `TileId`；重复响应映射为业务错误，不允许覆盖先前响应 | 类型可映射，尚未接线 |
| `GeneralSituation`、`PersonalSituation` 和 `YakuSettlement` | `ScoreRequest`、`YakuAward`、`ScoreResult` | 显式映射海底/河底、岭上、抢杠、一发、双立直、宝牌和里宝牌；将 yaku ID 本地化 | 可影子比对；展示契约未迁移 |
| `RiichiPaoRules` 与旧支付明细 | `PaoPaymentCalculator`、`SettlementAggregator`、`PaymentTransfer`、`AggregatedSettlement` | 主仓或新仓先从副露事件登记大三元/大四喜责任者；最后一次性聚合所有荣和、本场和供托 | 阻断；`RiichiRound` 尚不自动登记包牌责任 |
| `OpeningDiceRoll`、`MahjongSoulScoring`、`ScoreItem`/`RankedScoreItem` | 无等价物 | 继续留在主仓，它们属于开局展示和整场排名；不要塞回规则核心 | 保留主仓 |
| 主仓直接使用 `mahjong-utils` 的 Kotlin 类及反射 | 新仓原生 Java `HandEvaluator` 不加载后端；`MahjongUtilsBridge` 仅隔离计分并校验 JAR SHA-256 | 对齐编译期和 Paper 运行时解析版本；基准分别覆盖原生向听与反射计分；若目标是零 Kotlin 运行时，仍需后续原生 Java 计分器 | 仅计分仍阻断“纯 Java 运行时”目标；不阻断影子验证 |

日麻完整切换前必须补齐：补杠及抢杠窗口、立直后暗杠的听牌保持、国士无双抢暗杠、包牌责任
自动登记、整场推进契约，以及现有机器人/HUD 所需的弃牌建议指标。把这些路径留在旧引擎、其余动作改走
新状态机，会制造两个可变规则事实源，不接受这种长期混合方案。

## 国标 MCR 迁移映射

候选制品坐标为 `top.ellan.mahjong.rules:mahjong-mcr-java:0.1.0-SNAPSHOT`。它是无 JNI、无 JSON、
无运行时第三方依赖的牌形、81 番种、听牌和支付库，不包含发牌、补花、反应收集或整局状态机。
其 81 个检测分支已有迁移/差分覆盖，但 `RULE_COVERAGE.md` 明确把逐番独立黄金样例认证列为 1.0
发布阻断项，因此不能把“已实现”写成“已独立认证”。

| 旧类或职责 | 新仓类型或职责 | 主仓适配责任 | 当前是否可切换 |
| --- | --- | --- | --- |
| `GbTableRoundController` 的 GB 分支 | 新仓只提供 `McrRulesEngine`，没有整局控制器 | 保留发牌、补花、轮转、截和/鸣牌和 16 手赛程；把计番、听牌和支付调用改到窄端口 | 否；只能先替换纯计算调用 |
| `GbTileEncoding` | `Tile`、`TileCounts`、`Wind` | 对 `MahjongTile` 做穷尽映射；赤五、未知牌拒绝；花牌只进入 `WinContext.flowers`，不能混入暗手 | 类型可映射，尚未接线 |
| `GbMeldState`、`GbMeldType` | `Meld`、`MeldType` | 校验吃、碰、明/暗杠的牌数、来源和开放状态；保持“杠算一个结构组”的暗手张数约定 | 类型可映射，尚未接线 |
| `GbFanRequest`/`GbFanResponse`、`GbFanEntry` | `WinInput`、`WinContext`、`WinEvaluation`、`FanAward` | 将字符串 `winType`/`flags` 改为 `WinMethod`/`WinFlag`；分别处理 `winningShape`、`legalWin`、`qualifyingFan`、`flowerFan` | 可影子比对；旧 DTO 仍被控制器消费 |
| `GbTingRequest`/`GbTingResponse`/`GbTingCandidate` | `WaitInput`、`WaitEvaluation`、`WaitCandidate` | 保留弃和与自摸两种合法性，不能再把一个可空总番值当成全部语义；缓存键使用完整不可变输入 | 可影子比对；会话和机器人 API 未迁移 |
| `TableSessionContext#gbTingOptions`、`MahjongTableSession#gbTingOptions` | 不应继续公开 JNI 命名 DTO；内部读取 `WaitEvaluation` | 新增主仓自己的只读听牌展示模型，供 HUD 和机器人消费；不能把新仓类型扩散到 Paper 公共会话接口 | 否；当前接口签名直接依赖 `GbTingResponse` |
| `GbWinRequest`/`GbWinResponse`、`GbSeatPointsInput`、`GbScoreDelta` | `WinEvaluation` 加 `McrPayments.settle`/`Payment` | 由当前逻辑风位映射支付到物理 UUID；在主仓原子应用零和分差；当前点数不是计番输入 | 可影子比对；展示/应用路径未迁移 |
| `GbNativeRequestFactory` 及控制器内重复的 request builder | 上述类型化构造器 | 合并为一个主仓 MCR 转换器；明确暗手是否已含和牌张，禁止字符串和 JSON 穿越热路径 | 否；新转换器尚不存在 |
| `GbNativeRulesGateway` 的三个 Caffeine/FNV64 缓存 | 无内建缓存的 `StandardMcrRulesEngine` | 初期不缓存；需要时使用完整 `WinInput`/`WaitInput` 相等键，或哈希加原值校验，不能只相信 64 位摘要 | 可影子比对；线上仍调用 native gateway |
| `GbMahjongNativeModels.kt` 与 `GbMahjongNativeJson` | 全部由 Java 值类型替代 | 差分期保留为旧引擎端口；切换后先消除所有主仓引用再删除 | 否；当前控制器、会话、机器人仍公开这些 DTO |
| `GbMahjongNativeBridge`、`GbMahjongNativeLibrary`、`GbNativeWarmupService`、JNI C++ 和打包任务 | 新仓没有本地库加载 | 在影子期把 native 固定为只读对照；正式切换后移除启动预热和平台 native 资源 | 否；当前插件启动仍执行 native warmup |
| `GbReactionResolver` | 新仓不覆盖反应窗口 | 继续留在主仓，或以后独立抽出 MCR 单局状态机；荣和判定回调改收 `WinEvaluation` | 保留主仓 |
| `GbBotDecisionService` | 使用 `WaitEvaluation` 可提供合法听牌和番值 | 机器人策略留在主仓；改写 ready score 读取逻辑并为自摸/点和差异增加回归 | 否；当前依赖 `GbTingResponse` |
| `GbRuleProfile.GB` | 固定 MCR 规则对象，无字符串 profile | `MahjongVariant.GB` 仍属主仓；删除 `nativeRuleProfile` 只在四川也脱离共享控制器之后进行 | 否 |

MCR 差分必须把旧 JNI 当作迁移 oracle，而不是裁决者。已知“一个暗杠加一个明杠”旧私有
`MINGANGANG=5` 与新实现按 Green Book 记 6 分的差异，应作为有来源的允许差异保留；任何其他差异
都要先归档输入、双方结构化输出和规范条款，再决定修哪一侧。

## 四川麻将迁移映射

候选制品坐标为 `top.ellan.mahjong.rules:sichuan-mahjong-java:0.1.0-SNAPSHOT`。它无运行时依赖，
包含严格 27 种数牌、和牌/听牌、番型、杠分、查叫、退杠、呼叫转移、过胡及血战单局状态机。
它接收已经发好的四手牌和按列表首位为下一张的剩余牌墙，不负责掷骰、开门、发牌动画或赛事罚则。

| 旧类或职责 | 新仓类型或职责 | 主仓适配责任 | 当前是否可切换 |
| --- | --- | --- | --- |
| `GbRuleProfile.SICHUAN` 与 `GbTableRoundController` 中的四川分支 | `RoundState`、`RoundEngine`、`RoundAction`、`Transition`、`RoundEvent` | 新建独立四川控制器适配器，不再通过 `visitSichuan(GbTableRoundController)`；UUID 固定映射为 `Seat` | 阻断；当前四川仍复用国标控制器 |
| `SichuanHuEvaluator` | `HandAnalyzer`、`ShapeAnalysis` | 删除主仓重复递归前先用同一手牌语料比对；新 API 对红五、字牌、花牌和第五张牌关闭失败 | 可影子比对 |
| `DefaultSichuanRulesEngine#evaluateFan/waitingTiles` | `SichuanRules`、`WinInput`/`WinResult`、`ReadyInput`/`WaitResult`、`FanKind` | 映射定缺、和牌来源和加番事实；不再经 `GbFanEntry`/`GbTingCandidate` | 可影子比对；线上 DTO 尚未替换 |
| `DefaultSichuanRulesEngine` 的胡牌、杠分和荒牌分差 | `SettlementCalculator`、`ScoreDelta`、`KongReceipt`、`ReadyScore` | 将座位分差一次性映射到 UUID 并校验零和；持久化前冻结结算快照 | 可影子比对；线上仍用 `GbScoreDelta` |
| `GbMeldState`、`GbMeldType` | `Meld`、`MeldType` | 严格拒绝吃和错误来源；区分暗杠、直杠、补杠、即时雨 | 类型可映射，尚未接线 |
| `SichuanPreparationFlow`、`SichuanSuit`、`chosenMissingSuits` | `DingQueState`、`Suit`、`RoundAction.DeclareMissing` | UI/超时托管提交显式定缺命令；庄家首打隐式定缺和天缺需另有规范化边界，不能猜测 | 阻断；新仓对这两条明确关闭失败 |
| `SichuanPreparationFlow` 的换三张分支 | 新严格档案无对应 API | 默认 T/TFMJ 档案不得触发；若未来支持，另建有名称的房规 profile，不能在适配器私自交换 | 不切换该分支；主仓 UI/超时接口仍有引用 |
| `SichuanSessionAccess`、`TableSessionContext` 的定缺/换牌查询及 `PlayerActionSnapshotFactory` | `RoundPhase`、`DingQueState` 和合法的 `RoundAction` | 会话层从新快照生成平台动作，不让 UI 自己判断规则；严格档案不展示换三张动作 | 否；现有调用仍面向 `GbTableRoundController` |
| 共享的 `GbReactionResolver` | `ReactionWindow`、`ReactionChoice` 和 `RoundEngine` 内置一炮多响/血战退出 | 把玩家响应映射为新命令；等待全部响应后只应用一次 `Transition`；事件驱动后续动画 | 阻断；当前仍用日麻反应 DTO 和 GB resolver |
| `sichuanPassedWinUnits` | `PassedWinState` | 从引擎状态读取，不在主仓维护第二份番值门槛；摸牌事件自动清除本人过胡 | 类型已有，尚未接线 |
| `sichuanGangEvents`、`pendingSichuanCallTransferEvents` | `KongReceipt`、`transferableKongs`、`SettlementCalculator` | 不重复记账；把 `RoundEvent.ScoreMoved` 作为唯一分差来源 | 类型已有，尚未接线 |
| `drawReplacementTile(... removeLast())` | `TileWall.drawFront()`，普通摸和杠后补牌统一从前端 | 主仓构造牌墙时必须约定列表首位为下一张；渲染墙方向只做坐标换算，不改变规则顺序 | 这是有规范依据的预期差异，不能要求与旧实现相等 |
| 旧版“补杠第 4 张必须是本次摸入”且无即时雨 | `RoundAction.AddedKong` 保留该限制；`Meld.instantRainPung`/`RoundAction.InstantRain` 增加即时雨 | 响应碰时保留可即时雨事实；后来转杠时提交独立命令，杠分为 0、仍计根并顺杠补牌 | 这是预期新增规则路径，需主仓交互回归 |
| `hands` 中有顺序的 `List<MahjongTile>` | `PlayerState` 使用 `HandCounts`，没有物理牌顺序 | 主仓维护只用于展示的有序投影；以引擎计数为权威，每个接受事件后做计数一致性断言 | 阻断；点击索引到牌种及投影同步尚未实现 |
| `SichuanBotStrategy`、定缺和动作超时 | 新仓不提供机器人/计时器 | 继续留在主仓，只能从公开状态和合法动作生成策略；不得直接改 `RoundState` | 保留主仓 |

四川差分不能只追求旧新结果全等。下列旧行为应按规范固定为有解释的差异：杠后从墙前端顺杠、即时雨、
严格拒绝赤五/字牌/花牌，以及所有无法确认房规的关闭失败。对定缺开局，主仓必须先决定并测试标准中的
庄家首打隐式定缺和天缺如何进入状态机；在此之前只能使用当前新仓明确支持的“四家显式声明”流程。

## 尚不能删除的旧代码与依赖

| 旧代码或依赖 | 最早可删除时点 | 注意事项 |
| --- | --- | --- |
| `TableRoundController`、`MahjongTableSession`、调度、渲染、UI、机器人和持久化 | 不作为规则拆仓删除目标 | 只收窄依赖方向和 DTO，不把平台职责迁入规则仓 |
| `RiichiRoundEngine.kt`、`RiichiPlayer*State.kt`、`CoreModels.kt`、`RiichiPaoRules.kt` | 日麻完整动作和整场门槛通过，并且主仓无旧类型引用后 | 当前机器人、HUD、结算和控制器仍直接依赖；不能分批删除可变状态的一半 |
| 主仓直接 `mahjong-utils-jvm` 依赖 | 日麻旧实现移除后可从主仓源码依赖中删除 | 新日麻制品的计分器仍依赖同版本；向听已原生 Java，但运行时依赖尚未完全消失 |
| Kotlin Gradle/stdlib | 不能仅因规则源码迁移而删除 | 主仓测试和 `buildSrc` 仍有 Kotlin；新日麻后端也可能需要 Kotlin 运行时 |
| `GbMahjongNativeModels.kt`、JSON 序列化、native gateway/bridge/library/warmup | MCR 影子期结束、Java 结果成为权威且回滚观察窗结束后 | `kotlinx-serialization-json` 可在确认无其他使用者后移除；不要误删仍被日麻需要的 Kotlin 支持 |
| `native/gbmahjong`、native 构建与打包任务 | 所有受支持平台均完成 MCR Java 切换且无需差分回滚后 | 保留上游 MIT notice 和最终对照语料的来源记录 |
| `GbTableRoundController` | MCR 和四川分别有适配器后 | 四川切换不代表可删除 GB 控制器；应先拆出共享的平台流程 |
| `DefaultSichuanRulesEngine`、`SichuanHuEvaluator`、四川专用 controller 字段 | 四川新状态机成为权威且 replay/live 检查通过后 | 删除时同时清理 GB DTO 借用，避免留下双份过胡或杠分账本 |
| Caffeine | 不能随 `GbNativeRulesGateway` 一并删除 | 主仓本地化、桌管理、事件协调和调度仍有独立使用者 |

删除前必须以源码检索和依赖报告确认零引用；“新类名字已经存在”不是删除依据。

## 差分与正式切换门槛

每个玩法独立过门，不要求三者同日发布，但同一玩法必须按以下顺序完成：

1. **制品门槛**：独立仓发布非 `SNAPSHOT`、不可变版本；源码/Javadoc/JAR、许可证、依赖锁和校验和齐全；主仓编译期与运行期解析到同一版本。
2. **映射 TCK**：覆盖全部合法牌、风位/座位、赤牌、花牌、每种副露、和牌来源、规则 flag 和错误输入；往返映射不得静默归一化。
3. **规则语料**：官方/独立黄金例、近失例、互斥/不重复计分例和属性测试通过。MCR 额外要求 81 番逐项独立认证；日麻和四川要覆盖各自上文的阻断路径。
4. **确定性 replay**：从固定种子或记录下来的完整牌墙重放，逐动作比较合法动作、反应优先级、事件顺序、赢家、番/役、支付和终局状态。
5. **差异裁决**：未解释差异必须为零；允许差异必须记录规范来源、旧输出、新输出和固定回归，不能用“新实现看起来更合理”代替证据。
6. **故障语义**：无效输入、计算异常、溢出、重复响应和缺少补牌均关闭失败；影子异常只告警，权威引擎异常中止当前动作且不产生部分结算。
7. **性能门槛**：在相同 JDK、堆大小、预热和牌谱下比较吞吐及 p50/p95/p99；覆盖最坏分解、多家反应和全听牌枚举。日麻必须分别量化原生向听与反射计分的冷热路径和分配，MCR/Sichuan 不得退回 JSON/字符串热路径。
8. **主仓集成**：全部单元/集成测试、Paper/Folia 调度、断线托管、机器人、HUD/结算、渲染隐藏信息和 live-server 清单通过。
9. **观察窗**：按玩法灰度到 Java 权威模式，保留旧制品和指标至少一个发布观察窗；观察窗内不删除旧实现。

影子比较应使用规范化结构，而非展示字符串。例如番/役按稳定 ID 与次数排序，支付先聚合为每座位净额，
副露按牌种、开放性和来源比较。旧实现和新实现都不得在影子调用中修改牌局或重复写分。

## 发布制品接入

三个候选仓当前仅应用 `java-library`（四川另有 `application`）且没有 `maven-publish` 配置，所以目录中
即使存在本地 JAR，也不等于已经发布。正式接入应先在各自仓库配置 Maven 发布、签名和 CI，再发布到
主仓与 Paper 运行环境都能访问的 Maven 仓库。建议保持当前坐标并使用独立语义版本：

```text
top.ellan.mahjong.rules:riichi-mahjong-java:<riichiVersion>
top.ellan.mahjong.rules:mahjong-mcr-java:<mcrVersion>
top.ellan.mahjong.rules:sichuan-mahjong-java:<sichuanVersion>
```

主仓应使用固定 release 版本，例如：

```kotlin
dependencies {
    implementation("top.ellan.mahjong.rules:riichi-mahjong-java:$riichiRulesVersion")
    implementation("top.ellan.mahjong.rules:mahjong-mcr-java:$mcrRulesVersion")
    implementation("top.ellan.mahjong.rules:sichuan-mahjong-java:$sichuanRulesVersion")
}
```

如果保持 Paper 的 `MavenLibraryResolver` 运行时下载方式，`MahjongPaperLoader` 必须添加同一仓库和完全相同
的 release 坐标，并验证传递依赖收敛；若选择把规则 JAR 打入插件，则构建和发布测试必须验证打包内容及
类加载隔离。两种方式只能选定一种生产策略，不能让编译期使用一个版本、运行时下载另一个版本。

以下方式禁止用于生产接入：

- `implementation(files("../rule-repositories/...jar"))`；
- 把 `../rule-repositories/*/src/main/java` 加入主仓 source set；
- 用 Gradle `includeBuild` 或 Git submodule 的相对源码路径充当发布流程；
- 动态版本、`SNAPSHOT`、未校验的 latest 标签或运行时静默降级。

主仓应启用依赖锁/校验，记录三套制品版本、JAR SHA-256 和规则基线版本。规则仓升级必须通过普通依赖
PR 完成，使单个玩法可以独立升级或回退。

## 灰度与回滚策略

建议在主仓为每个玩法提供独立的启动期模式：`LEGACY`、`SHADOW`、`JAVA`。该名称是迁移建议，
当前代码尚未实现。

- `LEGACY`：只运行旧引擎，是拆分期间的兼容基线。
- `SHADOW`：旧引擎仍是唯一权威；新引擎读取同一不可变输入，只记录结构化差异和耗时，不发送事件、
  不更新分数、不写数据库。
- `JAVA`：新引擎是唯一权威；旧引擎只在明确开启的离线诊断中运行，不能作为单动作自动 fallback。

模式只在插件启动或新桌/新局边界读取。不得在一手牌中途从新引擎切回旧引擎，因为两者的反应窗口、
振听/过胡、杠收入和隐藏牌状态并非可互换；中途 fallback 会产生重复扣分或不同赢家。出现故障时应拒绝
当前动作、保存结构化诊断并冻结该桌，待该手安全结束或由管理员按现有强制结束流程处理。

回滚发布时，先把目标玩法在下一局边界切回 `LEGACY`，再把主仓依赖降回已验证的前一版规则制品并重启。
若已经写入新引擎产生的结算，不得通过重新跑旧引擎覆盖；只能把已提交的零和分差视为账本事实，另走审计
和人工补偿流程。旧实现、旧 native 资源、差分语料和前一版制品至少保留到观察窗结束，之后再按上表逐项
删除。
