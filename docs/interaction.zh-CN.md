# 桌面交互与看牌契约

2.0 使用一套与玩法无关的交互手感。日麻、MCR、四川只通过规则包决定有哪些牌、牌的稳定顺序、当前合法动作以及动作优先级，不得各自实现点击器、镜头或桌面坐标。

## 手牌操作

- 第一次点击一张可出的手牌，只选择该牌；本人看到牌面上抬 `0.06` 格，不提交规则动作。
- 超过 40ms 后再次点击同一张牌，才把 revision-bound token 提交给该桌 actor。
- 40ms 内由同一次 CraftEngine 交互产生的重复事件会被丢弃，避免一次点击意外出牌。
- 潜行点击已选择的同一张牌会取消；潜行点击另一张牌仍切换选择，保持原有操作习惯。
- revision 更新、动作区重建、离桌或断线都会清除选择，旧 token 不可能作用于新状态。

选择路径只进行 O(1) 路由、玩家/桌级并发表更新和至多两个 CE `setVariant`；它不调用规则包、SQL、布局器，也不重建整张桌面。每张暗手只有一个持久 CE 家具：本人收到条件正面，其他玩家收到互斥条件的牌背；选中上抬由资源包的 `selected` variant 定义。

## 动作按钮

CraftEngine YAML 定义可复用的透明 hitbox、碰撞、家具与 culling。Java 只按规则包给出的 `ActionPlacement` 和稳定顺序选择通用槽位。内置无参数按钮文字是仅授权玩家可见的 CE `text_display` 条件家具，颜色、背景、billboard 和 normal/emphasized variant 都在资源包；只有带动态牌/花色参数及第三方未知键的文字使用最小逐玩家 packet 文本。

三种玩法共享同一套动作行和右侧固定槽，不存在日麻、MCR、四川三份按钮坐标代码。“看牌河”固定在动作行侧边，其命中区只接受对应座位玩家的 `(InteractionHandle, PlayerId)`。

## 俯视看牌

- 玩家必须仍坐在 CraftEngine 座椅内才能进入。
- 点击“看牌河”后，客户端镜头从眼睛位置平滑移动到桌面中心上方 `4.5` 格；默认过渡 16 tick。
- 俯视状态只读，座位动作标签会隐藏，任何牌局动作都被路由层拒绝。
- 按 Shift 返回本人实体镜头并保持座位；传送、死亡、离桌、断线、桌面关闭也会强制恢复或清理镜头。
- 镜头只使用 CE packet proxy 创建的客户端私有锚点和启动期预热、随后缓存的 Sparrow Reflection 发包句柄，不生成 Bukkit 世界实体。

镜头动画由玩家实体调度器注册有限个单次任务，没有全服或全桌逐 tick 扫描。一个玩家的镜头失败只关闭该玩家的俯视功能，不修改规则状态，也不阻塞其他桌。

## 配置边界

资产 ID、通用布局尺寸、容量、开门动画时序位于资源包 `assets/mahjongcraft/mahjong_presentation.properties`；模型、桌椅、牌背、点棒、hitbox、座位、条件、variant 和裁剪位于 `craftengine/configuration/mahjong.yml`。它们都随经过清单校验的 CE bundle 发布，不进入插件 `config.yml`。CE 26.8 尚无 camera packet API，因此 `presentation.overhead` 是唯一保留在插件配置中的表现边界。
