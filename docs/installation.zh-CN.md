# 安装（2.0 开发版）

当前 `2.0` 尚未发布稳定版，只建议测试服验证。

要求：

- Java 25；
- Paper 或 Folia 26.2；
- CraftEngine 26.8（内部 API 精确锁定；升级 CE 前必须先升级 MahjongPaper）；
- CraftEngine 保持默认的 `misc.delay-configuration-load: true`，以便依赖插件在资源解析前注册行为与条件；
- 至少一个已安装、已验证并在重启后激活的官方规则包。

步骤：

1. 从 GitHub Actions 或正式 Release 获取 `mahjong-plugin` 的无分类器 thin JAR。它只包含本仓 Mahjong 模块和 CE bundle；首次加载时 Paper loader 会从 Maven Central 镜像与 Momirealms releases 下载数据库驱动、AntiGriefLib、Sparrow 与 ASM 到服务端 `libraries/` 缓存，因此首次启动需要访问这两个仓库（离线服须预热该缓存）。
2. 放入 `plugins/`，同时安装 CraftEngine。
3. 首次启动会生成 `plugins/MahjongPaper/config.yml`，把插件主体的通用牌桌/凳子/牌资源原子安装到 `plugins/CraftEngine/resources/mahjongpaper`，并把已激活规则的独立资源 ZIP 作为完整 CE pack 安装到 `plugins/CraftEngine/resources/mahjongpaper-rule-<id>-<version>-<jar-sha-prefix>`。插件不另建资源加载路径。
4. 内容与已加载 bundle 完全相同时可直接恢复；首次安装或任一文件变化时，插件通过 CE 26.8 自身的 reload 生命周期重载配置、生成资源包并等待 `CraftEngineReloadEvent` 后恢复场景，不需要手动执行 `/ce reload all`。
5. 新安装默认使用 [`rule-registry-v2026.08.10.1`](https://github.com/EllanServer/MahjongEngine/releases/tag/rule-registry-v2026.08.10.1) 的签名 registry；已有配置若仍为空，设置 `rules.registry-url` 为该 Release 的 `registry.json`。正式核心 Release 会内置对应的官方 Ed25519 公钥。
6. 用 `/mahjong rules install ...`、`verify`、`activate` 安装规则，然后完整重启。

家具、牌姿态、碰撞、座位、交互 hitbox、条件元素与按玩家 entity culling 均由 `craftengine/configuration/mahjong.yml` 管理；共享资产 ID、布局尺寸/容量和开门时序位于资源包 `assets/mahjongcraft/mahjong_presentation.properties`。这些值不出现在插件 `config.yml`，修改后必须重新构建核心 JAR 并重启服务端（安装器随后触发 CE reload）；Java 端没有另一套实体配置回退。

默认数据库是插件目录内的 H2 文件。也可把 `database.jdbc-url` 改成 MariaDB/MySQL JDBC URL。数据库不可用时插件与管理命令仍可加载，但不会开始或推进可恢复比赛。

规则包目录：

```text
plugins/MahjongPaper/rules/
  staging/
  quarantine/
  registry-cache.json
  riichi/<version>/riichi-rule-pack.jar
  riichi/<version>/riichi-resource-pack.zip
  mcr/<version>/mcr-rule-pack.jar
  mcr/<version>/mcr-resource-pack.zip
  sichuan/<version>/sichuan-rule-pack.jar
  sichuan/<version>/sichuan-resource-pack.zip
```

`activate` 仍是重启生效的保守路径；`swap`、`deactivate` 与 `rollback` 可即时切换新开牌局及其规则资源。进行中的比赛始终绑定原来的规则 JAR 版本和 SHA-256。

正式 Release 工作流要求 GitHub Actions repository variable `MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64`，其值必须是 Ed25519 X.509 公钥 DER 的 Base64。变量缺失、格式错误或最终 JAR 内嵌值不一致都会直接阻止发布。签名私钥不得进入源码或构建日志；普通分支构建制品不等同于嵌入信任根的正式核心 Release。
