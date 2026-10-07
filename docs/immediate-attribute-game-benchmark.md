# 即时静态网格的游戏内整数属性 A/B 测试

2026-10-07：测量入口已迁移到 `v2.client.mesh.StaticMeshRenderer`，开发属性对照移至 `mesh.debug.MeshRenderDebug`；清理只失效本 benchmark owner。工作区重构尚未正式发布，历史测量数据保持原样。

开发命令挂在现有 `/sbmmesh` 下，使用项目的 24 组枪模/贴图资源，通过真正的 `StaticMeshRenderer.tryDraw()` 绘制客户端虚拟实例。新增命令受 `FMLEnvironment.production` 限制，`example/**` 仍由现有打包任务排除。SBM 2.5.23 常规绘制默认使用缓冲整数属性；I2I/I4I/开发指针对照只在当前渲染调用的作用域内生效。新增 `PRODUCTION` 模式直接调用默认实现，包含正式保护检查。

本次只完成用例与编译验证，游戏内结果由手测产生。

正式 helper 的桌面 GL 回归可独立运行，无需启动游戏：

```powershell
./gradlew compileJava test --tests '*MeshIntegerAttributesTest' --offline
./tools/run_immediate_integer_attributes_gl_check.ps1
```

GL 检查复用现有 `build/classpath/runClient_minecraftClasspath.txt` 和缓存依赖，创建隐藏的 OpenGL 3.3 窗口，使用实际 Minecraft VertexBuffer 和生产 helper。结果写入 `build/immediate-integer-attributes-gl-check/console.log`；覆盖整数像素读回、VAO 交错、绑定保护、固定满亮、同格式重上传和参数表重建，不代替游戏内性能或移动端验收。

提供两种场景：原有 `start/both/view/view_both` 是不设置发光标志的单 part 控制组；新增 `glow/glow_both/view_glow/view_glow_both` 按 TaCZ 的 `_illuminated` 骨骼命名规则设置实例发光标志、用普通/自发光材质分流。此前报告只有一个 part，是捕获没有应用该 TaCZ 规则，不能据此代表真实枪械的多 part 情况。

实例属性要求 OpenGL 3.3 或 ARB_instanced_arrays；兼容 Minecraft 常见的 3.2 + 扩展上下文。GPU 计时单独检查 OpenGL 3.3 / ARB_timer_query 和计数器位数，不可用时仍测量 CPU/帧间隔。

## TaCZ 普通 + 荧光场景

重新编译/启动包含新增指令的开发客户端，用 24 个对象核对画面：

```text
/sbmmesh attrib view_glow i2i 24
/sbmmesh attrib view_glow i4i 24
/sbmmesh attrib view_glow pointer_part 24
/sbmmesh attrib view_glow pointer_item 24
/sbmmesh attrib view_glow pointer_cached 24
/sbmmesh attrib view_glow production 24
```

荧光瞄具/标记应保持满亮，普通枪身跟随实例光照。切换模式时场景、几何、位置保持一致。`view_glow_both` 可额外核对 overlay。

性能长测：

```text
/sbmmesh attrib glow 128 1200 10
```

上项完成后，可另测 overlay 变化：

```text
/sbmmesh attrib glow_both 128 1200 10
```

测试资源中 20/24 个枪模包含真实荧光 cube；`gun_06/07/18/23` 没有该几何，保留其单 part。因此 128 个循环实例预计是 **128 个普通 part + 106 个固定发光 part = 234 parts**；24 个预览对象预计是 44 parts。以实际准备日志及导出数据为准。

在该 128 对象场景，未缓存的稳态预期计数：

| 模式 | IPointer/帧 | bind/帧 |
|---|---:|---:|
| POINTER_PART | 362 | 468 |
| POINTER_ITEM | 362 | 256 |
| POINTER_CACHED | 取决于同 VAO 的光照切换 | 256 |

普通 part 更新 UV1 与实例 UV2；发光 part 只更新 UV1，UV2 保持几何中的满亮值、divisor=0。首次固定光照指针恢复、模式切换成本由预热排除。准备完成时若没有任何固定发光 part，场景会中止，避免静默测成单 part。

发光 RenderType 复刻 TaCZ `DisplayGunRenderType.emissive` 的 energy_swirl shader、恒等纹理矩阵、实体光照/overlay 状态、`ITEM_ENTITY_TARGET` 输出和深度写入；捕获使用 TaCZ 的 QUADS、跳过普通面可见性裁剪选项。它使用现有原始枪模和真实荧光组，未模拟完整外观显隐、配件解析、TaCZ BEWLR/阴影 pass。

新结果目录为 `benchmark-results/immediate-attributes-glow-yyyyMMdd-HHmmss/`，环境中有 `scene=TACZ_GLOW` 和发光规则。`samples.csv`、`summary.csv` 增加 `uniform_parts`、`fixed_parts`；`capture.csv` 记录每个模型捕获到的普通/发光 part 和顶点数，便于核对材质分流。旧目录和旧单 part 场景保留。

## 1. 先核对画面

启动 SBM 开发客户端、进入世界，找一块视野开阔的位置。测试网格生成在玩家前方约 10 格；站在高处或飞行可减少地形遮挡。先清理已有压力场景，再观察少量对象：

```text
/sbmmesh stress clear
/sbmmesh attrib view i2i 24
/sbmmesh attrib view i4i 24
/sbmmesh attrib view pointer_part 24
/sbmmesh attrib view pointer_item 24
/sbmmesh attrib view pointer_cached 24
/sbmmesh attrib view production 24
```

在相同 count、相同 overlay 配置的 preview 之间切换时，保留同一份几何和位置。不要移动镜头，用相同视角比较模型位置、普通光照、自发光和遮挡。对象使用不同的合成光照，部分对象较暗是预期行为；重点是不同模式之间是否一致。自发光 part 保留捕获时的逐顶点光照。

再检查 UV1/overlay：

```text
/sbmmesh attrib view_both i2i 24
/sbmmesh attrib view_both i4i 24
/sbmmesh attrib view_both pointer_item 24
/sbmmesh attrib view_both pointer_cached 24
/sbmmesh attrib view_both production 24
/sbmmesh attrib stop
```

`view_both` 使用不同的 overlay 坐标，出现不同程度的红色/白色覆盖是预期行为。它与 `view` 的场景参数不同，切换两者会重新准备几何与位置。preview 只供目视核对，不导出性能样本。

## 2. 自动测量完整物品调用的成本

默认命令：

```text
/sbmmesh attrib start
```

等价于 128 个对象、每模式每轮 120 个采样帧、3 轮；每个 block 先预热 45 帧。先捕获/上传全部几何，全部就绪后才开始计时。每轮随机排列六种模式，以减弱测试顺序影响。

更长采样，以及同时变化的 overlay：

```text
/sbmmesh attrib start 128 180 3
/sbmmesh attrib both 128 180 3
```

建议顺序运行，等第一项完成再启动第二项。相同对象的光照和位置在整个测试中固定；“变化”指不同 draw 使用不同参数。每种模式都绘制同一批对象。

可调参数范围：count 1–512，frames 30–1200，repeats 1–10。增加到 256/512 个对象可放大 CPU 差异，但可能使测试受 GPU 限制。count 较小也可以检查单物品、多 part 的绑定摊销。

| 模式 | 实际做法 |
|---|---|
| `I2I` | 每 part 关闭 UV1/普通光照 UV2 数组，调用 `glVertexAttribI2i` |
| `I4I` | 同一路径，改用 `glVertexAttribI4i(..., 0, 1)` |
| `POINTER_PART` | 每物品读取原 ARRAY_BUFFER 绑定；每 part 绑定参数 VBO、设置 offset、恢复原绑定 |
| `POINTER_ITEM` | 每物品读取原绑定并绑定参数 VBO一次；所有 part 共用；物品结束时恢复 |
| `POINTER_CACHED` | 与 POINTER_ITEM 相同，但按 VAO/属性缓存 offset，值相同跳过 IPointer |
| `PRODUCTION` | 不使用开发属性回调，直接测正式 Part 缓存、能力/参数检查及 pointer 更新时的绑定保护；pointer/bind 计数为 -1 |

参数表初始化上传一次：65536 个整数对、两个 GL_INT、8 字节 stride，共 512 KiB。UV1/实例 UV2 使用 divisor=1；固定光照 UV2恢复几何 VBO 的 SHORT 指针和 divisor=0。mode/format 变化会重新配置 VAO，相关成本落在预热期。

“每物品提前绑定”在这里包含保存旧绑定的 `glGetInteger`、开始绑定和结束恢复；这些都计入 CPU。它没有把多个物品合并成一个绑定作用域，比先前独立微基准的“计时前提前绑定”更接近当前物品接口。

POINTER_ITEM/POINTER_CACHED 在准备、预热及 preview 阶段检查 shader/材质是否破坏参数 VBO 的提前绑定。若出现 `Material/shader hook changed the prebound ARRAY_BUFFER`，测试停止，说明该环境不能直接应用提前绑定方案。正式采样阶段不逐 part 做这项查询。

PRODUCTION 在需要更新 pointer 时始终核对当前 ARRAY_BUFFER，若 hook 改写则重新绑定。正式计时包含此保护成本；不在生产路径插入 benchmark 计数器。所有模式的 part 数均依据成功绘制实例的 capture 记录累加，因此测量边界一致。生产/开发模式互相切换会使各自 VAO 配置失效并重挂，相关初始化成本落在预热期。

## 3. 控制测试条件

- 保持视角、分辨率、渲染距离和画质一致，关闭已有压力场景；不要同时运行两种压力测试。
- 关闭垂直同步、提高 FPS 上限，以观察帧时间变化。即使被限帧，绘制 CPU 指标仍有参考价值。
- 开始后保持镜头不动，等待资源加载和地形构建稳定。暂停菜单会停止采样，恢复后的第一个帧间隔不用于帧时间统计。
- 不在采样期间重载资源、切换光影包、切换世界或改变图形设置。几何失效或 GL 错误会中止测试。
- 分别测原版/Embeddium、Oculus/AR、目标移动设备；每份结果都会记录 GPU、驱动、限帧、vsync 和相机状态。

查看进度和停止：

```text
/sbmmesh attrib status
/sbmmesh attrib stop
```

停止会清理虚拟对象、查询和参数缓冲，并使即时网格缓存失效，防止 VAO 残留参数表引用。测试对象不写入世界存档。手动停止不导出未完成的样本。

## 4. 导出与解释

完成时聊天会打印六种模式的统计与结果目录；每个 block 完成也会记录到客户端日志。

结果保存在当前游戏目录：

```text
benchmark-results/immediate-attributes-yyyyMMdd-HHmmss/
    environment.txt
    samples.csv
    summary.csv
    capture.csv
```

默认 SBM 开发运行目录是 `D:/Minecraft/Dev/SimpleBedrockModel/run`，时间戳使用 Asia/Hong_Kong。

`summary.csv` 包含每种模式的 CPU 中位数/P95（微秒）、帧间隔中位数/P95（毫秒）、GPU interval 中位数（微秒），以及平均 pointer/bind 调用数。`samples.csv` 保留每帧 block/repeat/mode、成功物品数、part 数、调用数和原始纳秒数。

- `cpu_ns` 覆盖整个虚拟网格的真实 tryDraw 调用，包括缓存查询、矩阵/方向光上传、材质设置、属性设置、绑定、draw 提交及恢复。
- `frame_interval_ns` 是相邻 AFTER_BLOCK_ENTITIES 事件的间隔，包含游戏其余部分，不是只绘制模型的 GPU 时间。开始/暂停后的 0 间隔不进入汇总。
- `gpu_elapsed_ns` 使用异步 GL_TIME_ELAPSED 查询。不会通过 glFinish 等待；有其他同类型查询正在进行、查询池暂时耗尽时为 -1。
- GPU interval 可以包含 CPU 提交过程中 GPU 空闲，不应当作纯着色器执行时间。若没有可用 GPU 查询，汇总 GPU 值为 -1。
- `parts` 可用来判断多 part 是否摊薄绑定成本。通常 POINTER_PART 的绑定数约为 `2 × parts`，POINTER_ITEM/POINTER_CACHED 约为 `2 × items`；固定光照的首次恢复已由预热排除。
- POINTER_CACHED 可跳过不变的 overlay，以及部分光照设置，实际收益以 pointer 调用数为准。同一个 VAO 被不同光照的对象复用时仍需切换 UV2 offset；光照静态不意味着所有 UV2 设置都会被跳过。

这份用例在游戏上下文中运行真实即时绘制实现，但虚拟模型提交发生在 AFTER_BLOCK_ENTITIES，未模拟 TaCZ 的 BEWLR 调用位置、实体 pass 和激光顺序。因此可用于比较 GL 属性与绑定策略的游戏内成本，最终仍需在实际 TaCZ 手持/掉落物和光影 pass 中核对集成行为。
