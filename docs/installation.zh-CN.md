# 安装（2.0 开发版）

当前 `2.0` 尚未发布稳定版，只建议测试服验证。

要求：

- Java 21；
- Paper 或 Folia 1.20.1 及以上受支持版本；
- CraftEngine 26.7 及以上；
- 至少一个已安装、已验证并在重启后激活的官方规则包。

步骤：

1. 从 GitHub Actions 或正式 Release 获取 `mahjong-plugin` 的无分类器 fat JAR。
2. 放入 `plugins/`，同时安装 CraftEngine。
3. 首次启动会生成 `plugins/MahjongPaper/config.yml` 并把校验后的资源 bundle 原子安装到 `plugins/CraftEngine/resources/mahjongpaper`。
4. 若 CraftEngine 尚未读取新 bundle，执行 `/ce reload all`；插件只在 `CraftEngineReloadEvent` 后恢复场景。
5. 配置 `rules.registry-url` 和构建时内置的官方 Ed25519 公钥。
6. 用 `/mahjong rules install ...`、`verify`、`activate` 安装规则，然后完整重启。

默认数据库是插件目录内的 H2 文件。也可把 `database.jdbc-url` 改成 MariaDB/MySQL JDBC URL。数据库不可用时插件与管理命令仍可加载，但不会开始或推进可恢复比赛。

规则包目录：

```text
plugins/MahjongPaper/rules/
  staging/
  quarantine/
  registry-cache.json
  riichi/<version>/riichi-rule-pack.jar
  mcr/<version>/mcr-rule-pack.jar
  sichuan/<version>/sichuan-rule-pack.jar
```

规则升级、激活与回滚都要求重启；进行中的比赛始终绑定原来的版本和 SHA-256。
