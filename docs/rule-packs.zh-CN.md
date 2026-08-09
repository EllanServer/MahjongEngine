# 官方规则包

三个规则仓独立维护并通过同一 `mahjong-rule-spi`/TCK 接入：

- `EllanServer/riichi-mahjong-java`
- `EllanServer/mahjong-mcr-java`
- `EllanServer/sichuan-mahjong-java`

规则包负责完整比赛，不只是计分：牌墙、发牌、摸牌、合法动作、反应优先级、计分支付、局次推进和比赛结束都必须封装在 provider 的不可变 `RuleState` 中。

核心只接受固定 ID：`riichi`、`mcr`、`sichuan`。首版不承诺第三方规则包兼容。

发布包要求：

- Java 21 fat JAR，排除 SPI；
- 非 `SNAPSHOT` 语义版本；
- `ServiceLoader` provider 与完整 descriptor；
- JAR SHA-256、状态 schema、核心/SPI 兼容范围；
- GitHub Release 中的签名 registry 元数据；
- 官方 Ed25519 私钥只存在于发布环境；
- 通过规则 TCK、规则准确性金标与快照重放测试。

当前三个仓已有 Java provider 与 TCK 基础，但正式签名 Release/registry 和完整实服验收仍是 2.0 发布阻断项，不能把“源码可编译”描述成“已可正式服上线”。
