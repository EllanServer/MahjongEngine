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

规则包只通过父 classloader 提供的 SPI 通信。规则包是完整 JVM 受信代码；Ed25519 签名验证来源，不宣称提供 Java 沙箱。

## 动作链路

1. CraftEngine 交互实体只携带稳定 `InteractionHandle`。
2. `InteractionRouter` 以 `(handle, player)` O(1) 查找 revision-bound `ActionToken`。
3. 事件线程只做有界 `offer`，满 mailbox 立即拒绝。
4. actor 在公平规则池上最多提交一个在途转换。
5. continuation 回到 actor 后再次校验 revision；过期结果不提交。
6. 接受的状态先在内存生效，再进入该桌独立 outbox。
7. 最新投影异步变为 `SceneGraph`，被新 revision 覆盖的旧帧直接丢弃。

## CraftEngine 边界

CraftEngine bundle 在构建期复制已审查的 `craftengine/configuration` 与 `resourcepack`，并生成 SHA-256 清单；启动时校验后原子安装。家具模型、牌姿态、座椅、hitbox、interaction 与 entity culling 都由 CraftEngine YAML 的 template/config factory 表达，不由 Java 拼装。

Java 仅负责：

- 根据公开规则视图选择稳定资产 ID；
- 计算节点差分；
- 在目标 Folia region 下调用 CraftEngine `place/remove`；
- 将授权暗手通过客户端私有投影发送给本人；
- 将交互 handle 绑定到当前 revision 的 token。

世界实体不得包含暗手正面。动态 HUD 也是逐玩家发送。

## 持久化与恢复

`match_instance`、`match_event`、`match_snapshot`、`match_participant` 与 `table_anchor` 共同定义恢复边界。初始快照、参与者与世界锚点在一个 SQL 事务内创建。动作按 `(match_id, sequence)` 幂等写入；恢复只读到最后已提交 sequence。

进程崩溃可能丢失内存先行但尚未提交的尾部动作，这是 2.0 明确采用的语义；已经提交的支付不得重复。
