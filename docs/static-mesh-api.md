# 静态网格 API 与实现结构

更新日期：2026-10-07。VBO 优化处于发布前开发阶段，原型 API 和包路径直接迁移到现行结构。

## 职责与包结构

包根为 `com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh`。

| 包 | 职责与主要类型 |
| --- | --- |
| `mesh` | 临时提交、scope、结果与资源监听：StaticMeshRenderer、StaticMeshBufferSource、MeshDrawResult、MeshSubmitResult、MeshFlushStats、MeshLifecycle |
| `mesh.capture` | CPU 捕获和图元分流：GeometryCollector、MeshGeometryProvider、MeshSink、LightPassVertexRouter |
| `mesh.cache` | 共享缓存、驻留、帧准备预算和故障禁用：StaticMeshCache、MeshCacheResidency、MeshUploadQueue、MeshPreparationPolicy、MeshPathFailures、IdleMeshCache、MeshCachePolicy |
| `mesh.gpu` | 缓冲池、上传／回收、光照流、整数属性和格式：VertexBufferPool、WorldMeshBuffers、WorldMeshPart、MeshIntegerAttributes、LightRangeUpdates、MeshLighting、MeshVertexFormat |
| `mesh.render` | 共用材质／shader／VAO 执行：MeshBatchRenderer、MeshDrawTransform、EmissiveMeshRenderTypes |
| `mesh.world` | 长期对象、世界阶段、剔除、INSTANCE／SECTION、每组几何缓存及统计 |
| `mesh.debug` | 开发属性对照：MeshRenderDebug |

StaticMeshRenderer 执行即时绘制并创建临时队列；StaticMeshCache 持有临时几何生命周期。WorldMeshRenderer 管理世界阶段与登记组，WorldMeshBuffers 管理 GPU 生命周期。两类来源共用 MeshBatchRenderer 和 MeshSink.buildBuffer。

实现类可扩展，可公开的方法均为 public。GeometryCollector 的构造、Pass、snapshot、MeshSink 和 GPU 句柄可直接使用。

## 临时提交

```java
ResourceLocation owner = new ResourceLocation("mymod", "static_items");

try (StaticMeshBufferSource batch = StaticMeshRenderer.openBatch(
        owner, StaticMeshRenderer.BatchOrder.MATERIAL, pass::isCurrent)) {
    MeshSubmitResult result = batch.submit(key, previousKey, provider,
            pose, light, overlay, Duration.ofSeconds(30));
    // provider 在 submit 内按预算同步执行。
    // result.hasMesh() 后，按 result.usedPrevious() 选择对应动态效果。
    batch.endBatch();
}
```

SUBMISSION 保持调用和 Part 顺序，合并连续相同材质；MATERIAL 按实际 RenderType 和共享 Part 分组。同材质 setup／apply／clear 一次，同网格连续绘制期间 bind 一次，每个实例单独 draw。顺序敏感材质由调用方安排提交次序。

| 操作 | 契约 |
| --- | --- |
| submit | 准备目标或选择兼容的已就绪 previous，复制实例参数并持有选中网格 |
| flush | 排空本次命令、释放引用，保持 scope 开启；返回本次增量统计 |
| endBatch | 执行最后一次排空并关闭；返回最后一次排空统计 |
| discard／close | 幂等取消待提交命令并关闭 |

QUEUED／QUEUED_PREVIOUS 表示网格已选中并入队；PENDING 表示本次调用由 VBO 接管并等待准备；UNSUPPORTED 交给调用方的其他绘制路径。

即时入口使用同一缓存：

```java
MeshDrawResult result = StaticMeshRenderer.tryDraw(
        owner, key, previousKey, provider, pose, light, overlay, retention);
```

阶段所有者从实际入口取得缓冲与目标，在确定的边界协调原版提交和网格排空。

## 缓存与属性

临时缓存键为 `(owner, geometryKey)`。geometryKey 描述影响捕获结果的模型、材质、实际 LOD、视角变换等状态；位置、姿势、light 和 overlay 作为实例参数保存。

临时网格在调用期同步捕获并编码；跨帧任务保存独立 RenderedBuffer、实际格式和各 Part 上传进度，全部上传完成后保存就绪 GPU Part 和整数属性状态。世界组保存 CPU 几何以供 SECTION 拼接。INSTANCE 的 Resident 直接持有 active／pending。WorldMeshPart 持有所属 WorldMeshBuffers，释放交还该所有者。

相同键复用唯一准备任务。最近在当前帧或上一帧请求过的条目、正在准备的条目和持有绘制引用的条目保持驻留，不参加 128 条空闲 LRU 淘汰。停止请求后，准备任务在 retention 到期时取消；就绪几何进入闲置 LRU，TTL 从最后请求时间计算。释放一次调用的引用只结束该次使用，不取消准备任务。

Forge RenderTick START 推进上一帧的上传任务。默认每帧捕获最多 4 次／2ms，上传最多 4 个 Part／8MiB／2ms；时间为 CPU 软预算，首个 Part 可以越过字节／时间额度以保证进度。准备数据和整套几何驻留分别使用 256MiB 软准入预算，优先回收真正闲置的几何；允许一个超大几何单独驻留，避免部分 Part 上传后永久堵塞。预算不足时任务继续等待。`StaticMeshRenderer.preparationStats()` 返回准备、上传、驻留字节与累计捕获统计，调用方无需管理任务状态。

世界光照采用 UNIFORM／FIXED／MUTABLE。UNIFORM 和临时网格都通过整数参数表及 divisor=1 提供实例 UV2，不使用 `glVertexAttribI2i` 常量属性；世界网格的 UV1 保持读取几何，临时网格的 overlay 从参数表读取。参数表支持每个分量 0–255，世界 UNIFORM 的能力／范围／上传检查失败时 `prepareDraw` 返回 false。FIXED／MUTABLE 的 UV2 按实际上传格式读取几何或可变流，divisor=0，缓冲池复用时重新挂载。两者共用材质绘制、矩阵／方向光处理及 GPU 状态清理。

## 生命周期

所有操作在渲染线程执行。provider 在当前调用内按预算同步捕获和编码，不跨帧保存临时 ItemStack、PoseStack 或 consumer。GPU 上传由 SBM 自己的跨帧队列在帧开始执行 VertexBuffer.upload，并在执行点计入预算；不再将临时网格一次性推入 ChunkRenderDispatcher。全部 Part 就绪后才能提交绘制；绘制命令保存独立矩阵、整数参数和网格引用，仅属于创建它的当前帧／阶段。

命令持续持有网格直到排空或取消，释放引用不改变跨帧准备任务的有效性。世界、GL context、资源／格式代次或 owner 失效时，取消对应准备任务并精确释放未上传数据及 GPU Part；失效的绘制命令不会跨帧补画。

生产热路径以条件预检、显式正常收尾和明确的异常边界处理错误，不使用静默的 try-finally 继续执行。首次捕获、上传或绘制 RuntimeException 记录 owner、阶段与完整调用栈，并取消和禁用该 owner；后续请求直接返回 UNSUPPORTED，资源／格式重置或世界切换后才恢复。参数表分配故障同样停用该后端。若清理本身失败，追加到原异常并向上传播，禁止继续在损坏状态下渲染。已经可能部分绘制的本次调用仍由 VBO 接管，避免重复绘制。

## TaCZ 接入依据

SBM 基础设施已实现。TaCZ 已迁移新包引用、固定 owner 和调用方结果，动态效果按实际选中 Snapshot 对齐；当前使用同一缓存的即时 VBO，真实实体／shadow 阶段的队列接入待实施。

以现有即时 VBO 的正常表现为基线。同阶段固定的 shader 状态由 scope 维护；实际逐对象差异才保存到提交项或加入绘制分组。isCurrent 由阶段所有者校验所属 scope、目标和管线代次。

Oculus 的 CapturedRenderingState 是其全局渲染状态记录。扩展格式启用时，普通 BufferBuilder 会通过 Mixin 自动附加当前分类字段，现有即时路径也经过这一机制。根据实际格式、捕获值及画面差异决定缓存区分和状态保存。

## 发布与验证

静态准备任务、驻留及帧上传预算的实现边界见 [准备生命周期设计](static-mesh-preparation-design.md)。

新 API 随 `com.github.mcmodderanchor:simplebedrockmodel:2.5.26-forge-mc1.20.1` 发布，再同步 TaCZ 依赖范围。执行 `./gradlew publishToMavenLocal --offline` 可将重混淆后的完整 jar 和 sources jar 发布到 Maven Local。

已通过 `compileJava reobfJarJar sourcesJar --offline` 和产物引用检查。隐藏 OpenGL 3.3 检查覆盖连续 32 次 draw、交替 VAO、变化 light／overlay、固定发光、绑定改写和参数表重建。原有矩阵／整数属性测试已迁移包名。

`2.5.25` 已移除生产世界网格的常量整数属性路径。隐藏 GL 检查直接调用 WorldMeshBuffers.prepareDraw，覆盖 UNIFORM／FIXED／MUTABLE、几何 overlay、参数表重建、同格式 VAO 复用及 UV2 位于索引 4／3 两种实际格式。I2I／I4I 对照仅保留在不随发布 jar 打包的 example／tools 基准中。

`2.5.26` 的 115 项测试全部通过。准备队列回归覆盖 512 实例／200 键、每键一次捕获、上传软预算、闲置回收、取消与故障隔离；隐藏 GL 检查以独立编码数据驱动真实缓存和 PendingUpload，验证 200 份 VBO 上传、持续驻留、读回、绑定恢复及 owner 清理。故障注入验证首次捕获异常日志、owner 禁用、后续不再执行失败 provider，以及显式生命周期重置。

游戏内验收采用世界组、真实物品和主场景／shadow 对照；当前自动基准比较 immediate、ordered、batched 三条 VBO 路径。
