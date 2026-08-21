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
  automation/          托管入口与平台中立玩家在线状态端口
  concurrent/          有界规则池、单次任务调度
  feedback/            规则瞬时 cue 批次与平台端口
  interaction/         O(1) 路由、选牌与俯视视角端口
  opening/             开局骰子/开门阶段批次与表现端口
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

插件根包只允许保留 `MahjongPaperPlugin` 入口和 `MahjongRuntime` 装配根。配置位于 `plugin/config`，活动桌索引位于 `plugin/table`，比赛创建与恢复编排位于 `plugin/match`；其中规则初态创建、来源哈希和 actor 构造是独立组件，不再集中在一个比赛协调类中。最终产物是 thin JAR：构建只合并本仓内部模块，`MahjongPaperLoader` 通过 Paper `MavenLibraryResolver` 提供第三方运行时，`verifyThinJar` 拒绝 foreign class 与 nested JAR。

`mahjong-craftengine` 同样没有根包杂糅：`bundle` 管理签名构建产物安装，并串行调用 CE 自身的 config reload、PackManager 资源包生成与 reload event；`interaction` 只把 CE 行为回调转为平台中立输入，`port` 保存跨平台边界；`scene` 分别管理公开家具和按观众条件显示的 CE 家具及选牌 variant；`privateview` 只保留 CE 无法表达的动态参数文字、BossBar 和镜头。每个场景节点把 table/node/channel/interaction/schema 写入家具自己的 `FurniturePersistentData` custom NBT；注册到 `FurnitureBehaviors` 的 `mahjongpaper:managed_scene` controller 直接接收 CE 26.8 的 `loadCustomData/onLoad/onUnload/useOnFurniture/onPlayerHit`，由 `BukkitFurnitureManager` 负责放置、区块恢复、实体索引、座位流水线与持久化。Java 只保留由这些 CE 回调实时填充的并发语义索引和权威 desired-node 集合，不再写 anchor chunk UUID 索引，也不再监听 Paper entity add/remove 或家具 interact/hit/break 事件。未知 schema、缺失回调和私有观众缺失均 fail-closed；陈旧/重复实例只在其 Folia owning region 回收。网关禁止 `getWorlds`、区块实体枚举或附近实体扫描；跨区调用仍必须经 Paper region/entity scheduler，因为 CE 的安全操作器不能替代 Folia ownership。CE 注册表跨插件 classloader reload 的旧 factory 通过只含 JDK 容器/函数接口的反射桥复用，不持有新插件类型。场景后端仍按每桌目标状态、每 region 公平调度和单节点 mutation 执行器隔离，故障与积压不会穿过该边界。CraftEngine 生产类由 CI 强制限制在 350 行以内。Paper/Folia 适配只能依赖 `port`，不能反向依赖 CE 的具体场景实现。

其中 `opening` 是独立子包，只编排规则声明的有限开局阶段；它不保存模型或空间几何。骰子槽位、桌面高度、旋转、阴影、裁剪与 `single_face_*`/`double_face_*` variant 全部属于 CE YAML。开门动画只通过平台中立 `TableOpeningEffectPort` 发出有界阶段信号；Paper 的 `feedback` 适配器负责逐玩家调度声音，不能反向进入 application 或 CE 场景模块。

`mahjong-platform-paper` 只放 Paper/Folia 适配，并按 `anchor / concurrent / feedback / region` 分类：世界锚点、平台线程池、瞬时音效和区域调度各自独立，不在平台根包堆积工具类。Paper 模块同时拥有窄化的锚点查询与 region 调度端口；`mahjong-craftengine` 可以依赖这些 Paper 端口，Paper 绝不能反向依赖 CraftEngine 实现。

`mahjong-persistence-sql` 按 `connection / schema / event / match / lobby / anchor / recovery / common` 分类。比赛身份行映射、初始恢复元数据和大厅消费是独立 SQL 组件；大厅变为比赛时仍共用一个 JDBC 事务，不以模块化为代价拆散原子性。

`mahjong-presentation` 只保存平台无关的桌面语义，并按 `asset / layout / node / port / projection / scene` 分类：资产名、通用桌面坐标编译、节点值对象、后端端口、规则视图映射和场景差分互不混放。通用布局内部再分成有界缓存、冷路径编译器和不可变热路径查表计划；场景映射再按 `publicview / privateview / interaction / asset / support` 分解，公开实体、私有 HUD/暗手和 revision-bound 命中区不会堆在同一个类中。表现层生产类由 CI 强制限制在 300 行以内。具体 CraftEngine 家具调用仍只存在于 `mahjong-craftengine`。

`mahjong-rule-runtime` 按 `activation / admin / catalog / common / install / lifecycle / loading / registry / resources / security / storage` 分类，激活状态、管理命令、下载安装、类加载、签名注册表、资源 ZIP 检查和本地文件边界彼此独立。`mahjong-rule-spi` 是三个外部规则仓共同编译的稳定公共协议，因此保持扁平且版本化，不把一次内部整理变成规则包 ABI 破坏。

规则包只通过父 classloader 提供的 SPI 通信。规则包是完整 JVM 受信代码；Ed25519 签名验证来源，不宣称提供 Java 沙箱。

### 运行时热插拔与双代并存

规则包支持不重启替换。语义对应「一局 = 一个会话」：新开局拿到新版本，进行中的牌局继续跑创建时的版本，旧版本引用归零后才卸载。

- `LoadedRulePack` 持有租约集合。建局与恢复时按 `tableId` acquire，牌桌 `closeAndDrain()` 完成后 release。还有租约的版本不能 close。
- `RulePackRuntime` 同时维护 `active` 与 `superseded` 两代。`providerForNewMatch` 只返回 active 代，`providerForPinnedMatch` 也能返回 superseded 代，因此恢复旧局始终可用。
- `RuleActivationStore.activateNow` 立即写入 active，不依赖 `pendingJvmStartMillis` 的 JVM epoch 判据；`requestActivation` 保留为需重启的降级路径。`previous` 字段记录被取代的坐标，`rollback` 据此一键回退，`deactivate` 同样把坐标存入 `previous`。
- `unload` 只接受无租约且非 active 的坐标，返回 classloader 的 `WeakReference`，用于验证 loader 真被回收而不是静默泄漏。
- 卸载后 `FairRuleExecutor.renewWorkers()` 逐个替换规则 worker 线程。规则代码可能留下 ThreadLocal，其 value 由规则包 classloader 加载会让 loader 无法回收；换线程是唯一可靠做法，反射清 `ThreadLocalMap` 不线程安全。worker 线程的 context classloader 固定为宿主 loader。
- `RulePackLoader` 在校验期就拒绝会导致泄漏或重复类定义的产物：JAR 不得包含 `top/ellan/mahjong/` 下除自身 `rules/` 外的核心类，也不得声明 `META-INF/services/java.sql.Driver`（driver 会被 `DriverManager` 静态表永久持有）。`ChildFirstRuleClassLoader` 的资源查找与类查找同为 child-first。
- 垃圾回收前会向运行时查询仍处于 loaded 的坐标并跳过它们：classloader 未关闭时 JAR 句柄仍打开，Windows 上 `Files.move` 会因文件占用失败。
- `stateSchemaVersion` 不一致时不允许把进行中的局切到新包（pinned 校验已强制），`status()` 中的 `supersededInUse` 暴露双代共存状态。

命令：`/mahjong rules swap <id> <version>` 立即对新开局生效，`/mahjong rules deactivate <id>` 停止分配新局，`/mahjong rules rollback <id>` 回退到上一个坐标。`activate` 仍是需重启的路径。

## 动作链路

1. CraftEngine 交互实体只携带稳定 `InteractionHandle`。
2. `InteractionRouter` 以 `(handle, player)` O(1) 查找 revision-bound `ActionToken`。
3. 事件线程只做有界 `offer`，满 mailbox 立即拒绝。
4. actor 在公平规则池上最多提交一个在途转换。
5. continuation 回到 actor 后再次校验 revision；过期结果不提交。
6. 接受的状态先在内存生效，再进入该桌独立 outbox。
7. 最新投影异步变为 `SceneGraph`，被新 revision 覆盖的旧帧直接丢弃。

SPI 1.6 的 `ScheduledRuleAction` 由规则包为不可变状态给出已入座 actor、可重放动作、延迟和原因码。系统推进来自 `scheduledAction`，机器人与玩家托管来自 `automatedAction`；后者只能从核心已计算的 `AutomatedPlayerActions` 中选取合法动作。核心比较两者后只安装一个任务，并按输入到达序把玩家动作与 deadline 排序；先到者获胜。任务只绑定一个 revision，状态推进、托管切换、持久化暂停、关闭或规则故障都会取消它，迟到回调只被丢弃，不会遍历全部牌桌，也不会在 timer 线程调用规则。

## CraftEngine 边界

插件主体的 CraftEngine bundle 在构建期只复制已审查的通用牌桌、凳子、麻将牌、骰子、hitbox 与本地化资源，并生成 SHA-256 清单；启动时逐文件校验后原子安装。每个规则版本另带独立的资源 ZIP，ZIP 的有效载荷必须完整位于 `craftengine/` 并由 CE 管理。运行时只依据签名 registry 校验其 URL、长度和 SHA-256，再校验资源 descriptor、索引和逐文件清单，最后交给 CE 安装到版本与 JAR 哈希隔离的目录。规则资源 ZIP 不进入规则 classloader，也不合并进插件主体资源包。

开局骰子同样遵守这条边界：四个稳定槽位家具和所有 face/layout variant 由 CE 配置生成。Java 只提交槽位与点数；同一节点变化时在目标 Folia region 调用 CE `setVariant`，不删除并重建实体。只有节点首次出现和开局层结束时才执行 `place/remove`。

声音不是场景实体。规则代码只发出平台无关 cue；同版本的 CE 资源 ZIP 通过 `META-INF/mahjong-rule-sounds.properties` 把 cue 映射为版本化命名空间下的 sound event、音量和音高，实际 `sounds.json` 与音源只由 CE pack 提供。Paper 每名听众至多提交一个 Folia player-scheduler 任务。骰子开始和墙打开与 CE 动画阶段同步，但声音失败只丢失该次装饰性反馈，不能取消动画或规则 transition。插件配置不再硬编码 MCR/四川前缀，也不持有或分发规则音源。

玩家文字使用 Adventure translatable component，翻译键随 CE 资源包提供 `en_us/zh_cn/zh_tw/ja_jp` 四套文件；控制台使用代码内英文回退。构建期强制四个 locale 的键集合与 `%s` 参数数量完全一致，因此新增命令、动作或反馈不能只补一种语言。

Java 仅负责：

- 根据公开/本人规则视图选择稳定资产 ID 和语义 variant；
- 计算节点差分；
- 在目标 Folia region 下调用 CraftEngine `place/remove/move/setVariant`；
- 向自定义 CE condition 提交家具 UUID 对应的唯一授权玩家 UUID；
- 将交互 handle 绑定到当前 revision 的 token。

几何、模型、碰撞、culling、文字样式和选择上抬值全部位于 CE 资源包，而非插件 `config.yml`。暗手家具的真实 meta entity 只携带牌背 item；CE 对每名观众互斥求值牌背与正面虚拟元素，条件状态缺失时正面必定隐藏。授权变化先 `hide` 再 `show`，确保客户端不会保留上一次条件求值的陈旧元素。

交互绑定不是在差分入队时立即开放：后端会先移除旧 token，等目标 Folia region 内该 revision 的全部 CraftEngine 节点成功应用后再原子发布新绑定。迟到的旧 epoch、CE reload 中的操作或单桌失败都不能重新开放旧动作。带动态牌/花色参数的文字、BossBar 和 CE 未提供的 camera packet 仍逐玩家发送；无动态参数的动作文字由 CE 条件家具和客户端语言表直接呈现。

### 一套物理桌，不为玩法复制布局

桌体、四把椅子、牌模型、牌背、点棒与交互家具都是 CraftEngine 中的共享资产。核心只有一个 `UniversalTableLayout`，不会为日麻、MCR 或四川维护三套坐标代码。

规则包只声明会改变的事实：

- 本局实际使用的物理牌实例与正反面；
- 每边牌墙的墩数、开门墩与摸牌方向；
- 每位玩家的手牌、牌河、副露、花牌、和牌张与点棒顺序；
- 副露牌的持有座位、来源座位以及 `ORDINARY/CLAIMED/ADDED` 语义角色；
- 立直横牌、强调以及动作应绑定到哪一张实体牌。

布局编译器据此把语义区映射到同一张现实麻将桌。共享的 `RuleMeldPresentation` 根据来源座位统一决定副露的左/中/右横牌，并把加杠牌叠在原来源牌上；三个规则包不能各自维护这套坐标算法。四川的 `13/14/13/14`、日麻的 `17/17/17/17` 和 MCR 的 `18/18/18/18` 都只是规则数据，不是核心分支。座位编号从桌面本地 `+Z` 侧开始并顺时针递增，手牌与牌河按玩家视角从左到右。

为避免多桌互相抢 CPU，缓存键只包含可复用的物理桌型；每局不同的开门位置与摸牌方向在已编译坐标上做 O(1) 映射，不会重新计算三角函数。每帧先解析一次布局，后续每张牌只做有界数组访问。

## 持久化与恢复

`match_instance`、`match_event`、`match_snapshot`、`match_participant` 与 `table_anchor` 共同定义恢复边界。初始快照、参与者与世界锚点在一个 SQL 事务内创建。动作按 `(match_id, sequence)` 幂等写入；恢复只读到最后已提交 sequence。

进程崩溃可能丢失内存先行但尚未提交的尾部动作，这是 2.0 明确采用的语义；已经提交的支付不得重复。
