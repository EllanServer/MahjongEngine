# 并发与隔离

- 每桌 mailbox 默认 128，只有 actor 修改该桌状态。
- actor、场景预计算、SQL 使用共享但有界的独立执行器；不创建每桌线程。
- 规则池按玩法 round-robin，任何玩法最多占用一半 worker。
- 每桌最多一个规则转换在途；其他玩家收到 `RULE_BUSY`，不会阻塞事件线程。
- outbox 默认 50ms 或 16 条批量提交；32 个动作以及局/比赛结束生成快照。
- 单桌未落库动作达到阈值或最老事件超过 2 秒时，仅该桌进入 `PAUSED_PERSISTENCE`；排空后自动恢复。
- `SceneGraph` 预计算每桌只保留最新请求。
- render 执行器或 deadline 目标执行器瞬时饱和时使用逐桌合并、有界退避重投，不丢初始场景或 SQL flush。
- CraftEngine 默认每桌每 tick 最多 16 个节点变更，每个 region 共享 1.5ms 公平预算。
- 新 revision 到达时先关闭该桌交互路由；只有对应 CraftEngine 差分全部成功后才发布动作 token，失败 revision 始终 fail-closed。
- 家具裁剪和查看距离由 CraftEngine YAML 的 `entity_culling` 负责，Java 不扫描附近玩家。
- 没有全桌逐 tick 扫描；倒计时使用有界 deadline heap 注册一次性任务。
- Paper/CraftEngine 事件线程禁止执行规则、SQL、下载和布局计算。

CI 的隔离测试必须覆盖：64 桌、同 region 多桌、慢规则包、满 mailbox、数据库延迟、CraftEngine 单桌异常和关闭时 outbox 排空。
