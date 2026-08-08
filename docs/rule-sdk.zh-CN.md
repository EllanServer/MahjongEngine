# 规则包 SDK 与发布边界

`mahjong-rule-spi` 是核心插件与三个官方规则包共享的 Java 21 父类加载器契约，
`mahjong-rule-tck` 是规则包发布前必须执行的兼容性检查工具。当前 SDK 版本为
`1.0.0`：

```kotlin
dependencies {
    compileOnly("top.ellan.mahjong:mahjong-rule-spi:1.0.0")
    testImplementation("top.ellan.mahjong:mahjong-rule-tck:1.0.0")
}
```

规则包的 fat JAR 必须排除以上两个制品。核心通过父类加载器提供 SPI；把 SPI 类重复打包会被
`RulePackLoader` 拒绝，以防止类型身份分裂。SPI 和 TCK 的 `jdeps` 结果均只有
`java.base`，不引入 Bukkit、CraftEngine、JDBC、Kotlin 或具体玩法类型。

## 规则包的固定入口

每个规则包 JAR 必须同时包含：

- `META-INF/services/top.ellan.mahjong.spi.RulePackProvider`，且只声明一个 provider；
- `META-INF/mahjong-rule-pack.properties`；
- manifest 中 `requiredResources` 声明的全部资源。

manifest 只接受以下六个字段，额外或缺失字段都会使安装失败：

```properties
id=sichuan
version=1.0.0
spiVersion=1.0.0
requiredCoreVersion=>=2.0.0
stateSchemaVersion=1
requiredResources=
```

`id` 只能是 `riichi`、`mcr` 或 `sichuan`。provider 的描述符、JAR manifest、签名
registry 与实际制品哈希必须一致。规则包不得访问平台 API、文件、网络、系统时钟，不得自行
创建线程；同一场比赛的调用由核心串行化，不同比赛可被并发调用。

## 本地验证

在主仓运行：

```powershell
.\gradlew.bat `
  :mahjong-rule-spi:check `
  :mahjong-rule-tck:check `
  :mahjong-rule-spi:publishAllPublicationsToTestRepository `
  :mahjong-rule-tck:publishAllPublicationsToTestRepository
```

候选 Maven 仓库生成在 `build/rule-sdk-repository`。每个官方规则仓还必须调用
`RulePackTck.verify(...)`，覆盖确定性种子、状态不可变、非法动作零副作用、公开/私有视图、
快照恢复与重放。

## 发布规则

普通 `2.0` 分支推送和 PR 只运行验证并上传候选制品，不会写入包仓库。正式 SDK 只由
不可变标签 `rule-spi-v1.0.0` 触发并发布到 EllanServer 的 GitHub Packages；创建该标签前
必须确认三个规则仓已通过同一候选版本的 TCK。标签发布后不得覆盖相同版本，破坏性契约变更
必须提升 SPI 主版本。

GitHub Packages 的凭据只从 Actions 的 `GITHUB_TOKEN` 或开发者环境读取，不写入源码。
规则包签名私钥与 SDK 发布凭据是两套独立的发布环境输入。
