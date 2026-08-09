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

## 桌面呈现契约

SPI 1.1 不允许规则包传入自由格式坐标或让核心猜测某种麻将记谱。规则包输出规范化的 `TileVisualId`、`RuleTablePresentation`、`RuleTilePresentation` 与 `ActionPresentation`：

- `RuleWallPresentation` 声明每边墩数、开门墩和摸牌方向；
- `layoutIndex` 是规则拥有的稳定逻辑槽位，仍在牌墙中的同一实体牌跨 revision 不得换槽；
- `RuleTileRotation` 和 `stackLevel` 表达立直横牌、鸣牌来源与加杠叠牌；
- 普通弃牌动作直接绑定授权私有手牌的 `TileInstanceId`，其余动作进入主/次动作行；
- 规则包将自身牌面记法转换成共享资产后缀，例如 `m5_red`、`east`、`bamboo`，核心不解析玩法私有字符串。

TCK 会验证座位、私有视图授权、直接动作目标、牌墙容量、跨 transition 的稳定牌墙槽位以及快照恢复后的呈现确定性。
