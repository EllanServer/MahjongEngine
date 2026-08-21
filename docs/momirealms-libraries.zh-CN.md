# Momirealms 库采用审计

本页记录对 [`net.momirealms` 发布仓库](https://repo.momirealms.net/releases/net/momirealms/) 全部 24 个制品的完整审计结果。结论以实际 JAR、sources JAR、Gradle module metadata、编译和本地基准为准，不因库名相似就强行替换。

## 已采用

| 制品 | 版本 | 用途与边界 |
|---|---:|---|
| `craft-engine-core` / `craft-engine-bukkit` / `craft-engine-bukkit-proxy` | **锁定 26.8** | CE 负责公开家具、持久化/区块重载、实体索引、座位流水线、交互/命中入口、定义重载替换、私有手牌条件元素与选择 variant，以及无动态参数的动作文字家具。场景身份写入 CE `FurniturePersistentData`，自定义 `FurnitureBehavior` 的 load/unload callback 取代 anchor PDC UUID 索引和 Paper entity/furniture 事件；放置直接走 `BukkitFurnitureManager`。bundle 变化通过 CE `reloadPlugin` 与 `PackManager.generateResourcePack` 串行生效。所有几何、颜色、碰撞、culling 和 variant 参数位于 CE 资源包；Java 只提交权威 desired state、规则交互与 O(1) 私有观众授权。26.8 的 `BukkitAdaptor`、network user 和 packet proxy 继续承担世界/客户端代理边界。|
| `antigrieflib` | 1.0.17 | 已有的 fail-closed 领地保护统一适配层；不自行维护各领地插件分支。|
| `sparrow-reflection` | 0.34 | 已有的 NMS 类/字段定位。镜头包继续预热并缓存一个类型适配后的 `MethodHandle`：Sparrow 自身源码明确要求跨插件/NMS class loader 时使用 `unreflectSetter()`/MethodHandle，而不是不可见的 nestmate ASM 字段访问器。|
| `sparrow-yaml` | 1.0.12 | `GameRoomFileCodec` 已取代 Bukkit `YamlConfiguration` 的手工逐字段读写。使用类型化 `NodeSerializer`、拒绝重复键/对象键/alias、限制 200 万 code point，并继续采用临时文件 + 原子替换。每个 registry 独占 codec，方法同步，不共享 Sparrow YAML 的可变 representer。|
| `sparrow-minimessage` | 0.5 | 取代命令帮助页的大段手工 Adventure component 拼接。所有动态字符串均走 `unparsed`/`component`/`styling` placeholder，玩家文本不能注入 click/hover 标签；结果按六种规范化 locale、权限级别和页码缓存在有界 `ConcurrentHashMap` 中。|

## Adventure 性能结论

复现实验：

```text
./gradlew :mahjong-plugin:messageRenderingBenchmark
```

Java 25、G1、256 MiB heap；预热 20,000 次，每组 100,000 次，7 个样本取中位数。工作负载为帮助页同形模板、5 个动态 placeholder：

| 实现 | ns/op | ops/s | bytes/op |
|---|---:|---:|---:|
| 直接 Adventure builder | 653.1–668.2 | 1,496,544.5–1,531,095.8 | 4,296 |
| Sparrow MiniMessage 0.5 | 8,012.7–8,085.7 | 123,674.6–124,801.7 | 23,376–23,464 |
| Kyori MiniMessage 5.2.0 | 14,793.1–15,509.8 | 64,475.4–67,599.2 | 30,936–31,440 |

连续两次进程中，Sparrow 相对 Kyori MiniMessage 都降低约 **46%–48% 延迟**、约 **24%–25% 分配**，用户指出的优势得到实测确认。但视觉等价的直接 builder 仍约快 **12 倍**，分配约为 Sparrow 的 **1/5.5**。因此采用边界是：

- 命令帮助等低频、复杂、模板化富文本使用 Sparrow，并缓存最终不可变 Component；
- HUD、场景投影、倒计时、普通单色消息等热路径继续直接构造或复用 Adventure Component；
- 绝不把纯文本 locale 的 map 查找 + `String.format` 改成逐次富文本解析。

项目不依赖 PlaceholderAPI：插件内部富文本变量使用 Sparrow MiniMessage 的类型化 resolver；CE 私密可见性使用按 UUID O(1) 查询的自定义 condition，既不经过字符串桥，也不会把隐藏状态暴露给第三方 placeholder。

## 明确不采用

| 制品组 | 原因 |
|---|---|
| `craft-engine-adventure` | 使用 CE relocated Adventure 类型，与 Paper 的 `net.kyori.adventure` API 不兼容；属于 CE 内部集成包。|
| `sparrow-heart` | CE 26.8 已接管私有手牌与静态动作文字；保留下来的动态语义文字和镜头实体直接复用 CE 的实体 ID、metadata、packet proxy 与 `NetWorkUser`，不再需要第二套 FakeDisplay 生命周期。|
| `craft-engine-nms-helper*`、`craft-engine-s3` | CE 内部实现；项目没有手写的对应通用边界可替换。|
| `sparrow-nbt*` | 不新增独立依赖。家具语义数据使用 CE 26.8 已 relocation 并由 `FurniturePersistentData` 暴露的 NBT 类型；项目没有手写 NBT parser/codec 或 Component↔NBT 转换。再引入一份 Sparrow NBT 只会扩大 JAR。|
| `sparrow-metadata` | 没有跨插件 metadata store 需求；桌面状态必须留在 actor/持久化边界。|
| `sparrow-redis-message-broker` | 当前是单服权威 actor，没有 Redis 跨服消息契约；引入会制造另一条一致性路径。|
| `sparrow-reflection-proxy-scanner` | 镜头字段只有运行中 Paper remapper 才能确定，构建期扫描不能替代；当前预热后的 MethodHandle 已是无反射查找热路径。|
| `PluginConfiguration` 全量改用 Sparrow YAML | Paper 的 `saveDefaultConfig()`/`FileConfiguration` 已负责数据库、规则源、房间和 CE 不支持的镜头参数生命周期。CE 资产 ID、布局、容量、动画时序已移入资源包 descriptor，不再属于插件配置；重写剩余少量运维配置没有热路径收益。|
| `sparrow-util` | 实际包名位于 CraftEngine core，含内部/混淆实现，不是稳定第三方公共 API。|
| `custom-crops`、`custom-fishing`、`custom-nameplates` | 完整插件发行物，不是基础库。|

## 保留的专用实现

以下没有 Momirealms 等价库，或替换会破坏线程/领域边界：

- `FairRuleExecutor`、deadline scheduler、latest scene projector：每规则包公平性和 actor 截止时间语义；
- `PlayerRegionTaskScheduler`/`LatestTaskBuffer`：仅用于动态文字、BossBar 与镜头的 Folia 玩家实体调度和 latest-wins 有界合并；
- 规则 JAR 字节码策略、child-first class loader、签名 registry：安全边界；
- SQL migration/event store/rank query：Momirealms 仓库没有 SQL 库；
- `ClientCameraPacketSender`：CE 26.8 没有 camera packet proxy，Sparrow Reflection 的跨 loader MethodHandle 仍是最小实现；
- 场景差分、权威布局和动作标签宽度计算：纯 Java 热路径；最终几何、颜色、碰撞和静态文字由 CE 资源定义。

## 发布与许可

核心插件不再生成 fat JAR：最终 thin JAR 只合并本仓 Mahjong 模块，第三方 class 与 nested JAR 由 `verifyThinJar` 明确拒绝。数据库驱动、AntiGriefLib、Sparrow 与 ASM 通过 Paper `MavenLibraryResolver` 下载到服务端 `libraries/` 缓存；CraftEngine 仍由服主单独安装并通过必需依赖加入 classpath。新增库前仍必须确认它确实替代现有代码，避免运行时解析重复实现。Sparrow MiniMessage sources 标注 Adventure MIT；Sparrow Reflection/YAML 的既有 GPL 提示与可用许可文本继续打入 `META-INF/licenses`。
