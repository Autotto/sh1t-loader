# Mod 兼容测试：不成功的 mod（待修）

测试日期：2026-09-26 至 2026-09-27
加载器：Forbric main `11ca1ffa`，用 `forbric-kernel-installer` 装进 Mac 官方目录 `~/Library/Application Support/minecraft`（版本 `26.2-forbric`）
本文件只记录测试结果，没有查原因，也没有修复。

## 测试方法

- 从 Modrinth 选了 26.2 的 30 个热门 mod（下载量前 100 名里随机抽）和 50 个随机 mod，加载器随机（Fabric / NeoForge / MinecraftForge），再加上它们的依赖，共 101 个 jar。
- **每个 jar 单独测**，只带它自己必需的依赖。每次都用一个干净的游戏目录，进入同一个零 mod 生成的原版世界，第 100 tick 截图，第 200 tick 退出世界。
- 兼容策略设为"继续"（相当于玩家在提示窗口里点了继续）。
- 同样的测试跑了 3 轮。

"完全正常" = 进了世界、画面画出来了、正常退出，并且这个 mod 在 Forbric 加载报告里是 OK。

## 成功率

| 轮次 | 完全正常 | 能进世界 |
|---|---|---|
| 第 1 轮 | 83/101（82.2%） | 95/101（94.1%） |
| 第 2 轮 | 83/101（82.2%） | 95/101（94.1%） |
| 第 3 轮 | 83/101（82.2%） | 95/101（94.1%） |
| **平均** | **82.2%** | **94.1%** |

三轮结果完全一致，下面 18 个 jar 每轮都不成功。

按分组看（每轮相同）：热门 30 个里 23 个完全正常，随机 50 个里 43 个，依赖库 21 个里 17 个。

## 不成功的 mod

"来源"一列：热门 / 随机 = 抽中的 mod；依赖 = 被抽中的 mod 需要、从 Modrinth 依赖关系带进来的；补装依赖 = mod 自己的元数据要、但 Modrinth 上没标出来、测试时手动补上的。

### 进不了游戏或世界（6 个）

| mod | 来源 | 加载器 | 版本 | 现象（3 轮一致） |
|---|---|---|---|---|
| [MCA Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn) | 补装依赖 | NeoForge | 8.1.11+26.2 | 卡在 Mojang 加载画面，不再前进 |
| [Supermarket Life](https://modrinth.com/mod/mca-rebornsupermarket-life)（需要 MCA Reborn） | 随机 | NeoForge | 1.0.1 | 卡在 Mojang 加载画面，不再前进 |
| [Massive Smoke Columns](https://modrinth.com/mod/massive-smoke-columns) | 随机 | NeoForge | 1.5.2 | 启动时崩溃 |
| [Essential](https://modrinth.com/mod/essential) | 热门 | Fabric | 1.5.0.1 | 启动时直接退出 |
| [Roughly Enough Items (REI)](https://modrinth.com/mod/rei) | 热门 | NeoForge | 26.2.820+neoforge | 游戏能启动，打开世界时失败（数据包加载失败） |
| [Visual Workbench](https://modrinth.com/mod/visual-workbench) | 热门 | NeoForge | 26.2.1 | 游戏能启动，打开世界时失败（数据包加载失败） |

### 能进世界，但 mod 没加载成功（4 个）

| mod | 来源 | 加载器 | 版本 | Forbric 加载报告原文（未核实） |
|---|---|---|---|---|
| [Resourceful Config](https://modrinth.com/mod/resourceful-config) | 热门 | Fabric | 5.0.0 | did not finish loading — its main entrypoint threw |
| [andonium](https://modrinth.com/mod/andonium2) | 随机 | Fabric | 2.2.1+26.2-fabric | did not finish loading — its main entrypoint threw |
| [Knox](https://modrinth.com/mod/knox) | 随机 | Fabric | 1.0.0 | did not finish loading — its client entrypoint threw |
| [minimega](https://modrinth.com/mod/minimega) | 依赖 | Fabric | 7.1.0 | did not finish loading — its client entrypoint threw；它自带的 fantasy 部分功能未生效 |

### 能进世界，但 mod 部分功能没生效（8 个）

| mod | 来源 | 加载器 | 版本 | Forbric 加载报告原文（未核实） |
|---|---|---|---|---|
| [fabric-api](https://modrinth.com/mod/fabric-api) | 依赖 | Fabric | 0.161.0+26.2 | 4 个模块部分未生效：fabric-block-api-v1、fabric-creative-tab-api-v1、fabric-loot-api-v3、fabric-registry-sync-v0 |
| [Architectury API](https://modrinth.com/mod/architectury-api) | 热门 | Fabric | 21.1.10+fabric | A required injector has no attachment in the actual defined class |
| [Language Reload](https://modrinth.com/mod/language-reload) | 热门 | Fabric | 1.7.7+26.2 | A required injector has no attachment in the actual defined class |
| [Physics Mod](https://modrinth.com/mod/physicsmod) | 热门 | Fabric | 3.2.4 | 3 个 mixin 被跳过：immediatelyfast.MixinSignText、liquid.MixinProgramManager、sodium.MixinVertexTransform |
| [Carpet](https://modrinth.com/mod/carpet) | 补装依赖 | Fabric | 26.2 | A required injector has no attachment；receiveFluidToBlackstone 挂在一个没有调用方的方法上 |
| [Phantom Tweaks](https://modrinth.com/mod/phantom-tweaks) | 随机 | Fabric | 1.1.3+mc26.1 | A required injector has no attachment in the actual defined class |
| [Pack Tools](https://modrinth.com/mod/pack-tools) | 随机 | NeoForge | 1.26.6.2 | GuiMixin 等 mixin 应用失败（InvalidMixinException） |
| [No Too Expensive Anvil](https://modrinth.com/mod/no-too-expensive-anvil) | 随机 | NeoForge | 1.3.1 | RenderLabelsAnvilScreenMixin 被跳过 |

## 其他测试中看到的情况（未单独复测）

- **只在整包里出现**：andonium 和整包一起加载时，服务端生成地形直接崩溃；把 andonium 拿掉后世界能生成。单独测 andonium 时能进世界（但 andonium 自己加载失败，见上表）。
- **缺依赖但照样加载了**：wcopy（chat-copy）需要 Chat Heads，hide-minimega-leaderboards 需要 Legacy4J（没有 26.2 版）。两个都没装依赖，Forbric 照样加载、进了世界、没有报错，所以算作"完全正常"。
- **Modrinth 没标出的依赖**：blockframe 需要 owo-lib、ibcarpet 需要 Carpet、Supermarket Life 需要 MCA Reborn、chunky-friends 需要 Chunky、Peterwolf's Railroads One 需要 Minecart Chain。补上后，除了带着 MCA 的 Supermarket Life，其余都完全正常。

## 证据位置（本地，不在仓库里）

- 每个 jar 每一轮的日志、加载报告、截图、崩溃报告：`forbric-kernel/build/sweep80-mac/per-mod/`、`per-mod-r2/`、`per-mod-r3/`
- 选中的 mod 清单（含版本、SHA-1、下载地址）：`forbric-kernel/build/sweep80-mac/manifest.json`
- 汇总：`forbric-kernel/build/sweep80-mac/summary.json`
