# 安装（2.0 开发版）

当前 `2.0` 尚未发布稳定版，只建议测试服验证。

要求：

- Java 25；
- Paper 或 Folia 26.2；
- CraftEngine 26.7 及以上；
- 至少一个已安装、已验证并在重启后激活的官方规则包。

步骤：

1. 从 GitHub Actions 或正式 Release 获取 `mahjong-plugin` 的无分类器 fat JAR。
2. 放入 `plugins/`，同时安装 CraftEngine。
3. 首次启动会生成 `plugins/MahjongPaper/config.yml`，把插件主体的通用牌桌/凳子/牌资源原子安装到 `plugins/CraftEngine/resources/mahjongpaper`，并把已激活规则的独立资源 ZIP 作为完整 CE pack 安装到 `plugins/CraftEngine/resources/mahjongpaper-rule-<id>-<version>-<jar-sha-prefix>`。插件不另建资源加载路径。
4. 内容与已加载 bundle 完全相同时可直接恢复；首次安装或任一文件变化后执行 `/ce reload all`，插件只在安装完成后的 `CraftEngineReloadEvent` 恢复场景。
5. 新安装默认使用 [`rule-registry-v2026.08.10.1`](https://github.com/EllanServer/MahjongEngine/releases/tag/rule-registry-v2026.08.10.1) 的签名 registry；已有配置若仍为空，设置 `rules.registry-url` 为该 Release 的 `registry.json`。正式核心 Release 会内置对应的官方 Ed25519 公钥。
6. 用 `/mahjong rules install ...`、`verify`、`activate` 安装规则，然后完整重启。

家具、牌姿态、碰撞、座位、交互 hitbox 与按玩家 entity culling 均由 `craftengine/configuration/mahjong.yml` 管理；修改后必须重新构建核心 JAR，并执行 `/ce reload all`。Java 端没有另一套实体配置回退。

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
