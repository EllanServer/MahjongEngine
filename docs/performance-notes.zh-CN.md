# 性能笔记（2.0）

本文记录**实测**结果与归因，不记录猜测。每条结论都注明测量方式，便于复核与推翻。

## 复现方式

```powershell
$env:GRADLE_USER_HOME='E:\project\majiang\.gradle-home'
.\gradlew.bat :mahjong-presentation:sceneProjectionBenchmark
```

规则包各自的冒烟基准（需先发布 SDK 到本地仓库）：

```powershell
# 先发布 SDK（clean 之后必做）
.\gradlew.bat :mahjong-rule-spi:publishAllPublicationsToTestRepository `
              :mahjong-rule-tck:publishAllPublicationsToTestRepository

cd rule-repositories\mahjong-mcr-java
.\gradlew.bat microBenchmark "-PmahjongRuleSdkRepo=E:\project\majiang\build\rule-sdk-repository" --no-daemon
# riichi 与 sichuan 的任务名是小写的 microbenchmark
```

这些都不是 JMH，没有 fork、置信区间与分位数，只能在**同一机器、同一 JVM、同一参数**下做前后比较，不可用于跨机器性能声明。

## 已测基线（本机，2.0 分支）

| 路径 | 频率 | 实测 | 线程 |
|---|---|---|---|
| 场景投影 `map` | 每次状态变更 | 14.1–14.9 µs，31,728 B/次（214 节点） | 专用 |
| 场景投影 `map`+diff | 每次状态变更 | 19.2–20.5 µs，33,408 B/次 | 专用 |
| MCR `evaluate`（代表性番种） | 每次和牌判定 | 4,444 ns | `FairRuleExecutor` |
| MCR `waits`（34 种听牌枚举） | 每次听牌查询 | 56,692 ns | `FairRuleExecutor` |
| 立直 shanten（缓存未命中） | 每次手牌分析 | 9,257 ns | `FairRuleExecutor` |
| 立直 shanten（缓存命中） | 同上 | 207 ns | `FairRuleExecutor` |
| 立直计分（缓存未命中） | 每次和牌结算 | 98,963 ns | `FairRuleExecutor` |
| 立直计分（缓存命中） | 同上 | 753 ns | `FairRuleExecutor` |
| 立直反应窗口 | 每次鸣牌判定 | 5,344 ns | `FairRuleExecutor` |
| 四川和牌判定 | 每次和牌判定 | 143 ns | `FairRuleExecutor` |
| 四川听牌掩码 | 每次听牌查询 | 1,210 ns | `FairRuleExecutor` |
| 四川物理弃牌转换 | 每次弃牌 | 1,551 ns | `FairRuleExecutor` |
| actor 管道（64 桌并发） | 每次玩家动作 | 162.6 µs/动作，约 6,151 动作/秒 | 8 dispatcher + 8 rule worker |

三包对比值得注意：**立直与四川都有分析缓存，MCR 没有**。立直 shanten 命中缓存后是 207 ns（未命中 9,257 ns，约 45 倍差距），而 MCR 每次 `waits` 都要付满 56,692 ns。这是三包之间最大的实现差异。

但**不要因此就给 MCR 加缓存**：见下文机器人决策一节的算账，MCR 的听牌枚举只在专用线程池上按回合触发，CPU 占比约 0.08%，而缓存键写错会直接产出错误番数。若将来 MCR 真的成为瓶颈，正确做法是照立直的方式——把手牌规范化打包成基本类型键，并配套差分金标测试。

### actor 管道吞吐的读法

`TableActorThroughputTest` 用 64 张桌、每桌 12 轮共 768 次动作测量管道本身：邮箱投递、公平执行器派发、投影重建、token 签发、outbox 追加。规则提供者是平凡桩，所以这个数字**不含规则计算**（规则成本见上表各包条目）。

两点读数局限，别把它当上限：

- 每轮有屏障——64 桌全部完成才进下一轮，所以 162.6 µs/动作里含排队等待，不是单次动作的服务时间。
- 测试用 `Thread.onSpinWait()` 等待投影推进（动作 token 一次性，必须拿到新投影才能发下一轮），这会与 8 个 worker 抢 CPU，使数字**偏悲观**。

即便如此也足够宽裕：真实对局每桌每秒约 1~2 次动作，6,151 动作/秒对应数千张同时活跃的桌，远超任何 Minecraft 服务器的实际规模。**管道不是瓶颈。**

断言只覆盖正确性（全部 `ACCEPTED_MEMORY`、每桌最终 revision 等于轮数），吞吐只打印不断言——在共享 CI 硬件上设时间阈值只会产出与代码无关的失败。要发现回退，请在同一台机器上比较打印值。

## 关键归因：什么**不是**热路径

逐条核对过，避免过早优化：

- **场景投影是事件驱动的**，不是逐 tick。`LatestSceneProjector.publish` 用 revision CAS + latest-wins 槽位合并，只在状态变更时跑。麻将桌每秒状态变化只有几次，20 µs/次可以忽略。全仓除 `PersistenceOutbox` 的 50 ms 刷盘外**没有周期性任务**。
- **场景链路已深度优化过**，不要重复劳动：`SceneNodeIdentity` 有四层无锁有界缓存（tile id、hash 段、player key、interaction handle），`DefaultTableSceneMapper` 的 map 与 bindings 列表都按预估容量预分配，`SceneNodeId.trusted()` 跳过每帧正则校验。首次 publish 的全量排序只发生一次（`slot.applied == null`）。
- **机器人决策不是瓶颈**。一次弃牌决策要为每个候选打分，约 14 次 `waits()` ≈ 800 µs；但它跑在**独立的 `FairRuleExecutor`**（有界公平池，含每包配额、超时、熔断），不占服务器/region 线程。按每手约 64 次决策计，CPU 占比约 0.08%。**不要为此在规则代码里加缓存换取正确性风险。**
- **点击路由是 O(1)**。`InteractionRouteRegistry` 用 `ConcurrentHashMap<RouteKey, …>`，没有线性扫描；这是唯一跑在服务器线程上的高频路径。
- **1.x 的头号热点在 2.0 不存在**。旧版 `LocalizedMessages` 每帧对每座位做 MiniMessage `deserialize` + `serialize`（占插件采样 11.7%）。2.0 的 `LocalizedMessageCatalog` 存纯文本，只做两次 map 查找加 `String.format`。

## 已修问题

**`idx_rank_summary_ladder` 漏列 `total_score`，索引完全失效。**

排行 `ORDER BY tier_ordinal DESC, tier_level DESC, stage_points DESC, total_score DESC, player_id`，而索引建成了 `(rule_id, rank_system, tier_ordinal, tier_level, stage_points, player_id)`——**在第 4 个键就与排序分叉**。索引只有在列序完整覆盖排序键时才能服务 `ORDER BY`，缺中间键则任何查询计划器都用不上它。

后果：索引白占写入开销，且每次 `/mahjong rank` 都在内存里排序整个 `(rule_id, rank_system)` 分区。

第一轮 H2 `EXPLAIN` 用 `SELECT player_id` 验证，补列后确实从主键切到 ladder 索引。但这个探针是**覆盖索引查询**，生产代码还要读积分、局数、段位与名次统计。把测试改成生产完整列集后，它重新选择主键并排序整个分区——说明旧守卫给了假信心。

最终方案没有把全部展示列塞进超宽索引（那会为每个玩家增加数百字节索引并放大每局写入），而是改成单 SQL 两阶段：

1. 内层由窄 ladder 索引选出当前页最多 51 个 ID 与排序键（第 51 行用于判断下一页）；
2. 外层按复合主键取完整展示行；
3. 外层即使排序，也只处理已限定的 51 行，而不是整个排行榜；
4. 仍是一条 SQL，保持一次网络往返和一个语句快照。

H2 `EXPLAIN`（2,000 行 + `ANALYZE`）确认内层选择 `idx_rank_summary_ladder`。`RankingQueryPlanTest` 三项守卫固定此结论：索引列序必须逐字镜像 `ORDER BY`；**生产形状的两阶段分页**必须让内层走 ladder 索引；个人名次的 `COUNT(*)` 不得退化为全表扫描。

**教训**：复合索引与 `ORDER BY` 是一份必须逐字对应的契约，且计划测试必须镜像生产的列集、分页与 join 形状；一个简化成 covering query 的探针可能通过，却不能证明生产查询快。守卫测试读真实元数据与 `EXPLAIN`，比读代码可靠。

## 跨引擎可移植性（H2 已实测，真实引擎门禁已接入 GitHub）

项目支持把 `database.jdbc-url` 指向 MariaDB/MySQL（见 `SqlDialect`、`DatabaseBootstrap` 的驱动选择），**且没有声明最低版本**。排行的 `ORDER BY` 现在每个键都降序，包括 `player_id` 平手键；`idx_rank_summary_ladder` 则刻意声明为纯升序，让引擎通过反向扫描服务统一降序，而不依赖真正的降序索引。

H2 已确认两阶段查询的内层走 ladder 索引。本机没有 MySQL/MariaDB/Docker，所以没有伪装成本地实测；改由 GitHub 的 `external-sql-plans` job 启动真实的 **MySQL 8.4 LTS** 与 **MariaDB 11.4 LTS** service container，并运行 `ExternalRankingQueryPlanTest`：

- 先让生产 `SqlSchemaMigrator` 在两个全新数据库上完整迁移；
- 插入 12,000 条目标榜单 + 8,000 条干扰榜单并执行 `ANALYZE TABLE`，避免空表计划无意义；
- 从真实 `information_schema.statistics` 断言 7 列索引顺序完整且全部升序；
- 对生产形状的两阶段 SQL 执行真实 `EXPLAIN`；
- 内层必须选择 `idx_rank_summary_ladder`、access type 不能是 `ALL`、自身不得出现 `filesort`；
- CI 报告会记录数据库产品、完整版本与计划行。

普通本地 `check` 没有 JDBC URL 时自动跳过这两项，因此不会要求开发机安装数据库。**实际 MySQL/MariaDB 结果要等改动推送到 GitHub 后才算完成验证**；目前完成的是可重复的真实引擎门禁，不声称已在本机得到结果。

平手键翻向的代价只有一处且无实际影响：段位、级别、阶段分与总分全部相同的两名玩家，顺序从 `player_id` 升序变为降序；两种都是确定性的。

## 场景投影 A/B 回退门禁

`.github/workflows/build.yml` 的 `scene-projection-regression` job 只在 **pull request** 上运行，在**同一台 runner** 上先测本 PR，再 checkout base 提交测一遍，然后由 `.github/scripts/compare-scene-benchmark.py` 对比两组数字。

### 为什么必须同机 A/B，不能存基线文件

本基准是自制的，不是 JMH——没有 fork、置信区间与分位数，绝对值跨机器无意义。而 GitHub 的 `ubuntu-latest` 池会在不同规格 runner 间漂移。把本地测的数字存成基线再拿去和 CI 比，等于拿两台不同的机器比较，会因与改动无关的原因失败。**同机配对测量才是让它成为门禁而非噪声的前提。**

### 两个指标，两种容差（有实测数据支撑）

本机对**完全相同的代码**连跑两次：

| 指标 | 第一次 | 第二次 | 漂移 |
|:---|---:|---:|---:|
| `map` ns/op | 14,984.6 | 17,859.5 | **+19.2%** |
| `map` bytes/op | 31,544 | 31,848 | **+1.0%** |
| `mapAndDiff` bytes/op | 33,272 | 33,464 | **+0.6%** |

结论很清楚：

- **`bytes/op` 是可信信号**，容差 `×1.25`。分配量由代码与 JDK 决定，不受 CPU 速度、调度、温度影响，同机漂移约 1%。真正该抓的回退——往投影循环里重新引入每节点分配、或某个缓存悄悄不再命中——都会直接体现在这里。
- **`ns/op` 只用来抓数量级错误**，容差 `×1.30`。相同代码都能漂 19.2%，说明 30% 已是勉强够用的下限，再收紧就是制造 flaky。

### 门禁也会在测量消失时失败

如果 candidate 不再输出 base 里有的某项测量，脚本判定失败——删掉一项测量正是隐藏回退最省事的办法。

### 门禁有效性已验证

用合成数据确认它真的会触发，而不是永远放行：

| 场景 | 结果 |
|:---|:---|
| 分配量 +40% | `REGRESSED +40.1% (x1.40, limit x1.25)`，exit 1 |
| 墙钟 +45% | `REGRESSED +72.8% (x1.73, limit x1.30)`，exit 1 |
| 测量项消失 | `candidate no longer reports these measurements: mapAndDiff`，exit 1 |
| 真实两次噪声 | `within the gate`，exit 0 |

若确实要接受一次回退，应在 PR 里说明，并有意识地修改脚本里的容差常量——不要偷偷调大。

## 待办与未测项

- 三个规则包的 `BENCHMARK.md` 都写明「发布前需补 JMH」：分位数、分配量、多线程评估器、JDK 21/25 对比。目前只有冒烟基准。
- `sceneProjectionBenchmark` 已进 CI，见下节「场景投影 A/B 回退门禁」。
- `sichuan-mahjong-java/BENCHMARK.md` 已重写为与 MCR、riichi 两包一致的中文版：补充了 JMH 待办区、正确性门禁与最新实测表。早前记录称其「内容为乱码（编码损坏）」是误判——文件本就是合法 UTF-8，乱码只是 PowerShell 默认 GBK 代码页读取 UTF-8 文件的显示假象；现已修正。
- actor 管道吞吐已测（见上表与读法说明）。相关**行为**此前就有覆盖——`FairRuleExecutorTest` 8 项涵盖单包占满池、双包共享、空闲回收、跨包配额隔离、超时只隔离肇事包、三次失败开熔断；`TableActorIsolationStressTest` 有 64 桌隔离测试。
- 未测：大牌河下的 diff 规模。
- 真实 MySQL/MariaDB 计划门禁已实现；尚待推送后取得第一次 GitHub 运行结果。
