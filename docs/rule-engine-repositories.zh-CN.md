# 规则引擎拆仓说明

麻将规则不再以 Paper/Folia 牌桌控制器作为复用边界。第一阶段在本工作区的
`rule-repositories/` 下维护三个独立 Git 仓库：

| 规则 | 本地仓库 | Java 包前缀 |
| --- | --- | --- |
| 日麻（雀魂四人规则 profile） | `riichi-mahjong-java` | `top.ellan.mahjong.rules.riichi` |
| 国标麻将（MCR） | `mahjong-mcr-java` | `top.ellan.mahjong.rules.mcr` |
| 四川麻将（T/TFMJ 01—2024） | `sichuan-mahjong-java` | `top.ellan.mahjong.rules.sichuan` |

三个目录各自拥有构建、测试、许可证、规则来源和版本历史，不作为 MahjongPaper
主仓库的源码目录提交。远程仓库地址确定后，再以带校验和的版本化 Maven 制品接入；
Git submodule 只能用于源码审阅，不能成为生产构建的相对源码依赖。

## 拆分边界

规则仓库负责：

- 牌、风位、副露、和牌上下文和类型化规则配置；
- 牌形、向听/听牌、番役/番种、符与支付；
- 动作合法性、反应优先级和规则状态转换；
- 可确定性重放的场景及规则测试契约；
- 不依赖 Minecraft 的性能基准。

MahjongPaper 主仓库继续负责：

- UUID 与规则座位的映射；
- Paper/Folia 调度、倒计时、断线托管和机器人调度；
- CraftEngine、渲染、声音、命令、UI、数据库和本地化；
- 规则领域事件与玩家可见快照之间的适配。

规则仓库不得依赖 Bukkit/Paper、Adventure、CraftEngine、数据库、插件配置或主仓库
内部 DTO。不同玩法也不得再互相借用反应或结算类型。

## 规则基线

- 日麻默认 profile 固定为雀魂四人规则；`EARLY_KAN_DORA` 只表示杠宝揭示时机差异，
  旧 `TOURNAMENT` 名称不是完整赛事规则。
- 国标以固定版本的 WMO/EMA Mahjong Competition Rules（Green Book）及 EMA 补充规程
  为裁决依据。旧 JNI/GB-Mahjong 只用于差分，不自动视为权威结果。
- 四川以 T/TFMJ 01—2024 为裁决依据。换三张及其他地方房规不能混入默认 profile。

每个仓库都必须记录引用来源、读取日期、适用范围和已知未覆盖项。遇到无法裁决的输入时应
显式返回错误（fail closed），不得猜测或静默降级成“不能和”。

## 性能约束

三套引擎统一使用 Java 21。牌形热点使用紧凑整数索引、计数数组和不可变值对象；最终生产
热点路径不得依赖 JSON、反射、字符串 flag、Stream 或按次创建 UUID。当前日麻计分适配器
仍有隔离的反射/Kotlin 后端，这是 1.0 切换前必须消除或经性能门槛批准的已知边界。缓存键
必须保留完整相等语义，不能仅用 64 位哈希代替请求本身。

## 当前验证快照

2026-08-08 在 GraalVM JDK 25 上以 `--release 21` 独立构建：

| 仓库 | 本地首提交 | 测试 | 运行时边界 |
| --- | --- | ---: | --- |
| `riichi-mahjong-java` | `d3ed7d0` | 89（另含 12,000 手牌确定性差分） | 向听/听牌为原生 Java；计分仍依赖固定的 mahjong-utils/Kotlin 后端 |
| `mahjong-mcr-java` | `cf8d61b` | 97 | `jdeps` 仅 `java.base` |
| `sichuan-mahjong-java` | `15cc378` | 35 | `jdeps` 仅 `java.base` |

三仓均保留独立 Git 历史和 Gradle Wrapper，并在 EllanServer 下以私有仓库维护：
[riichi-mahjong-java](https://github.com/EllanServer/riichi-mahjong-java)、
[mahjong-mcr-java](https://github.com/EllanServer/mahjong-mcr-java)、
[sichuan-mahjong-java](https://github.com/EllanServer/sichuan-mahjong-java)。首个发布分支统一为 `2.0`；
签名私钥和发布凭据不进入源码。各仓 `BENCHMARK.md` 记录同机冒烟结果和适用限制。

## 接入顺序

1. 在三个独立仓库内通过规则 TCK、属性测试和微基准。
2. 为 MahjongPaper 增加三个窄适配器，只做类型映射，不复制规则判断。
3. 同一确定性牌谱并跑旧实现和新实现；差异逐项回查规则来源。
4. 规则差异完成裁决后，主仓切换到版本化制品，并删除旧 Kotlin/JNI/混合控制器规则代码。
5. Paper 集成、渲染和 live-server 清单通过后，才发布稳定版。

拆分期间，旧实现仍是线上兼容基线，但不是新引擎的规则权威。

主仓逐类适配关系、切换门槛和回滚步骤见 [规则引擎迁移映射](rule-engine-migration-map.zh-CN.md)。
