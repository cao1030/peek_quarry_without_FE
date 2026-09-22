# 开发笔记

这份文档记录了搭建/开发这个模组过程中踩到的坑、1.7.10 的几个反直觉行为，以及游戏内实测记录。
内容偏「工程笔记」，想了解模组怎么用请看根目录的 [README](../README.md)。

---

## 1. 开发环境

### 1.1 三套 JDK，各司其职，不能混

| 用途 | 版本 | 原因 |
|---|---|---|
| 跑 Gradle 本身 | **Java 25** | RFG 2.0.4 的 class 文件版本是 **69.0**（= Java 25）。用 Java 17/21 会直接 `UnsupportedClassVersionError: ... class file version 69.0`。 |
| 编译 mod | **JDK 8** | RFG 默认 `jvmLanguageVersion = 8`，产出 `major=52`，1.7.10 才加载得了。 |
| RFG 内部任务（`:applyJST` 等） | **JDK 21** | RFG 自己 fork 的进程。 |

`gradlew` 通过 `JAVA_HOME` 找 Java 25；JDK 8 / 21 通过 Gradle 的 toolchain 机制找。
两者的位置都**不写进仓库**（否则别人 clone 下来构建不了），放在全局 Gradle 配置里：

```properties
# %GRADLE_USER_HOME%/gradle.properties  或  ~/.gradle/gradle.properties
org.gradle.java.installations.paths=/path/to/jdk8,/path/to/jdk21,/path/to/jdk25
org.gradle.java.installations.auto-download=false
```

Windows 上 `dev.bat` 会读同目录的 `local.env.bat`（已 gitignore）来拿 `JAVA_HOME` 和
`GRADLE_USER_HOME`。

### 1.2 环境坑（都已绕过）

| 现象 | 原因 | 解决 |
|---|---|---|
| `services.gradle.org` 只有 **0.04 MB/s**，129MB 要下 50 分钟 | 该域名在国内被限速 | wrapper 的 `distributionUrl` 指向**腾讯镜像**（~10 MB/s） |
| `curl` / PowerShell 的 HTTPS 全部失败 `SEC_E_NO_CREDENTIALS` | 本机 **schannel 凭据不可用** | 用 Python / Java（自带 OpenSSL / JSSE）下载，不影响 Gradle |
| Maven Central 0.11 MB/s，**Forge maven 仅 0.01 MB/s** | 跨境链路慢 | Forge 1.7.10 的产物 Maven Central / 阿里云**都没有**（实测 404），只能走官方 maven；好在总量仅 ~4.5MB |
| `:downloadVanillaJars` 报 `SocketTimeoutException: Read timed out` | RFG 下载任务的 socket 超时太短，Mojang CDN 慢 | 用 `tools/prefetch_vanilla.py` 预取（RFG 对该任务是 `overwrite(false)`，文件存在即跳过，并按 SHA1 校验） |
| Gradle 报 `Could not initialize native services` | 默认 `~/.gradle` 在受限目录下不可写 | `GRADLE_USER_HOME` 指到工作区内 |
| `Couldn't open current thread, error = 5` | Gradle 原生文件监视器被沙箱拦 | `org.gradle.vfs.watch = false` |
| `Circular evaluation detected: mcpMappingChannel` | Kotlin DSL 里局部变量名和 `minecraft` 扩展属性重名，被 receiver 遮蔽 | 改用 `providers.gradleProperty("mcpMappingChannel")` 显式取值 |
| `dev.bat` 执行时冒出一堆 `'tlocal' is not recognized` / `ClassNotFoundException: 25` 碎片 | `dev.bat` 存成了 **LF 换行**，而 `cmd.exe` 解析批处理必须要 **CRLF**，于是从行中间断开执行 | 转成 CRLF。**以后用编辑器改 `.bat` 记得别存成 LF** |
| PowerShell 脚本里的中文导致语法崩 | 无 BOM 的 `.ps1` 会被 Windows PowerShell 按 **GBK** 读 | 辅助脚本一律写纯 ASCII |

**如果开了代理**（`127.0.0.1:7897`），可以在 `gradle.properties` 里加上以加速境外下载：

```properties
systemProp.http.proxyHost = 127.0.0.1
systemProp.http.proxyPort = 7897
systemProp.https.proxyHost = 127.0.0.1
systemProp.https.proxyPort = 7897
systemProp.http.nonProxyHosts = mirrors.cloud.tencent.com|maven.aliyun.com|localhost|127.0.0.1
systemProp.https.nonProxyHosts = mirrors.cloud.tencent.com|maven.aliyun.com|localhost|127.0.0.1
```

---

## 2. 1.7.10 / FML 的实现坑

这几条都是**编译通过、运行时不报错、但行为不对**的那种，最难查。

| 坑 | 说明 |
|---|---|
| **`EventBus` 没有 `register(Class)`** | FML 1.7.10 的 `EventBus` 只有 `register(Object)`，而且只扫**实例**方法上的 `@SubscribeEvent`。把 `onServerTick` 写成 `static` 再传 `PacketHandler.class` —— 编译通过、运行时不报错、但**什么都不注册**。表现是「点了确认没反应」，日志里一行错都没有。 |
| **`IMessageHandler` 跑在 netty 线程** | 见 `SimpleChannelHandlerWrapper.channelRead0`，`onMessage` 直接在网络线程被调用。所以服务端方向的包一律先塞进 `ConcurrentLinkedQueue`，再由 `ServerTickEvent` 在主线程执行，绝不在网络线程碰世界数据。 |
| **netty 的 `ByteBuf` 没有 `readUTF/writeUTF`** | 那是 MC 自己 `PacketBuffer` 的方法。这里手动加 int 长度前缀 + UTF-8 字节。 |
| **结果列表必须主动拉取** | 半径/进度走 Container 窗口属性自动同步，但列表太大不适合那么传。打开 GUI 时客户端要发 `MsgRequestResults`。忘了发的话，重进世界后**半径对、状态对、列表空**，极具迷惑性。 |
| **`Item.getItemFromBlock` 是按 ID 反查的** | 源码是 `getItemById(Block.getIdFromBlock(block))`。没有 ItemBlock 的方块（甘蔗、农作物、水/岩浆）会返回 null 或**撞到别的物品**。所以 `BlockCount.getStack()` 会校验拿到的 `ItemBlock.field_150939_a` 是否就是该方块，不匹配就退回直接画方块贴图。 |
| **`World.setBlock` 无条件 checkLight** | `setBlockToAir` → `setBlock(..., flags=3)`，但 `checkLight` 那一步不看 flags，每个方块都会跑一次。所以挖方块比读方块贵好几个数量级，必须限速。 |
| **GUI 坐标系** | `drawGuiContainerBackgroundLayer` 是**绝对**坐标；`drawGuiContainerForegroundLayer` 已经被平移过、是**相对** `guiLeft/guiTop` 的。混了就画歪。 |
| **`searchField.drawTextBox()` 在 `drawScreen` 里画** | 所以占位提示必须画在它**之后**，否则被整块盖掉。 |
| **语言文件只用 ASCII + 汉字** | `→` `×` `↓` `·` 这类字符不在 MC 默认 ASCII 字形里，只有加载了 unicode 字体页才画得出来，所以一律避开。颜色码 `§`（U+00A7）是例外，`.lang` 不支持 `\u` 转义，必须写字面量。 |
| **`provideChunk` 会生成地形** | 扫描/提取时用它强制加载范围内区块，未生成过的会**现场生成**。这是设计上明确的代价，也是「取消」按钮存在的理由。 |

---

## 3. 游戏内实测记录

测试环境：开发环境 `runClient`，创造模式、开作弊，测试世界视距 12 区块。

| 检查项 | 结果 |
|---|---|
| 客户端启动 | ✅ `4 mods loaded`，`preInit/init/postInit` 全跑完 |
| 贴图烘焙 | ✅ blocks-atlas 正常 stitch；FML 的 `logMissingTextureErrors()` 无输出（缺失会打 `TEXTURE ERRORS`） |
| 方块注册名 | ✅ `/give @p peek_quarry:ender_touch` 成功，中文名显示为「末影之触」 |
| 放置与渲染 | ✅ 世界里的正常立方体，六面贴图正确 |
| 右键开 GUI | ✅ 布局、按钮、搜索框、列表均正常渲染 |
| 扫描 | ✅ 半径 3 → `7×7 = 49 区块` / 55 种；半径 8 → `17×17 = 289 区块` / 78 种、510 万个方块 |
| 排序切换 | ✅ 降序 ↔ 升序，列表实时重排 |
| 搜索过滤 | ✅「矿」→ 6 种，状态栏合计变为 14934（= 各项相加） |
| 图标回退 | ✅ 无 ItemBlock 的方块（甘蔗、仙人掌…）也能画出贴图，不再是空格 |
| 取消扫描 | ✅「已取消 163/289 区块，结果不完整」，进度条停在 56%，保留部分结果 |
| 剔除流体+基岩 | ✅ 重扫后 78 种 / 5106355 → **55 种 / 4844634**；搜索「岩」「水」均无匹配 |
| 拼音：全拼 | ✅ `shitou` → 石头 |
| 拼音：首字母 | ✅ `st` → 石头；`zsks` → 钻石矿石；`myzc` → 末影之触 |
| 拼音：部分音节 | ✅ `kuang` → 6 种矿石，合计 92623 |
| 拼音表懒加载 | ✅ 首次搜索时才读，日志 `拼音表已加载：20901 个字` |
| NBT 持久化 | ✅ 正常退回标题存盘 → 重进世界，结果与存盘前一致 |
| 侧边栏 | ✅ 右侧只读列表，空时显示「空」，有内容按数量降序 |
| 中键弹窗 | ✅ 图标、可提取数、输入框、快捷键、确认/取消、橙色警告 |
| 数量校验 | ✅ 超上限 → 红字提示，弹窗不关也不执行 |
| 提取入容器 | ✅ 石头 ×64：列表 4179932 → 4179868，侧边栏出现「石头 64」 |
| **提取真的改了世界** | ✅ 重新扫描（直接读世界，与记账无关）得到 **4179868**，正好少 64 |
| 挖掘可见 | ✅ 挖掉 200 个草方块后，玩家周围地面露出下面的泥土 |
| 取消提取 | ✅ 泥土「全部」(289007) 中途取消 →「提取已取消 x18246」，列表同步扣减 |
| 主动输出：循环在跑 | ✅ 诊断日志证明 `tickEject()` 每 8 tick 执行、遍历六个面并查找相邻 `IInventory` |
| **主动输出：真的送进容器** | ❌ **未验证** —— 见下 |
| 运行期报错 | ✅ 整个会话无 ERROR / WARN（除 FML 在开发环境固定报的 binpatch/signature 告警） |

### 未验证项

1. **主动输出的最后一环。**
   那次会话里 Minecraft 的**键盘输入完全不通**：`keybd_event` 和直接 `PostMessage`
   （返回成功、窗口有效、确实是前台窗口）都无效，鼠标却正常。
   于是 `/give` 和创造模式物品栏都用不了；滚轮会丢事件、右键时灵时不灵，也无法可靠瞄准摆放。
   绕过去的办法是直接改存档（`level.dat` 的玩家物品栏 NBT）拿箱子 ——
   写了带**类型精确保留**的 NBT 读写（往返字节一致，不会写坏存档）——
   但几次摆放都落成了**对角相邻而不是共面**，诊断日志如实报 `相邻容器 0 个`。

   也就是说：**输出循环确实在跑、也确实在找相邻容器，但「真的把物品塞进容器」没有实测过。**
   验证方法：放一个箱子**贴着**方块（共面，不是对角），看侧边栏是否开始减少；
   日志出现 `输出 <方块> xN -> (x, y, z)` 即通过。

2. **未生成区块的现场地形生成**没有被真正触发过 —— 测试世界视距 12 区块，而半径上限是 8，
   范围内区块本来就是加载好的。`provideChunk` 调用到了，但没走到「生成新地形」那条路。

3. **「提取」的规模上限没测**：只测到 1.8 万个方块。多人同时操作同一个方块、
   提取过程中方块被破坏、跨维度等场景都没测。

4. **`en_US`** 只在语言文件里写了，没在游戏内切英文看过。

5. **拼音匹配的算法**另有一份 26 条离线用例（全拼 / 首字母 / 多音字 / 误报）跑通过，
   但**没有沉淀成仓库里的自动化测试**。

---

## 4. 换机器时要注意的

- `gradle.properties` 里**没有**绝对路径（刻意的）。JDK 位置放全局 Gradle 配置。
- `dev.bat` 读同目录的 `local.env.bat`（gitignore 掉了）拿 `JAVA_HOME` / `GRADLE_USER_HOME`。
- `tools/gen_pinyin_table.py` 需要一个 Unihan 数据源。最方便的是从任意装了
  **REI（Roughly Enough Items）** 的现代版本整合包里拿
  `config/roughlyenoughitems/unihan.zip`，用 `--unihan <路径>` 指过去。
- `tools/recolor_command_block.py` 会自动去 `%GRADLE_USER_HOME%\caches\retro_futura_gradle\mc-vanilla\1.7.10\client.jar`
  找原版命令方块贴图，也可以用 `--jar <路径>` 手动指。
