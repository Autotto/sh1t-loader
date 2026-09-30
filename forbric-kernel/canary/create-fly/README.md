# Create Fly 加载回归

实测文件：`create-fly-26.2-rc-2-6.0.9-1.jar`（Fabric，Minecraft 26.2）。
下载地址：<https://cdn.modrinth.com/data/dKvj0eNn/versions/phlsMPgT/create-fly-26.2-rc-2-6.0.9-1.jar>。
SHA-512：`2879187dcb1aa494710b71e4d967ac8f12d38e5f4b800652d38c484c1db3a38a72bf624e8dc0b53a457ccdf62f479c3264e1d5cbb2418e23da42bdce9c738f54`。

在 `forbric-kernel` 目录运行专项测试：

```sh
./gradlew --offline -Pforbric.createFlyJar=/绝对路径/create-fly-26.2-rc-2-6.0.9-1.jar \
  test --tests '*Create*MixinAdapterTest' --tests '*CreateWorkerShutdownTest' \
       --tests '*CreateTaskWaitTest' \
       --tests '*StringVersionCompatibilityTest' --tests '*SoundRegistryIdentityInjectorTest' \
       --tests '*ServerReloadListenerNamesInjectorTest' \
  transferTest --tests '*IdentityValueBiMapTest'
```

未指定路径时读取 `build/compat-inputs/create-fly/create-fly.jar`；缺少真实模组文件的专项用例会跳过。
字节码测试还需要已准备好的游戏与 Forge/NeoForge 运行时文件。

2026-10-01 客户端验证：仅装 Create Fly，严格兼容模式，进入独立的原版测试世界；第 100 tick 截图、
第 200 tick 退出世界，进程自行以 0 退出。加载报告中 Create Fly 为 `OK`，已确认的必需功能缺失为 0。
覆盖加载和退出流程，以及声音对象身份、按键回调和 Flywheel 线程池收尾；未覆盖所有机械、列车和 Ponder 功能。
