# 规则 SPI 与 TCK

`mahjong-rule-spi` 只依赖 `java.base`，定义 ID、动作、事件、公开/私有视图、合法动作 token 与版本化二进制快照。

`RulePackProvider` 必须满足：

- 同一 seed、profile、配置和动作序列产生完全相同结果；
- 状态不可变；拒绝动作返回同一状态实例且无事件；
- 不访问平台、文件、网络、系统时钟或线程；
- 私有视图只返回指定 viewer 的秘密；
- 快照可恢复并重放到相同状态哈希；
- 支付与排名投影遵守玩法自己的守恒约束。

SDK 发布工作流位于 `.github/workflows/rule-sdk.yml`。规则 fat JAR 必须排除 SPI，让核心 classloader 提供唯一协议类型。
