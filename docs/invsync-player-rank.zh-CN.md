# InvSync 2.x 玩家段位接入

`backend=INVSYNC` 期间，MahjongPaper 将 InvSync 作为玩家段位 profile 载荷的唯一主数据源。接入只使用 InvSync 开发者文档公开的 `InvSyncAddonManager.register`、`event.readData(key)` 与 `event.putData(key, bytes)`；项目不声明或伪造付费插件的 Maven 坐标，也不打包其 API。

## 数据边界

- InvSync 已启用、可用且 API 兼容：载荷保留三个模式的 `MahjongSoulRankProfile` 槽位，但当前只有四名真人完成的 `RIICHI` 对局会从结算更新段位与个人统计缓存。`GB`、`SICHUAN` 槽位只能读取已有或一次性迁移的数据，不会由对应对局自动更新。
- MahjongPaper 数据库继续保存持久牌桌、`round_history`、`rank_history` 和排行榜投影。`player_rank_mode` 在此模式只是非权威投影，不能反向覆盖 InvSync。
- InvSync 缺失、未启用、启动探测不兼容或回调期发生 API 失败：启用回退且 SQL 后端已启用、健康时，改用现有 `DatabaseService` 段位实现；否则进入 `UNAVAILABLE`。日志会输出当前 `backend=INVSYNC`、`DATABASE_FALLBACK` 或 `UNAVAILABLE`。
- 公开离线 API 要求直接继承 InvSync jar 内的 `OfflineInvSyncAddon`。仓库没有合法 API jar，因此本接入不伪造离线读取；`/mahjong rank` 只读取当前在线玩家已同步的缓存。

## 部署要求

1. 安装与你的服务端兼容的 InvSync 2.x。`plugin.yml` 与 Paper/Folia 元数据将它声明为可选、`BEFORE` 依赖。
2. 在 InvSync 中同时开启 **auto-save（自动保存）** 与 **world-save（世界保存）**。MahjongPaper 只能在 InvSync 的 `onSave` 回调写入公开事件，公开 API 没有“立即强制保存在线插件数据”的方法。
3. 保留 MahjongPaper 的数据库：全局牌桌、`round_history`、`rank_history` 与排行榜投影仍由 SQL 管理。默认配置已经启用本地 H2，也可改用 MariaDB/MySQL。
4. 默认配置会启用 InvSync 探测与数据库回退：

```yaml
ranking:
  playerStorage:
    invSync:
      enabled: true
      fallbackToDatabase: true
```

需要强制停用 InvSync 时，将 `ranking.playerStorage.invSync.enabled` 设为 `false`；此时完整使用自托管数据库。将 `fallbackToDatabase` 设为 `false` 则会在 InvSync 不可用时让玩家段位后端进入 `UNAVAILABLE`，不建议生产环境这样配置。
数据库回退还要求 `database.enabled: true` 且连接初始化成功。默认 `database.failOnError: false` 会在数据库失败时继续启动并关闭持久化；要求段位与历史必须可写的生产环境应改为 `true`。
玩家存储后端会在插件启动时选定；修改上述 InvSync 开关或回退策略后需要完整重启服务器，`/mahjong reload` 不会替换已注册的 addon。

进程在一次段位更新后、下一次 InvSync 保存前异常崩溃时，公开 API 无法保证这段窗口内的在线缓存已经落盘；因此生产环境不应关闭 auto-save/world-save，并应合理缩短自动保存间隔。排行榜 SQL 投影可用于人工核对，但不会被当作权威数据自动反灌。

## 一次性迁移与安全性

- 玩家首次同步时，若 `mahjongpaper:rank-profile` 完全不存在，才从旧 `player_rank_mode` 导入一次；保存的版本化载荷包含 migration marker，之后不长期双写玩家主档。
- 若旧数据库临时读取失败，该次迁移进入只读失败状态，不会用默认值覆盖远端；玩家重新同步后可重试。
- 若 InvSync 中已有载荷但 magic、版本、长度、UUID、枚举或计数校验失败，会保留原始字节并拒绝覆盖，同时向玩家显示本地化错误、向控制台记录 UUID。
- 载荷最大 4096 字节，显示名最多 128 个 UTF-8 字节，包含 `MahjongSoulRankProfile` 的全部字段。

参考：[InvSync 开发者文档](https://halo.xbaimiao.com/archives/invsynckai-fa-zhe-wen-dang)。
