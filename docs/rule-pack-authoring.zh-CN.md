# 自定义规则包开发教程（SPI 1.6.0）

本教程说明怎样为 MahjongPaper 2.0 编写、测试和发布一个完整规则包。规则包不是“计分插件”：它必须拥有整场比赛的牌墙、发牌、合法动作、反应优先级、支付、局次推进、终局结果、公开/私有视图和可恢复快照。

> **先读这一条：当前原版核心不能直接安装任意第三方规则包。** 2.0 首版把 `riichi`、`mcr`、`sichuan` 作为硬编码信任边界，并且只接受由内嵌 Ed25519 公钥验证通过的签名 registry。你可以完全独立地开发并通过 TCK；要放进服务器运行，则需要向官方规则仓贡献，或维护自己的核心发行版、允许列表、公钥和签名 registry。不存在把任意 JAR 直接丢进 `plugins/MahjongPaper/rules/` 的绕过路径。

## 1. 最快的起点

不要从空项目猜接口。选择最接近的官方 Java 21 规则仓作为模板：

- [riichi-mahjong-java](https://github.com/EllanStudio/riichi-mahjong-java)
- [mahjong-mcr-java](https://github.com/EllanStudio/mahjong-mcr-java)
- [sichuan-mahjong-java](https://github.com/EllanStudio/sichuan-mahjong-java)

三个模板已经包含：

- Java 21 和 Gradle Wrapper；
- SPI/TCK 依赖边界；
- `ServiceLoader` 注册；
- 静态规则 descriptor；
- thin JAR 校验；
- 独立 CraftEngine 资源 ZIP；
- GitHub Actions CI/Release；
- 确定性、快照、重放和规则金标测试。

建议先复制一个仓，保留构建和发布脚本，只替换领域模型、provider、测试以及规则资源。

## 2. 获取 SPI 和 TCK

规则代码使用 Java 21。坐标是：

```text
top.ellan.mahjong:mahjong-rule-spi:1.6.0
top.ellan.mahjong:mahjong-rule-tck:1.6.0
```

最稳定的本地开发方法，是先从核心源码发布一个隔离 Maven 仓库：

```bash
git clone --branch 2.0 https://github.com/EllanStudio/MahjongEngine.git
cd MahjongEngine
./gradlew \
  :mahjong-rule-spi:publishAllPublicationsToTestRepository \
  :mahjong-rule-tck:publishAllPublicationsToTestRepository
```

产物位于 `MahjongEngine/build/rule-sdk-repository/`。规则仓通过属性使用它：

```bash
./gradlew clean check \
  -PmahjongRuleSdkRepo=/absolute/path/to/MahjongEngine/build/rule-sdk-repository
```

也可以使用带认证的 GitHub Packages，但 CI 最好像三个官方仓一样：检出固定的核心提交，现场发布 SPI/TCK，再构建规则包。这样不会意外跟随未发布接口。

## 3. 项目结构

```text
my-rule-pack/
├─ build.gradle
├─ settings.gradle
├─ gradle/rule-resources.gradle
└─ src/
   ├─ main/
   │  ├─ java/top/ellan/mahjong/rules/myrule/
   │  │  ├─ MyRulePackProvider.java
   │  │  ├─ MyRuleState.java
   │  │  ├─ MyActionCodec.java
   │  │  ├─ MySnapshotCodec.java
   │  │  └─ MyViewProjector.java
   │  ├─ resources/META-INF/
   │  │  ├─ mahjong-rule-pack.properties
   │  │  └─ services/top.ellan.mahjong.spi.RulePackProvider
   │  └─ rule-resources/
   │     ├─ META-INF/
   │     │  ├─ mahjong-rule-resources.properties
   │     │  ├─ mahjong-rule-sounds.properties
   │     │  └─ RESOURCEPACK_ATTRIBUTION.md
   │     └─ craftengine/
   │        ├─ pack.yml
   │        └─ resourcepack/assets/<versioned-namespace>/...
   └─ test/java/.../MyRulePackProviderTest.java
```

规则自己的 class 必须放在 `top.ellan.mahjong.rules/` 下。不要复制或打包 `top.ellan.mahjong.spi`，也不要把核心其他模块的 class 放进规则 JAR。

## 4. 最小 Gradle 边界

下面是核心部分；实际项目建议直接沿用官方模板中的完整校验任务：

```groovy
plugins {
    id 'java-library'
}

group = 'top.ellan.mahjong.rules'
version = '0.1.0'

ext.ruleResourceId = 'myrule'
ext.ruleSoundCueTypes = [
    'TILE_SHUFFLE',
    'TILE_DRAW',
    'TILE_DISCARD',
    'ROUND_WIN',
    'ROUND_DRAW',
    'TURN_CHANGE'
]
apply from: 'gradle/rule-resources.gradle'

repositories {
    def sdk = providers.gradleProperty('mahjongRuleSdkRepo')
    if (sdk.isPresent()) {
        maven { url = uri(sdk.get()) }
    }
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // 核心父 classloader 提供唯一 SPI，生产 JAR 绝不能打入它。
    compileOnly 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'

    testImplementation platform('org.junit:junit-bom:5.12.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'
    testImplementation 'top.ellan.mahjong:mahjong-rule-tck:1.6.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 21
    options.compilerArgs += ['-Xlint:all', '-Werror']
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
    maxHeapSize = '256m'
    maxParallelForks = 1
}

tasks.named('jar', Jar) {
    archiveBaseName = 'myrule-rule-pack'
    exclude 'assets/**', 'resourcepack/**', 'craftengine/**'
    manifest {
        attributes(
            'Mahjong-Rule-Id': 'myrule',
            'Mahjong-Rule-Version': project.version,
            'Mahjong-SPI-Version': '1.6.0',
            'Mahjong-State-Schema': '1'
        )
    }
}
```

规则 JAR 是 **thin、零第三方运行时依赖** 的 Java 21 JAR。不要使用 Shadow，不要打 nested JAR，也不要依赖 Bukkit、Paper、CraftEngine、JDBC 或其他插件 API。

## 5. 实现 `RulePackProvider`

`RulePackProvider` 是唯一运行入口。核心会串行调用同一场比赛，但不同比赛可能并发调用同一个 provider 实例，因此 provider 自身应无可变比赛状态。

| 方法 | 责任 |
|---|---|
| `descriptor()` | 返回稳定且不可变的 ID、版本、SPI、核心约束、状态 schema、profile 和资源声明。 |
| `createMatch(setup)` | 只根据 profile、128-bit seed、固定座位和配置创建确定性初态。 |
| `legalActions(state, actor)` | 返回该玩家此刻真正可执行的动作和表现；未入座 actor 必须返回空列表。 |
| `transition(state, actor, action)` | 验证并执行一个动作；接受时返回新不可变状态，拒绝时返回原状态实例且不产生事件。 |
| `scheduledAction(state)` | 可选；系统摸牌、超时、局间推进等确定性单次动作。返回时 actor 必须已入座，且同状态下 `transition` 必须接受该动作。 |
| `automatedAction(state, candidates)` | 可选；机器人/托管只能从核心给出的合法候选中选择。 |
| `matchResult(state)` | 非终局必须为空；一旦 transition 返回 `MATCH_ENDED`，就必须提供完整排名、分数与支付结果。 |
| `publicView(state, revision)` | 只输出所有观众都能看到的牌、属性和桌面事实。 |
| `privateView(state, viewer, revision)` | 只输出该 viewer 被授权看到的秘密；未入座 viewer 必须抛出 `IllegalArgumentException`。 |
| `snapshot(state, sequence)` | 输出版本化二进制快照和 payload SHA-256；禁止 Java 原生序列化。 |
| `restore(snapshot)` | 恢复出与快照前哈希、视图、合法动作一致的状态。 |
| `stateHash(state)` | 可选优化；默认使用 `snapshot(state, 0).sha256()`。 |

推荐拆分成五层：

1. 不可变领域状态和完整比赛状态机；
2. `RuleAction` 编解码器；
3. 公开/私有 view projector；
4. 二进制 snapshot codec；
5. 很薄的 `RulePackProvider` 适配器。

不要把整套规则塞进一个 provider 类。

### 状态和随机数

- `RuleState` 只是不可变状态 marker，不是 `Serializable`。
- 所有随机行为只能从 `MatchSetup.seed()` 派生；规则契约禁止读取任何系统时间，也禁止 `SecureRandom`、无种子的随机源或 `UUID.randomUUID()`。
- 相同 profile、配置、玩家、seed 和动作序列必须得到相同状态哈希、事件、视图与快照。
- 不得创建线程、定时器、并行 stream、锁、`ThreadLocal` 或后台任务。

这是 provider 的确定性契约，不只是当前 bytecode denylist。即使某个新 JDK 入口暂未被扫描器单独列出（例如 `Date`、`Calendar` 或 `java.util.random`），使用它读取时间或取得非 seed 随机数仍是不受支持的规则包。

### 动作

`RuleAction` 由稳定的 `type` 和最多 64 KiB 的 provider 私有 payload 组成。核心不解析 payload。

每个 `LegalAction` 还要带 `ActionPresentation`：

- 普通按钮：`ActionPresentation.actionRow("action.my_action")`；
- 次要按钮：`ActionPresentation.secondaryRow(...)`；
- 点击暗手牌：`ActionPresentation.handTile(labelKey, tileInstanceId)`。

`HAND_TILE` 的目标必须是该玩家 `PrivateRuleView` 中真实存在的 `TileInstanceId`，且同一 legal-action frame 中两个手牌动作不能指向同一物理牌。规则包只声明语义和标准化牌面，不提交世界坐标、模型矩阵或 CE entity。

### transition

拒绝动作必须这样返回：

```java
return RuleTransition.rejected(currentState, "invalid_action");
```

这里的 `currentState` 必须是传入的同一个实例。接受动作必须返回新状态、稳定 reason code 和规范事件；终局转换使用 `TransitionDisposition.MATCH_ENDED`，局结束但比赛未结束使用 `ROUND_ENDED`。

### 快照

自己定义稳定二进制格式，例如显式写入：

```text
magic | schema | match metadata | wall | hands | discards | melds | scores | pending reaction
```

要求：

- payload 最大 8 MiB；
- `sequence` 是核心分配的外部序号，不得改变相同状态的 payload；
- 保存物理牌实例、未决反应和所有影响未来结果的数据；
- schema 变化时递增 `stateSchemaVersion` 并保留明确迁移策略；
- `restore(snapshot(state))` 必须恢复相同 state hash、view、合法动作和调度动作。

## 6. 两个必须完全一致的 descriptor

### JAR 静态 descriptor

`src/main/resources/META-INF/mahjong-rule-pack.properties`：

```properties
id=myrule
version=0.1.0
spiVersion=1.6.0
requiredCoreVersion=>=2.0.0
stateSchemaVersion=1
requiredResources=
```

字段集合必须恰好是以上六项。`requiredResources` 指规则 **JAR 内部** 必须存在的逻辑资源路径，不是旁边的 CE 资源 ZIP。

### provider descriptor

`descriptor()` 的 ID、版本、SPI、核心约束、状态 schema 和 `requiredResources` 必须匹配 JAR 静态 descriptor。签名 registry 另行认证 ID、版本、SPI/核心约束，以及 JAR/资源 ZIP 的 URL、长度和 SHA-256；它不包含 profile、状态 schema 或 `requiredResources`。

```java
private static final RulePackDescriptor DESCRIPTOR = new RulePackDescriptor(
        new RuleId("myrule"),
        "0.1.0",
        SpiVersion.CURRENT,
        ">=2.0.0",
        1,
        List.of(new RuleProfileDescriptor(
                new ProfileId("default"),
                "My Rule - default",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")),
        Set.of());
```

profile 配置必须通过 descriptor 中的 JSON schema 描述；`createMatch` 仍要执行领域级校验，不能信任外部字符串。

### ServiceLoader

文件：

```text
src/main/resources/META-INF/services/top.ellan.mahjong.spi.RulePackProvider
```

内容只能指定一个 provider：

```text
top.ellan.mahjong.rules.myrule.MyRulePackProvider
```

运行时要求恰好发现一个实现。

## 7. 使用 TCK

准备一个初始状态至少有一个合法动作的确定性 fixture，并提供一个保证会被拒绝的动作：

```java
@Test
void providerPassesTck() {
    List<MatchPlayer> players = IntStream.range(0, 4)
            .mapToObj(index -> new MatchPlayer(
                    new PlayerId(new UUID(0L, index + 1L)),
                    new SeatId(index)))
            .toList();
    MatchSetup setup = new MatchSetup(
            new ProfileId("default"),
            new MatchSeed(0x0123_4567_89ab_cdefL, 0xfedc_ba98_7654_3210L),
            players,
            Map.of());

    RulePackTckReport report = RulePackTck.verify(
            new MyRulePackProvider(),
            new RulePackTckCase(
                    setup,
                    Map.of(
                            players.getFirst().playerId(),
                            new RuleAction("invalid", new byte[0]))));

    assertEquals(4, report.playersVerified());
    assertTrue(report.legalActionsVerified() > 0);
    assertTrue(report.snapshotsVerified() > 0);
}
```

TCK 会检查确定性、拒绝保持同一状态实例、快照 SHA-256/恢复、private view 的 viewer/seat 标签、未入座 outsider 拒绝、牌实例唯一性、墙槽稳定性、合法动作表现绑定、scheduled/automated action 和有界输出。它不会判断玩家 A 的秘密内容是否误放进玩家 B 的 view；规则仓必须另写跨座位暗手泄露测试。TCK 通过只是最低协议门槛，你仍需为计分、支付、反应优先级、终局条件和历史争议案例建立规则金标测试。

## 8. 独立的 CraftEngine 资源 ZIP

当前发布格式要求同一版本同时提供：

```text
myrule-rule-pack-0.1.0.jar
myrule-resource-pack-0.1.0.zip
```

规则 JAR 只含代码；声音等玩法专属表现只放在资源 ZIP。共享桌体、椅子、牌模型、牌背、布局和交互家具由核心 bundle 提供，不要在每个规则包里复制。

资源 descriptor：

```properties
# META-INF/mahjong-rule-resources.properties
format=1
id=myrule
version=0.1.0
```

`craftengine/pack.yml` 必须使用版本隔离命名空间：

```yaml
namespace: mahjong_myrule_v0_1_0
```

`META-INF/mahjong-rule-sounds.properties` 把规则 cue 映射到该命名空间：

```properties
cue.TILE_SHUFFLE=mahjong_myrule_v0_1_0:tile_shuffle,1.0,1.0
cue.TILE_DRAW=mahjong_myrule_v0_1_0:tile_draw,1.0,1.0
cue.TILE_DISCARD=mahjong_myrule_v0_1_0:tile_discard,1.0,1.0
cue.ROUND_WIN=mahjong_myrule_v0_1_0:round_win,1.0,1.0
cue.ROUND_DRAW=mahjong_myrule_v0_1_0:round_draw,1.0,1.0
cue.TURN_CHANGE=mahjong_myrule_v0_1_0:turn_change,1.0,1.0
opening.dice=mahjong_myrule_v0_1_0:dice,1.0,1.0
opening.wall=mahjong_myrule_v0_1_0:wall,1.0,1.0
```

`ruleSoundCueTypes`、`cue.*` 属性和 provider 实际会发出的 cue 必须同步增删；`opening.dice` 与 `opening.wall` 则始终必填。复制官方 `gradle/rule-resources.gradle` 后，构建会生成并校验 `_bundle_index.txt`、`_bundle_manifest.sha256`、`sounds.json` 和 `.ogg` 的一一对应关系，并在构建期拒绝 `.class`、服务注册和核心包路径。生产运行时还会拒绝 `.jar`、`.java` 和任何不在允许元数据/`craftengine/` 边界内的内容。

## 9. 运行时安全边界

规则 classloader 会扫描并插桩 bytecode。加载期明确拒绝：

- Bukkit、Paper、CraftEngine、核心内部 API，以及 `top.ellan.mahjong.rules/`/SPI/允许 JDK 边界之外的类型；
- 文件、网络、channel、进程、JDBC、native method；
- 反射、ClassLoader、ServiceLoader、模块、management/JMX；
- 线程、锁、monitor、`ThreadLocal`、timer、并行 stream；
- logging、XML、scripting、sound/image/UI 等宽泛 JDK 子系统；
- `System`（仅 `arraycopy` 例外）、direct/off-heap buffer、超过四维的数组；
- 规则代码直接调用宿主 `RuleExecutionBudget`；
- 打包 SPI、核心 class、多版本 JAR 或表现资源。

扫描器还阻止 `java.time.*.now/system*`、无参 `Random`/`SplittableRandom`、`Math.random`、无随机源的 `Collections.shuffle`、`UUID.randomUUID` 和 `SecureRandom` 等常见入口，但它不是“确定性证明器”。上一节的契约更严格：任何 wall clock 或未从 `MatchSetup.seed()` 派生的随机入口都禁止使用。

规则方法运行在有界执行预算内。长循环应保持算法有界；不要依靠缓存、全局单例或外部状态维持正确性。

## 10. 构建和发布前检查

```bash
./gradlew clean check assemble \
  -PmahjongRuleSdkRepo=/absolute/path/to/MahjongEngine/build/rule-sdk-repository
```

确认：

- `build/libs/myrule-rule-pack-0.1.0.jar` 是 Java 21 thin JAR；
- JAR 内没有 `top/ellan/mahjong/spi/`、第三方 class、图片、音频或 nested JAR；
- `build/distributions/myrule-resource-pack-0.1.0.zip` 没有可执行代码；
- descriptor、provider 和版本 tag 完全一致；
- TCK、规则金标、快照重放和 ServiceLoader 测试全部通过；
- Release 同时上传规则 JAR、资源 ZIP 和包含大小/SHA-256/URL 的 `registry-entry.json`。

## 11. 怎样让自定义包在服务器运行

### 原版 2.0

不能直接运行任意 ID。原版只信任：

```text
riichi
mcr
sichuan
```

即使你生成了合法 JAR、资源 ZIP 和自己的签名 registry，原版仍会在 registry 解码、安装、激活、路径解析和恢复阶段拒绝第四个 ID。不要冒用已有 ID；这会破坏存档身份、排行榜和规则来源审计。

### 自己维护发行版

需要同时完成：

1. 在核心 fork 中扩展 `OfficialRuleIds`；
2. 为新 ID 增加创建命令、默认 profile、排行榜/机器人测试入口及必要的共享牌面映射；
3. 修改 `.github/workflows/rule-registry.yml`，收集并校验你的规则仓；
4. 生成 Ed25519 密钥，把 **X.509 SubjectPublicKeyInfo DER 的 Base64**（不是 raw 32-byte、OpenSSH 或 PEM 文本）写入核心构建变量 `MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64`；
5. 把 **PKCS#8 DER 的 Base64** 私钥仅保存为发布环境 secret `MAHJONG_RULE_PACK_ED25519_PRIVATE_KEY_PKCS8_BASE64`；
6. 发布 HTTPS 规则 JAR、资源 ZIP、registry entry 和最终签名 `registry.json`；
7. 让服务器 `rules.registry-url` 指向你的 registry；
8. 用 `/mahjong rules install`、`verify`、`activate` 安装，并完整重启。

私钥绝不能提交到 Git、JAR、Actions artifact 或日志。核心仓只包含验证和发布流水线，不提供运行时跳过签名的开发模式。

## 12. 发布检查表

- [ ] Java 21，SPI 1.6.0，核心约束正确。
- [ ] provider 无跨比赛可变状态，所有状态不可变且确定。
- [ ] 拒绝动作返回同一状态实例、零事件、零 cue。
- [ ] 公开视图不泄露暗手；私有视图严格绑定 viewer。
- [ ] snapshot 覆盖完整未来状态并能确定性恢复。
- [ ] TCK、金标和重放测试全部通过。
- [ ] JAR 零第三方运行时依赖，不含 SPI/核心/表现资源。
- [ ] 资源 ZIP 使用版本隔离 CE namespace 并带完整归属声明。
- [ ] JAR、ZIP、descriptor、tag 和 registry entry 版本一致。
- [ ] registry 使用发布环境私钥签名，核心只嵌入公钥。
