# MahjongPaper 2.0

MahjongPaper 2.0 是一个面向 Paper/Folia 的麻将核心插件。核心不再包含任何具体麻将规则；日麻、MCR 和四川麻将由三个独立、签名、版本固定的规则包提供。

> 本项目不是 Mojang、Microsoft、Mahjong Soul 或任何麻将组织的官方产品。

## 当前状态

`2.0` 分支正在重构中，尚未发布稳定版。当前已经完成：

- 平台无关核心与规则 SDK 保持 Java 21 边界；Paper/CraftEngine 适配层和最终插件面向 Java 25，根项目不再编译旧生产源码。
- 每桌一个有界 mailbox 的 `TableActor`，单写者状态，不创建“每桌线程”。
- 三玩法公平共享的有界规则 CPU 池；单玩法最多占一半 worker。
- 内存先行、逐桌有序的 SQL outbox、事件日志、快照和恢复。
- 签名规则包的下载、校验、独立 classloader、版本固定、隔离失败和重启激活。
- 平台无关 `SceneGraph`、只保留最新帧的异步预计算，以及按 Folia region 公平应用的 CraftEngine 差分。
- CraftEngine 配置负责桌体、座椅、麻将牌姿态、碰撞、交互 hitbox、entity culling 和骰子槽位/face variant；Java 不重复实现这些资产行为，同一骰子只原地切 variant。
- 规则包声明骰点与开门位置，CE 播放固定槽位动画；插件主体拥有通用牌桌、凳子和牌，每个规则版本另发布独立的 CraftEngine 资源 ZIP 来提供本玩法的开门、摸打、吃碰杠和终局音效。插件只校验并交付给 CE，纯规则 JAR 不混装任何资源包内容。
- 暗手牌面只通过 Sparrow 客户端假实体发送给授权玩家，不创建含秘密信息的世界实体。
- 大厅可加入可恢复的通用机器人；掉线和 `/mahjong auto` 复用每桌唯一单次任务，日麻、MCR、四川的出牌策略分别由对应规则包实现。
- 房主可通过桌面动作或 `/mahjong owner <seat>` 原子转让给在线真人；命令、动作和反馈由客户端按简体中文、繁体中文、英文或日文渲染。

已经删除并由架构哨兵禁止重新引入：

- `MahjongTableSession`、`TableRoundController` 与混合玩法控制器；
- 根项目 Kotlin 生产规则与 `mahjong-utils`；
- GB JNI、CMake 和 vendored native 规则；
- Paper Display Entity 桌面渲染和全桌逐 tick 扫描；
- `LEGACY`、`SHADOW` 与运行时 fallback 模式。

## 运行结构

```text
Paper / CraftEngine event
          │ O(1) lookup + bounded offer
          ▼
      TableActor
          ├── signed RulePackProvider
          ├── PersistenceOutbox → SQL
          └── latest SceneGraph → CraftEngine region budget
```

核心模块：

| 模块 | 职责 |
| --- | --- |
| `mahjong-rule-spi` | Java-only 规则协议与版本化快照契约 |
| `mahjong-domain` | 平台无关桌、比赛与参与者模型 |
| `mahjong-application` | actor、mailbox、公平规则池、倒计时与 outbox |
| `mahjong-rule-runtime` | 官方签名规则包安装、验证、激活和 classloader |
| `mahjong-presentation` | `SceneGraph`、布局、公开/私有投影 |
| `mahjong-persistence-sql` | H2/MariaDB/MySQL 事件、快照与恢复 |
| `mahjong-platform-paper` | Paper/Folia 调度和世界锚点 |
| `mahjong-craftengine` | CraftEngine 官方 API 差分后端与客户端私有投影 |
| `mahjong-plugin` | 唯一生产装配入口与最终 fat JAR |

更完整的边界见 [2.0 架构](docs/architecture.zh-CN.md) 和 [并发模型](docs/concurrency.zh-CN.md)。

## 安装与命令

稳定版发布前请只在测试服使用。要求 Java 25、Paper/Folia 26.2 和 CraftEngine 26.7+。参见 [安装说明](docs/installation.zh-CN.md)。规则 SPI、TCK 与三个外部规则包仍保持 Java 21 字节码。

核心命令：

- `/mahjong create <riichi|mcr|sichuan> [profile]`
- `/mahjong join <table-id> <seat>`、`/mahjong ready`、`/mahjong start`
- `/mahjong owner <seat>`（兼容 `/mahjong transfer <seat>`）
- `/mahjong bot <add|remove> <seat>`
- `/mahjong auto <on|off>`
- `/mahjong list`
- `/mahjong state [table-id]`
- `/mahjong remove <table-id>`
- `/mahjong rules list`
- `/mahjong rules install <id> [version]`
- `/mahjong rules update <id> [version]`
- `/mahjong rules verify [id]`
- `/mahjong rules activate <id> <version>`
- `/mahjong rules gc`

规则别名：`richi` → `riichi`，`gb` → `mcr`。规则安装与激活不会热替换 classloader，必须完整重启后生效。

## 构建

仓库的真实编译门禁在 GitHub Actions。最终插件 CI 使用 Java 25 执行：

```text
./gradlew clean check :mahjong-plugin:shadowJar --no-daemon
```

最终 JAR 位于 `modules/mahjong-plugin/build/libs/`。CI 还会验证最终产物不包含高于 Java 25 的 classfile，并拒绝 native、旧控制器、Kotlin runtime 和具体规则实现进入核心 JAR。

三个规则仓及发布要求见 [规则包说明](docs/rule-packs.zh-CN.md)。
