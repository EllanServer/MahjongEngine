# MahjongPaper 2.0

[English](README.md) · [安装细节](docs/installation.zh-CN.md) · [自定义规则包教程](docs/rule-pack-authoring.zh-CN.md) · [架构](docs/architecture.zh-CN.md)

[![Build 2.0](https://github.com/EllanStudio/MahjongEngine/actions/workflows/build.yml/badge.svg?branch=2.0)](https://github.com/EllanStudio/MahjongEngine/actions/workflows/build.yml)

MahjongPaper 是一个面向 Paper/Folia 的 CraftEngine 麻将核心。核心负责牌桌、并发、权限、持久化和表现；具体麻将规则由独立、签名并固定版本的规则包提供。

目前支持三类规则身份：

- `riichi`：日麻；
- `mcr`：国标麻将（MCR）；
- `sichuan`：四川麻将。

> **开发状态：** `2.0` 是当前主分支，但尚未发布稳定核心版本。请先在测试服使用。项目与 Mojang、Microsoft、Mahjong Soul 及任何麻将组织无关。

## 1. 它是怎样工作的

```text
玩家点击 CE 家具或执行命令
              │
              ▼
       有界 TableActor
       ├─ 固定版本的 RulePackProvider
       ├─ SQL event/snapshot/outbox
       └─ 最新 SceneGraph
              │
              ▼
 CraftEngine 家具、座椅、资源包与客户端表现
```

- 一张桌只有一个单写者 actor，不会“每桌一个线程”。
- 规则包只处理规则，不访问 Bukkit、文件、网络、时钟或线程。
- CraftEngine 26.8 负责家具放置、持久恢复、区块加载、实体索引、座椅、交互入口、条件元素、模型和资源包生成。
- 暗手默认关闭：只有被授权玩家能收到正面条件元素，观众表缺失时不会泄露。
- 每场比赛固定规则包版本、JAR SHA-256、profile、配置、seed 和状态 schema；重启恢复后不会偷偷切换规则。
- 插件是 thin JAR；HikariCP、数据库驱动、AntiGriefLib、Sparrow 和 ASM 由 Paper loader 下载到服务端 `libraries/` 缓存。

## 2. 服主安装教程

### 2.1 环境要求

| 项目 | 要求 |
|---|---|
| Java | **25** |
| 服务端 | Paper/Folia **26.2** |
| CraftEngine | **26.8 API 线**：major/minor 必须是 26.8；允许 26.8.x 或构建后缀，不接受 26.9 |
| 客户端 | 必须接受 CraftEngine 生成的服务器资源包 |
| 网络 | 首次启动需访问 Maven Central 镜像与 `repo.momirealms.net` |

CraftEngine 必须保留：

```yaml
misc:
  delay-configuration-load: true
```

MahjongPaper 会在 CE 延迟解析资源前注册自定义 behavior/condition。关闭它会导致资源配置无法解析。

### 2.2 下载和放置

1. 安装 CraftEngine 26.8。
2. 从 [MahjongEngine Releases](https://github.com/EllanStudio/MahjongEngine/releases) 下载无分类器的 `mahjong-plugin-<version>.jar`。
3. 将 JAR 放进服务端 `plugins/`。
4. 启动服务端，等待 Paper 下载 runtime libraries，等待 MahjongPaper 安装 CE bundle 并触发 CE reload/资源包生成。
5. 完全停止服务端，再编辑配置。

> 普通分支的 Actions artifact 主要用于 CI 验证，可能没有正式规则信任根。实际开服应使用正式 Release；自行构建时必须嵌入与你的签名 registry 匹配的 Ed25519 公钥。

运行过程中会按需出现以下目录；并非所有条目都在首次启动生成，注释标出了关键的条件目录：

```text
plugins/
├─ MahjongPaper/
│  ├─ config.yml
│  ├─ data/                     # 默认 H2 数据库
│  ├─ game-rooms.yml            # 首次保存房间后
│  └─ rules/
│     ├─ staging/
│     ├─ quarantine/
│     ├─ registry-cache.json    # 获取/安装规则后
│     ├─ riichi/<version>/      # 安装对应版本后
│     ├─ mcr/<version>/
│     └─ sichuan/<version>/
└─ CraftEngine/resources/
   ├─ mahjongpaper/
   └─ mahjongpaper-rule-<id>-<version>-<sha-prefix>/  # 激活规则启动后
```

不要手工修改已安装 bundle 中的生成清单。启动恢复时，受管文件变化会被原子替换，并通过 CE 自身的 reload 与 `PackManager` 生效；运行中切换规则若日志提示资源 reload，完整重启是最安全路径。

### 2.3 配置数据库和房间

默认 `database.jdbc-url` 为空，使用 `plugins/MahjongPaper/data` 下的 H2 文件，适合测试服：

```yaml
database:
  jdbc-url: ""
  username: "sa"
  password: ""
  maximum-pool-size: 8
```

MariaDB 示例：

```yaml
database:
  jdbc-url: "jdbc:mariadb://127.0.0.1:3306/mahjong"
  username: "mahjong"
  password: "change-me"
  maximum-pool-size: 8
```

数据库不可用时，插件和管理命令仍能加载，但不会开始或推进需要恢复保证的比赛。

默认启用游戏房间，并限制新牌桌必须完整位于房间内：

```yaml
game-rooms:
  enabled: true
  restrict-new-tables: true
  enter-exit-messages: true
  leave-countdown-seconds: 60
  default-radius: 10
  default-height: 8
  file: "game-rooms.yml"
```

其余可调运行项：

| 配置 | 说明 |
|---|---|
| `database.maximum-pool-size` | SQL 连接池大小，范围 2–32。 |
| `rules.registry-url` | Ed25519 签名 registry 的 HTTPS 地址；正式发行默认值通常无需修改。 |
| `ranking.enabled` | 是否累计段位分；关闭后仍保留比赛和得分历史。 |
| `ranking.east-room` / `south-room` | 东风/南风场段位房级别：`BRONZE`、`SILVER`、`GOLD`、`JADE` 或 `THRONE`。 |
| `game-rooms.default-radius` / `default-height` | 未使用 wand 选区时，以玩家为中心创建房间的半径/高度；范围分别为 3–127、4–128。 |
| `game-rooms.file` | 房间索引文件名；仅允许安全文件名 token。 |
| `craftengine.bundle-folder` | CE 主 bundle 安装文件夹名，不是资产/几何配置入口。 |
| `presentation.overhead.enabled` | 是否提供只读俯视视角。 |
| `presentation.overhead.height` | 俯视高度，范围 2–8 格。 |
| `presentation.overhead.transition-ticks` | 进入/退出视角过渡，范围 1–40 tick。 |

如果不需要房间限制，可以把 `restrict-new-tables` 改为 `false`。其余 CE 资产、几何、牌墙容量、动画、碰撞和 variant 不在 `config.yml` 中配置。

### 2.4 安装并激活规则包

在游戏内或控制台用管理员权限执行：

```text
/mahjong rules install riichi
/mahjong rules install mcr
/mahjong rules install sichuan
/mahjong rules verify
/mahjong rules list
```

不写版本时安装签名 registry 中该 ID 的最大版本坐标；官方 registry 只收集通过发布校验的稳定 Release。`rules list` 会显示实际版本，然后为需要的规则排队激活：

```text
/mahjong rules activate riichi <version>
/mahjong rules activate mcr <version>
/mahjong rules activate sichuan <version>
```

`activate` 为了 classloader 安全会在**下一次完整 JVM 启动**时生效；执行后完整停止并重启服务端，再运行：

```text
/mahjong rules list
```

确认版本带有 `[active]`。不要用 `/reload`、插件热卸载器或直接覆盖正在使用的规则 JAR。

已有服务器更新规则时可以使用：

- `/mahjong rules update <id> [version]`：下载并校验；
- `/mahjong rules swap <id> <version>`：新开比赛立即使用新版本，进行中的比赛继续旧版本；
- `/mahjong rules rollback <id>`：回到上一版本；
- `/mahjong rules deactivate <id>`：停止给新比赛分配该规则；
- `/mahjong rules gc`：隔离不再需要的版本。

### 2.5 创建游戏房间

默认配置下，不先创建房间就不能摆桌。管理员：

```text
/mahjong room wand
```

推荐拿到选择工具后，用左键和右键选定房间两个角，再执行；如果没有完整的两点选区，`create` 会按 `default-radius`/`default-height` 以玩家当前位置创建默认房间：

```text
/mahjong room create lobby-1 主大厅
/mahjong room info lobby-1
/mahjong room list
```

完整的 `7 × 4 × 7` 牌桌占地必须位于房间和允许建造的领地中。删除和重载：

```text
/mahjong room delete lobby-1
/mahjong ops reload-rooms
```

## 3. 从摆桌到开局

### 3.1 创建牌桌

玩家站在预期桌子中心位置执行：

```text
/mahjong create riichi
```

也可以指定玩法和 profile：

```text
/mahjong create riichi mahjong-soul
/mahjong create mcr green-book
/mahjong create sichuan t-tfmj-01-2024
```

创建者也是大厅所有者，但仍需点击一把 CE 椅子入座。创建失败时按提示检查：游戏房间、7×4×7 空间、层高、附近已有牌桌以及领地保护。

### 3.2 加入、观战和准备

推荐直接点击四把椅子。命令回退：

```text
/mahjong join <table-id> [east|south|west|north]
/mahjong spectate <table-id>
/mahjong unspectate
/mahjong leave
```

开局前可以：

```text
/mahjong mode <riichi|mcr|sichuan> [profile]
/mahjong bot add <east|south|west|north>
/mahjong bot remove <east|south|west|north>
/mahjong owner <east|south|west|north>
```

每名真人点击桌面 Ready 动作或执行：

```text
/mahjong ready
```

四个座位都已占用并准备后，所有者点击 Start 或执行：

```text
/mahjong start
```

管理员快速验证可以创建四 Bot 对局：

```text
/mahjong botmatch MAJSOUL_HANCHAN
/mahjong botmatch GB
/mahjong botmatch SICHUAN
```

### 3.3 对局操作

正常操作优先使用桌面上只对当前玩家显示的 CE 动作家具和手牌点击：

- 点击手牌选择或直接打出；
- 使用吃、碰、杠、和、跳过等动作按钮；
- 点击牌河视角家具进入只读俯视视角；桌面控制面板用于规则、托管、离桌和结算；
- 隐藏信息只发送给对应玩家。

如果资源包或交互暂时不可用，可使用命令回退：

```text
/mahjong tsumo
/mahjong ron
/mahjong pon
/mahjong minkan
/mahjong chii <tileA> <tileB>
/mahjong kan <tile>
/mahjong skip
/mahjong riichi <hand-index>
/mahjong kyuushu
```

命令只是回退入口，仍会检查同一个 revision-bound 动作令牌；过期或当前规则不允许的动作会被拒绝。

掉线托管和手动托管共用同一规则包 AI 路径：

```text
/mahjong auto on
/mahjong auto off
```

## 4. 常用命令

普通玩家默认拥有 `mahjongpaper.command`，管理员命令需要 `mahjongpaper.admin`（默认 OP）。游戏内可执行 `/mahjong help [page]` 浏览内置分页帮助；下表列出本教程使用的准确形式。

| 命令 | 用途 |
|---|---|
| `/mahjong create [rule] [profile]` | 在当前位置创建等待大厅和物理牌桌。 |
| `/mahjong join <table-id> [seat]` | 命令方式入座。 |
| `/mahjong table [table-id]` | 打开桌面控制面板。 |
| `/mahjong rule [summary]` | 打开规则设置/摘要。 |
| `/mahjong ready`、`/mahjong start` | 准备和开局。 |
| `/mahjong list`、`/mahjong state [table-id]` | 查看自己所在桌和当前状态。 |
| `/mahjong history [page]` | 查看个人历史。 |
| `/mahjong rank [riichi|mcr|sichuan] [page]` | 查看排行榜。 |
| `/mahjong settlement [table-id]` | 打开结算界面。 |
| `/mahjong remove <table-id>` | 删除自己的等待桌；管理员可删除任意桌。 |
| `/mahjong rules ...` | 规则包安装、校验、激活和回滚。 |
| `/mahjong room ...` | 游戏房间管理。 |
| `/mahjong ops status <table-id>` | 查看运行状态。 |
| `/mahjong ops force-end <table-id>` | 管理员强制结束异常比赛。 |
| `/mahjong reload` | 重读游戏房间索引；核心配置与规则激活仍需完整重启。 |

规则别名：`richi → riichi`、`gb → mcr`、`MAJSOUL_HANCHAN/MAJSOUL_TONPUU → riichi`。

## 5. 备份、恢复和升级

- 备份数据库以及整个 `plugins/MahjongPaper/`，不要只备份规则 JAR。
- 运行中的比赛固定规则版本；不要删除仍被快照引用的版本。
- H2 备份前应正常停服；外部数据库使用数据库自身的一致性备份工具。
- 升级核心前先读 Release Notes，并确认仍然要求 CraftEngine 26.8。
- CraftEngine 内部 API 被精确锁定；不要单独升级 CE 后继续运行旧核心。
- 遇到恢复失败先执行 `/mahjong rules verify` 和 `/mahjong ops status <table-id>`，保留日志、数据库和被隔离的规则包。

## 6. 常见问题

### 插件提示 CraftEngine 版本不兼容

只能使用 26.8 API 线，并确认 `misc.delay-configuration-load: true`。不要通过修改版本字符串绕过检查。

### 第一次启动缺少类或下载失败

thin JAR 需要 Paper loader 下载 runtime libraries。允许访问 Maven Central 镜像和 `https://repo.momirealms.net/releases/`，或在有网络的同版本测试服预热 `libraries/` 缓存。

### 规则安装了但不能创建桌

依次检查：

1. `/mahjong rules verify <id>` 是否为 valid；
2. `/mahjong rules list` 是否显示 `[active]`；
3. `activate` 后是否完整重启 JVM；
4. 正式核心是否内嵌了与 registry 匹配的公钥。

### 模型、文字或声音不可见

确认 CraftEngine reload/pack generation 已完成、服务端资源包已分发、客户端接受资源包，并检查 CE 日志。MahjongPaper 没有另一套 Display Entity 回退渲染器。

### 无法创建牌桌

默认必须在管理员定义的游戏房间内，并满足空间、层高、邻桌距离和领地保护检查。用 `/mahjong room info` 和创建失败提示定位。

## 7. 自己编写规则包

完整教程见：[自定义规则包开发教程（SPI 1.6.0）](docs/rule-pack-authoring.zh-CN.md)。

### 7.1 当前限制

原版 2.0 只接受 `riichi`、`mcr`、`sichuan` 三个签名身份。你可以开发和运行 TCK，但 arbitrary ID 不能直接安装到原版服务器。要上线自定义玩法，需要：

- 向对应官方规则仓贡献；或
- fork 核心，扩展规则 ID 允许列表和命令/profile 映射，嵌入自己的 Ed25519 公钥，并维护自己的签名 registry。

不要把无关玩法伪装成已有 ID。

### 7.2 发布本地 SDK

```bash
./gradlew \
  :mahjong-rule-spi:publishAllPublicationsToTestRepository \
  :mahjong-rule-tck:publishAllPublicationsToTestRepository
```

规则项目使用：

```groovy
compileOnly 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'

testImplementation platform('org.junit:junit-bom:5.12.2')
testImplementation 'org.junit.jupiter:junit-jupiter'
testImplementation 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'
testImplementation 'top.ellan.mahjong:mahjong-rule-tck:1.6.0'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

tasks.withType(Test).configureEach { useJUnitPlatform() }
```

生产规则 JAR 必须是 Java 21 thin JAR，不打入 SPI，不包含任何第三方运行时依赖。

### 7.3 Provider 必须完成什么

实现 `RulePackProvider`：

- `descriptor`、`createMatch`；
- `legalActions`、`transition`；
- `publicView`、严格按 viewer 隔离的 `privateView`；
- `snapshot`、`restore`、稳定 state hash；
- 可选的系统调度与机器人/托管；transition 返回 `MATCH_ENDED` 时必须提供终局结果。

同一个 seed 和动作序列必须完全确定；拒绝动作必须返回原状态实例且不产生事件。规则契约禁止读取平台、文件、网络和任何系统时钟，也禁止创建线程或使用 native/JDBC/反射；不能把某个暂未列入 bytecode denylist 的 JDK 入口当成绕过方式。

### 7.4 注册和测试

JAR 至少包含：

```text
META-INF/mahjong-rule-pack.properties
META-INF/services/top.ellan.mahjong.spi.RulePackProvider
```

再用 TCK 验证：

```java
RulePackTckReport report = RulePackTck.verify(
        provider,
        new RulePackTckCase(setup, rejectedActions));
```

每个版本需要同时发布纯规则 JAR 和独立 CraftEngine 资源 ZIP，再由签名 registry 绑定 URL、长度和 SHA-256。直接复制三个官方仓的 Gradle、TCK 和 Release 流水线是最安全的起点。

## 8. 构建核心

需要 JDK 25：

```bash
./gradlew clean check :mahjong-plugin:jar :mahjong-plugin:verifyThinJar --no-daemon
```

输出：

```text
modules/mahjong-plugin/build/libs/mahjong-plugin-<version>.jar
```

`verifyThinJar` 会拒绝非 `top/ellan/mahjong/` class 和 nested JAR，并确认 Paper loader、runtime catalog 等必要条目存在；`check` 任务另行执行模块/架构测试。正式发行还必须通过构建变量嵌入规则 registry 公钥。

## 9. 更多文档

- [安装与目录说明](docs/installation.zh-CN.md)
- [自定义规则包开发](docs/rule-pack-authoring.zh-CN.md)
- [规则 SPI/TCK](docs/rule-sdk.zh-CN.md)
- [官方规则包发布](docs/rule-packs.zh-CN.md)
- [架构和模块边界](docs/architecture.zh-CN.md)
- [并发模型](docs/concurrency.zh-CN.md)
- [交互契约](docs/interaction.zh-CN.md)
- [性能基线](docs/performance-notes.zh-CN.md)
- [Momirealms 库采用审计](docs/momirealms-libraries.zh-CN.md)

## 10. 许可证

核心源码使用根目录 MIT 许可证。CraftEngine、数据库驱动、Sparrow、规则包和资源素材保留各自许可证；详见 `THIRD_PARTY_NOTICES.md` 与 `resourcepack/ATTRIBUTION.md`。
