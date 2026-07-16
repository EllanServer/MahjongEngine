# 麻将规则验证矩阵

本文固定当前仓库实际承诺的玩法边界，避免把“雀魂风格”“赛事”“四川血战”等名称误解为另一个完整规则集。实现、文档和回归测试必须同时满足这里的定义。

## 基线来源

| 玩法 | 主要基线 | 本仓库 profile |
| --- | --- | --- |
| 立直 | [雀魂官方四人规则](https://mahjongsoul.com/news/46)、[官方 FAQ](https://mahjongsoul.com/faq) | `MAJSOUL` 为默认规范；`EARLY_KAN_DORA` 只改杠宝时序。旧 `TOURNAMENT` 是存档兼容值，不是 WRC |
| 国标 | [EMA/WMO Green Book](https://mahjong-europe.org/portal/images/docs/mcr_EN.pdf)、[EMA 赛事规程](https://mahjong-europe.org/portal/images/docs/mcr_regulations.pdf) | `GB` 为 144 张、净分 0、固定 16 手、8 番门槛与 8 分底分；通用覆盖属于房规 |
| 四川 | [T/TFMJ 01—2024](https://www.ttbz.org.cn/StandardManage/Detail/112870/) | `SICHUAN` 对齐该四川竞赛标准；MIL 与天府龙地方赛只作独立差异对照 |

## 立直麻将

| 规则点 | 当前承诺 | 主要回归 |
| --- | --- | --- |
| 振听 | 自己曾打过任一当前待牌即舍牌振听；非立直见逃到下次摸牌；立直见逃持续整局 | `RiichiPlayerStateTest`、`RiichiRoundEngineTest` |
| 立直棒与本场 | 宣言时只扣一次；和牌不重复扣；普通流局和流满正确保留/累加 | `RiichiRoundEngineTest` |
| 赤宝与重复指示牌 | 赤五进入底层计分；相同宝牌指示牌可重复叠加 | `RiichiPlayerStateTest` |
| 一发与鸣牌 | 吃、碰、明杠、暗杠、加杠均中断一发 | `RiichiRoundEngineTest` |
| 抢加杠 | 抢杠判定完成前不修改碰；荣和只移除第 4 张，跳过后才升级成杠 | `RiichiRoundEngineTest` |
| 食替 | 吃后禁同牌和两面筋食替，碰后禁同牌；下一次合法弃牌后解除 | `RiichiPlayerStateTest` |
| 起手天和 | 庄家 14 张遍历所有唯一和牌候选，按实际最高得点择优 | `RiichiRoundEngineTest` |
| 杠宝时序 | `MAJSOUL`：暗杠立即翻，大明杠/加杠在下一次弃牌判定荣和前翻，岭上不吃该张；`EARLY_KAN_DORA`：杠成立后、岭上摸前翻 | `RiichiRoundEngineTest` |
| 岭上与海底 | 岭上不叠加海底，岭上后的弃牌不算河底；无补牌不能杠 | `RiichiRoundEngineTest` |
| 最低番数 | 二番缚等门槛只统计役，宝牌、赤宝、里宝不能凑门槛，但仍进入最终得分 | `RiichiRoundEngineTest` |
| 空听与第五张伪听 | 真实待牌只是全耗尽仍可立直/算听；暗手已有四张且唯一待牌需要第五张时不算听，也不能立直 | `RiichiPlayerStateTest`、`RiichiRoundEngineTest` |
| 多家荣与供托 | 多家荣全部结算；只有距放铳者最近的荣家取得本场与供托 | `RiichiRoundEngineTest` |
| 四杠散了 | 仅多名玩家合计四杠；第四杠后的弃牌先判荣和，无荣才流局；一人四杠不流局 | `RiichiRoundEngineTest` |
| 起始/返还/门槛 | 25000 起始、25000 返还、30000 一位必要点数；同点按起亲座次 | `MahjongSoulScoringTest`、`RiichiRoundEngineTest` |
| 延长局 | 基础末亲按第一名和 30000 判断连庄；进入延长后第一名达 30000 即结束，多家荣含亲例外优先连庄 | `RiichiRoundEngineTest` |

`localYaku` 数据字段仅为旧存档兼容保留。当前没有实现雀魂整组地方役，因此运行时固定为关闭，规则 GUI 不展示，命令也不能开启。

## 国标麻将

| 规则点 | 当前承诺 | 主要回归 |
| --- | --- | --- |
| 牌组与牌墙 | 144 张全部进入同一活墙；每边 36 张；正常摸前端、补花/杠后从尾端；没有日麻王牌区 | `GbOfficialRulesRegressionTest`、`VariantWallRenderLayoutTest` |
| 掷骰与开门 | 两次各掷两枚六面骰；第一掷从庄家起按逆时针选开门家，第二掷参与开门墩数；四家庄与 2..12 点组合保持 controller/session/render 一致 | `GbOfficialRulesRegressionTest`、render/layout 测试 |
| 发牌与花牌 | 每人依次四张，共三轮；再发各一张，庄家跳牌；花牌可公开从尾端补牌，也可保留后弃出，不强制补花 | `GbOfficialRulesRegressionTest`、`GbTableRoundControllerTest` |
| 起始分与赛长 | 严格 `GB` 从净分 0 开始，固定东南西北四圈 16 手且每手轮庄；500 偏移、目标分或短局只属兼容房规 | `SessionRulePresetResolverTest`、`GbOfficialRulesRegressionTest` |
| 起胡门槛 | 至少 8 番；花牌番计入支付但不能满足 8 番门槛；仅自摸补足第 8 番的听牌仍应显示 | native fan/ting 测试 |
| 结算 | 自摸时三家各付“番数+8”；点炮者付“番数+8”，另两家各付 8；所有分差原子应用且总和为 0 | `GbOfficialRulesRegressionTest`、`GbTableRoundControllerTest` |
| 截和与鸣牌 | 单响；和牌优先，碰与明杠同级并由离放铳者最近者取得 | `GbReactionResolverTest` |
| 排行榜 | 国标结果页使用原始分且不显示“雀魂得点”；当前国标对局不套用雀魂段位公式，也不自动写入 rank/leaderboard 投影 | database/session/UI 测试 |
| 明暗杠与形式等待 | 一明杠一暗杠共 6 分；已耗尽的形式第二等待仍阻止边张/嵌张/单钓将误加分 | `GbMahjongRealWorldFanCoverageTest` |

## 四川 T/TFMJ profile

规范骨架：108 张序数牌、无字牌花牌、不能吃、直接定缺、多人荣和、胡后退出本副、其余玩家继续、杠后补牌、过胡、呼叫转移、荒牌未听退全部杠分，以及“首位赢家 / 首次多响放铳者 / 无人胡原庄”的下副庄家规则。

| 规则点 | 当前承诺 | 主要回归 |
| --- | --- | --- |
| 开局 | 发牌后直接定缺，无默认换三张；定缺牌必须优先打；一组两骰的和定方、较小点定幢 | `SichuanPreparationFlow`、`GbTableRoundControllerTest`、`SichuanRuleInvariantTest` |
| 计番与支付 | 平胡 0、大对子 1、清一色/七对 2；根等加番后总番封顶 3，基础单位最高 8；自摸每名付款者另加 1 | `SichuanRulesEngineTest`、`SichuanRealWorldFanCoverageTest` |
| 番种边界 | 不把将对或龙七对层级作为默认番；带根七对为 `QI_DUI + GEN`；末张统一 `HAI_DI` | `SichuanRealWorldFanCoverageTest` |
| 过胡 | 放弃胡或选择碰/杠后，同番及更低番在下次摸牌前禁止；严格更高番允许，0番与1番不可归为同档 | `GbTableRoundControllerTest` |
| 杠与抢杠 | 暗杠、明杠、加杠都必须有补牌；加杠第 4 张必须是本次摸入；抢杠只移第 4 张并保留原碰 | `GbTableRoundControllerTest` |
| 呼叫转移 | 杠后点炮把该次及连续杠收入转给荣家，原付款人不退款；多响等额上取整、放铳者补差；无人荣的普通弃牌清链 | `GbTableRoundControllerTest` |
| 荒牌 | 按结算瞬间重算听牌；天然未打完定缺按未听查叫，无固定 16/48 花猪转移；未听退本副杠收入并向听牌者付最高理论单位（最高 8） | `GbTableRoundControllerTest`、`SichuanRulesEngineTest` |
| 牌墙末段 | 默认没有末四强制胡或禁碰杠；可跳过和牌，托管不强制胡 | `SessionActionDeadlineCoordinatorTest`、`GbTableRoundControllerTest` |
| 赛长 | 房间默认 8 副、净分 0；完整 8 局 × 8 副赛事与计时层未实现，通用覆盖属于房规 | `SessionRulePresetResolverTest`、`GbTableRoundControllerTest` |

MIL 的杠上炮退款与固定外部花猪罚、“天府龙牌”的末四强制和均属其他认可 profile；这些差异不能用模糊的 `SICHUAN_TOURNAMENT` 名称伪装，因此该别名不被接受。

## 公共状态机与运行边界

- 行动时间为“每次动作 base + 每名玩家每手共享 extra”；只扣超过 base 的部分，每手重置。
- 普通超时选择安全弃牌，反应默认跳过；四川定缺使用确定性合法托管动作，不在末四张强制代胡。
- 中局断线保留座位 UUID 并立即进入托管，重连解除；反应未最终裁决前不提前广播碰、杠、胡。
- 每次分差应用校验座位合法、整数不溢出且总和为 0；结算快照在异步写库前冻结，连续两局相同结算不会被错误去重。

## 验证边界

自动化测试验证纯规则、控制器、数据库、渲染布局和发布构建。国标原生库是实现依赖，最终裁决仍以 Green Book 为准；Paper/Folia、CraftEngine、真实客户端镜头和数据库断线恢复仍需要 live server 清单做最终验收。
