# MahjongPaper 2.0 架构

## 唯一生产路径

2.0 没有 legacy/shadow/fallback 分支。一次比赛必须在创建时绑定：

- 规则 ID、规则包版本和 JAR SHA-256；
- profile 与配置 SHA-256；
- 状态 schema 版本；
- 固定 128-bit 随机种子；
- 四名参与者、座位与桌面世界锚点。

规则包缺失、不兼容或计算异常只会把对应比赛标为 `BLOCKED_RULE_PACK`，不会切换到另一套规则实现。

## 模块边界

核心的 `domain/application/rule-spi/presentation/rule-runtime` 不允许导入 Bukkit、CraftEngine 或 JDBC。平台和基础设施模块实现 application 端口，`mahjong-plugin` 是唯一装配根。

`mahjong-domain` 按 `lobby / match / table` 分类：开局前座位与准备状态、比赛版本身份、活动桌生命周期互不混放。领域对象仍只依赖 Java 与规则 SPI 的公共 ID，不导入平台或持久化实现。

`mahjong-application` 内部继续按职责分包，不允许把类放回 application 根包：

```text
application/
  concurrent/          有界规则池、单次任务调度
  interaction/         O(1) 路由、选牌与俯视视角端口
  persistence/         事件、快照和每桌 outbox
  projection/          平台无关桌面投影端口和值对象
  security/            revision-bound 动作令牌签发
  table/
    actor/              单写者循环及拆开的计算/授权/落库组件
  lobby/
    actor|command|port|projection|runtime|usecase
```

`TableActor` 只是有界调度外壳；`TableActorStateMachine` 独立拥有单写者比赛状态、落库提交和投影生命周期，`TableRuleTaskLauncher` 只启动公平规则池任务，`TableScheduledActionController` 只持有当前 revision 的一个单次任务。`TableActorInbox` 独立拥有有界玩家动作队列，以及规则完成、持久化健康、初始化和关闭的保留信号槽。玩家把动作队列塞满也不能阻断内部 continuation；重复规则完成会单桌 fail-closed。`InteractionRouter` 同样只保留分派门面，route 索引、暗手二击确认和俯视模式分别由独立组件维护。application 生产类由 CI 强制限制在 400 行以内。

`MahjongRuntime` 只负责生命周期与用例委派。SQL 初始化位于 `plugin/bootstrap/sql`，规则包初始化位于 `plugin/bootstrap/rules`，CraftEngine/Paper 装配位于 `plugin/platform`，恢复位于 `plugin/recovery`。架构检查会拒绝 application 根包类、超过责任上限的 application 类，以及重新塞回 `MahjongRuntime` 的 JDBC、HTTP 或 CraftEngine 具体初始化代码。

插件根包只允许保留 `MahjongPaperPlugin` 入口和 `MahjongRuntime` 装配根。配置位于 `plugin/config`，活动桌索引位于 `plugin/table`，比赛创建与恢复编排位于 `plugin/match`；其中规则初态创建、来源哈希和 actor 构造是独立组件，不再集中在一个比赛协调类中。

`mahjong-craftengine` 同样没有根包杂糅：`bundle` 只管理构建产物安装与 reload 门禁，`interaction` 只把 CE 交互转为平台中立输入，`port` 保存跨平台边界，`scene` 执行公开家具差分，`privateview` 只负责本人暗手、HUD 与相机。私有投影内部进一步把无锁目标/活动索引、region-thread 显示渲染、选牌状态和俯视相机拆成独立组件；网关只做端口及玩家生命周期转发，不再同时持有所有实现细节。公开场景后端也分为每桌目标状态、每 region 公平调度和单节点 mutation 执行器，故障与积压不会穿过该边界。CraftEngine 生产类由 CI 强制限制在 350 行以内。Paper/Folia 适配只能依赖 `port`，不能反向依赖 CE 的具体场景实现。

`mahjong-platform-paper` 只放 Paper/Folia 适配，并按 `anchor / concurrent / region` 分类：世界锚点、平台线程池和区域调度各自独立，不在平台根包堆积工具类。Paper 模块同时拥有窄化的锚点查询与 region 调度端口；`mahjong-craftengine` 可以依赖这些 Paper 端口，Paper 绝不能反向依赖 CraftEngine 实现。

`mahjong-persistence-sql` 按 `connection / schema / event / match / lobby / anchor / recovery / common` 分类。比赛身份行映射、初始恢复元数据和大厅消费是独立 SQL 组件；大厅变为比赛时仍共用一个 JDBC 事务，不以模块化为代价拆散原子性。

`mahjong-presentation` 只保存平台无关的桌面语义，并按 `asset / layout / node / port / projection / scene` 分类：资产名、通用桌面坐标编译、节点值对象、后端端口、规则视图映射和场景差分互不混放。通用布局内部再分成有界缓存、冷路径编译器和不可变热路径查表计划；场景映射再按 `publicview / privateview / interaction / asset / support` 分解，公开实体、私有 HUD/暗手和 revision-bound 命中区不会堆在同一个类中。表现层生产类由 CI 强制限制在 300 行以内。具体 CraftEngine 家具调用仍只存在于 `mahjong-craftengine`。

`mahjong-rule-runtime` 按 `activation / admin / catalog / common / install / lifecycle / loading / registry / security / storage` 分类，激活状态、管理命令、下载安装、类加载、签名注册表和本地文件边界彼此独立。`mahjong-rule-spi` 是三个外部规则仓共同编译的稳定公共协议，因此保持扁平且版本化，不把一次内部整理变成规则包 ABI 破坏。

规则包只通过父 classloader 提供的 SPI 通信。规则包是完整 JVM 受信代码；Ed25519 签名验证来源，不宣称提供 Java 沙箱。

## 动作链路

1. CraftEngine 交互实体只携带稳定 `InteractionHandle`。
2. `InteractionRouter` 以 `(handle, player)` O(1) 查找 revision-bound `ActionToken`。
3. 事件线程只做有界 `offer`，满 mailbox 立即拒绝。
4. actor 在公平规则池上最多提交一个在途转换。
5. continuation 回到 actor 后再次校验 revision；过期结果不提交。
6. 接受的状态先在内存生效，再进入该桌独立 outbox。
7. 最新投影异步变为 `SceneGraph`，被新 revision 覆盖的旧帧直接丢弃。

SPI 1.2 的 `ScheduledRuleAction` 由规则包为不可变状态给出已入座 actor、可重放动作、延迟和原因码。核心按输入到达序把玩家动作与 deadline 排序；先到者获胜。任务只绑定一个 revision，状态推进、持久化暂停、关闭或规则故障都会取消它，迟到回调只被丢弃，不会遍历全部牌桌，也不会在 timer 线程调用规则。

## CraftEngine 边界

CraftEngine bundle 在构建期复制已审查的 `craftengine/configuration` 与 `resourcepack`，并生成 SHA-256 清单；启动时逐文件校验后原子安装。内容完全未变且 CE registry 已加载时可直接恢复；任一文件变化后必须等新的 `CraftEngineReloadEvent`，期间场景保持关闭。家具模型、牌姿态、座椅、hitbox、interaction 与 entity culling 都由 CraftEngine YAML 的 template/config factory 表达，不由 Java 拼装。

Java 仅负责：

- 根据公开规则视图选择稳定资产 ID；
- 计算节点差分；
- 在目标 Folia region 下调用 CraftEngine `place/remove`；
- 将授权暗手通过客户端私有投影发送给本人；
- 将交互 handle 绑定到当前 revision 的 token。

交互绑定不是在差分入队时立即开放：后端会先移除旧 token，等目标 Folia region 内该 revision 的全部 CraftEngine 节点成功应用后再原子发布新绑定。迟到的旧 epoch、CE reload 中的操作或单桌失败都不能重新开放旧动作。

世界实体不得包含暗手正面。动态 HUD 也是逐玩家发送。

### 一套物理桌，不为玩法复制布局

桌体、四把椅子、牌模型、牌背、点棒与交互家具都是 CraftEngine 中的共享资产。核心只有一个 `UniversalTableLayout`，不会为日麻、MCR 或四川维护三套坐标代码。

规则包只声明会改变的事实：

- 本局实际使用的物理牌实例与正反面；
- 每边牌墙的墩数、开门墩与摸牌方向；
- 每位玩家的手牌、牌河、副露、花牌、和牌张与点棒顺序；
- 副露牌的持有座位、来源座位以及 `ORDINARY/CLAIMED/ADDED` 语义角色；
- 立直横牌、强调以及动作应绑定到哪一张实体牌。

布局编译器据此把语义区映射到同一张现实麻将桌。共享的 `RuleMeldPresentation` 根据来源座位统一决定副露的左/中/右横牌，并把加杠牌叠在原来源牌上；三个规则包不能各自维护这套坐标算法。四川的 `14/13/14/13`、日麻的 `17/17/17/17` 和 MCR 的 `18/18/18/18` 都只是规则数据，不是核心分支。座位编号从桌面本地 `+Z` 侧开始并顺时针递增，手牌与牌河按玩家视角从左到右。

为避免多桌互相抢 CPU，缓存键只包含可复用的物理桌型；每局不同的开门位置与摸牌方向在已编译坐标上做 O(1) 映射，不会重新计算三角函数。每帧先解析一次布局，后续每张牌只做有界数组访问。

## 持久化与恢复

`match_instance`、`match_event`、`match_snapshot`、`match_participant` 与 `table_anchor` 共同定义恢复边界。初始快照、参与者与世界锚点在一个 SQL 事务内创建。动作按 `(match_id, sequence)` 幂等写入；恢复只读到最后已提交 sequence。

进程崩溃可能丢失内存先行但尚未提交的尾部动作，这是 2.0 明确采用的语义；已经提交的支付不得重复。
