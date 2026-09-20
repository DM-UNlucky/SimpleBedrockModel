# 静态方块实体渲染：结论与 SimpleBedrockModel 原型计划

日期：2026-09-18（v2，修订记录见文末）

范围：以装饰枪（`tacz-wall-display`）的静态渲染方案为**参考案例**，设计 SimpleBedrockModel 的通用「静态方块实体快速路径」。库侧提取 VBO 管理与统一绘制通道；实例收集、过期淘汰与脏标记语义留给各实现方。本文只记录结论与依据，不含实现。

状态：架构结论已定；第 8 节为待实测项。

定位说明：`[WD]` 是参考实现，不是本库的一手项目。本库不为它的兼容性与回归负责，只从它身上提炼机制与约束；库的收益不依赖「它是否会改用 SBM」。

### 路径简写

| 简写 | 实际路径 |
|---|---|
| `[WD]` | `D:\Minecraft\Dev\TACZ_WallDisplayedGuns_Mod\tacz-wall-display-1.20.1`（参考实现） |
| `[SBM]` | `D:\Minecraft\Dev\SimpleBedrockModel` |
| `[TACZ]` | `D:\Minecraft\Dev\MCModderAnchor\TimelessAndClassicsZeroF` |
| `[FORGE-SRC]` | `...\build\fg_cache\net\minecraftforge\forge\1.20.1-47.3.19_mapped_parchment_2023.08.20-1.20.1\forge-1.20.1-....-sources.jar` |
| `[EMB]` | 本机 `D:\Minecraft\cf\Instances\test\mods\embeddium-0.3.31+mc1.20.1.jar` |
| `[OCULUS]` | `[TACZ]/libs/oculus-mc1.20.1-1.7.0.jar` |

---

## 0. 结论摘要

- 静态化方案成立。参考实现有两类退化点：光照突发触发的重建，以及每帧 `O(N·M)` 的索引重建（第 4、5 节）。两者都在实现方一侧，不是方案本身的问题。
- 库提取「VBO 管理 + 统一绘制通道」在只有一个消费者时也成立：它收拢的是容易写错且写错会静默失效的 GL 纪律（池化、`bind() → upload() → unbind()`、每 RenderType 一次 setup/clearState、Oculus 的 bind/unbind save-restore）。
- 实例的**收集、过期、淘汰策略**归实现方；但**释放动作必须经由库 API**（handle + 引用计数），否则 GPU 内存无从回收。
- 池化（复用整个 `VertexBuffer`）要做且安全；**子分配（一个大 VBO 切区间）不做**。
- 「每材质一趟」在每实例一张贴图时退化为「每贴图一趟」。库承诺消除每批次的固定开销，不承诺跨贴图合并。
- Embeddium 0.3.31 下 `AFTER_*_BLOCKS` 仍然派发（已静态确认，第 6.2 节）。
- Iris/Oculus 的 shadow pass **不触发** `AFTER_BLOCK_ENTITIES`：静态几何不会被重画，也不会进入阴影贴图。是否需要投影是产品决策（第 6.3 节）。
- 手写 VAO / 多顶点流 / 实例化：放弃（第 6 节）。
- `AFTER_BLOCK_ENTITIES` 是唯一绘制阶段，半透明部件的排序问题必须显式接受（第 4 节）。
- 原型已落地（第 7.5 节）：库侧 `v2/client/world` + `MeshSink`，示例 Source 挂在 TestBlock 上，运行时用 `/sbmstatic on|off|stats|drop` 做 A/B。

---

## 1. 分层与归属

| 层 | 内容 | 归属 | 现状 |
|---|---|---|---|
| L1 烘焙 | 骨骼树 → 打包静态几何，可保留部分骨骼动态 | SBM | **已有** |
| L2 回放 | 打包几何 → 任意 `VertexConsumer`，带任意 pose | SBM | **已有**，缺一个 MeshSink |
| L3 世界运行时 | VBO 池化、上传提交、统一绘制通道、阶段派发 | SBM（`v2/client/world`） | **缺** |
| L4 策略 | 实例收集、过期淘汰、脏标记、网格生产、视距剔除、光照采样、材质选择 | 各实现方 | 各自实现 |

### 1.1 库负责（L3）

- VertexBuffer 池化与复用（acquire/release），池按顶点格式分桶。
- 上传提交：统一走原版 `ChunkRenderDispatcher.uploadChunkLayer`，把 `bind() → upload() → unbind()` 顺序封装在库内。
- 统一绘制通道：按 RenderType 排序；每个 RenderType 只做一次 `setupRenderState` / `clearRenderState` 与共享 uniform；逐 shard 只上传 MODEL_VIEW 矩阵 + `bind()` + `draw()`。
- 单一 `RenderLevelStageEvent` 监听，把各 Source 的 `stageOrder` 合成确定的绘制顺序，不让每个 mod 各自抢阶段、各自切状态。
- ready gate 聚合：以提交 future 的完成语义判定「静态几何就绪」。
- Oculus / Embeddium 兼容纪律（第 6 节）。
- 统计与调试命令。

### 1.2 实现方负责（L4）

- 网格生产与缓存 key、后台重建。
- 脏标记语义。
- 实例的收集、过期与淘汰策略。
- 视距、剔除策略、光照采样点。
- 材质归属哪个绘制阶段：提供不透明 MaterialKey 与其对应的 `RenderType`。

### 1.3 库与实现方的生命周期契约

- **handle + 引用计数**：`submit` 返回 handle；实现方用 `release(handle)` 声明不再使用；引用归零后由库回收。同一 mesh 被多实例复用时，多个实例共享同一 handle，回收只发生一次。
- **失效广播**：世界卸载 / 资源重载 / Source 注销时，库统一回收缓冲并回调 `onInvalidate`。
- **池按顶点格式分桶**：`VertexBuffer.upload` 在格式变化时会先在当前绑定的 VAO 上 `clearBufferState()` 再 `setupBufferState()`（`[FORGE-SRC]/VertexBuffer.java:54-75`），跨格式复用会互相破坏 VAO 状态，必须禁止。
- **渲染线程约束**：`new VertexBuffer`、`close()`、`upload()`、池的 acquire/release 都必须在渲染线程；后台线程只产出不可变网格数据。

判断准则：按「知识是否稳定」决定是否提取，而不是按「代码是否重复」。骨骼折叠规则、UV/法线变换、`bind → upload → unbind` 这类 GL 纪律是稳定的；阶段选择、剔除与光照策略仍在演进。对「写错会静默失效」的兼容层额外偏向提取。

---

## 2. 已存在、可直接复用

### SBM 侧

| 能力 | 位置 |
|---|---|
| 骨骼树 → 打包静态几何，按「最近的 runtime 祖先」归并 | `[SBM]/src/main/java/com/github/mcmodderanchor/simplebedrockmodel/v2/common/model/baked/BedrockGeometryBaker.java:39` |
| 静态/动态骨骼划分 | `BakerOptions.bakeStaticGeometry` / `animatedBones` / `preservedBones` / `preservedBonePatterns` |
| 打包顶点数据（不是 record 列表） | `BakedGeometryChunk` / `BakedQuadData`（12/3/8 stride）/ `BakedVertexData`（3/3/2） |
| 打包几何 → 任意 consumer，带任意 pose | `BakedGeometryChunkRenderer.render(chunk, poseStack, quadConsumer, triangleConsumer, ...)`，内含 Sodium / AcceleratedRendering 快速路径 |
| 模型带外部 consumer 的渲染入口 | `BedrockModel.renderToBuffer(PoseStack, VertexConsumer, light, overlay)`（`[SBM]/.../v1/common/model/BedrockModel.java:151`） |

注：参考实现需要 5 个 capture mixin 才能拿到「渲染到指定 consumer」，SBM 本来就有公开重载。但 TACZ 用的是自己 fork 的 `com.tacz.guns.client.model.bedrock.*`，不是 SBM 的类；本库不依赖 TACZ，也不为它的模型层负责，该案例只用于提炼机制。

### 原版侧

| 能力 | 位置 |
|---|---|
| 取到区块渲染调度器 | `LevelRenderer.getChunkRenderDispatcher()`，public，`[FORGE-SRC]/LevelRenderer.java:775` |
| 渲染线程上传队列（只入队，不立即上传） | `ChunkRenderDispatcher.uploadChunkLayer(RenderedBuffer, VertexBuffer)`，public，`:225` |
| 每帧自动 drain | `LevelRenderer.renderLevel` 每帧无条件调用 `compileChunks`（`:1190`），其中 `uploadAllPendingUploads()`（`:2133`）早于 `renderChunkLayer` |
| 绘制循环模板 | `LevelRenderer.renderChunkLayer`，`:1473-1591`（每材质一次 setup/clear，N 次 uniform + bind + draw） |

`uploadChunkLayer` 的实现把 `toUpload` 队列当作 `Executor` 用：

```java
return CompletableFuture.runAsync(() -> {
   if (!pBuffer.isInvalid()) { pBuffer.bind(); pBuffer.upload(pBuilder); VertexBuffer.unbind(); }
}, this.toUpload::add);
```

因此在任意线程调用它只会入队，真正的 bind/upload 由渲染线程在下一帧 `compileChunks` 执行；`isInvalid()` 守卫使得世界卸载后已 close 的 buffer 不会被误上传，生命周期天然对齐。`runAsync` 返回的 future 由 `AsyncRun` 在渲染线程执行完成后完成，因此「future 完成」等价于「已上传」，可用于 gate 聚合。

缺口：`toUpload` 是 `ConcurrentLinkedQueue`（`:71`），FIFO、无预算、无优先级，且 drain 是一次性全量执行。所以**队列直接用，但预算和排序必须由库在提交端补，并按顶点/字节保守估算**。

---

## 3. 库侧设计

### 3.1 对外契约

```java
public final class StaticWorldRenderer {
    static void register(Source source);
    static void unregister(String id);                                       // 实现方注销时回收
    static ShardHandle submit(Source owner, MeshSink mesh, ShardMeta meta, double distanceSq);  // 唯一提交入口
    static boolean isReady();                                                // 跨 mod gate
    static String stats();
}

public interface Source {
    String id();
    int stageOrder();                       // 与地形层、其他 Source 的相对顺序
    void forEachShard(ShardSink out);       // out.accept(handle, 实例原点, 实例世界包围盒)
    void onInvalidate(Kind kind);           // WORLD / RESOURCES / SHUTDOWN
}

public record ShardMeta(RenderType material, AABB bounds, Vec3 origin, int packedLight) {}
```

关键约定：

- **提交的是 `MeshSink`（L2 产物），不是 `RenderedBuffer`**：库负责在渲染线程把它回放到池化的 `BufferBuilder` 并转交 `uploadChunkLayer`。这样实现方可以在任意线程烘焙网格，符合「GL 对象操作留在渲染线程」的约束。
- **释放只走 `ShardHandle.release()`**：`submit` 返回的句柄初始引用数为 1（提交者持有），同一 mesh 多实例复用时 `retain()`；引用归零后由库在上传完成后回收。世界卸载 / 资源重载 / 注销由库统一 hard reset，此后 `release()` 是空操作。
- **实例原点与包围盒由 Source 在枚举时提供**：去重后同一个缓冲会被多个实例复用，`ShardMeta.origin()` 只是烘焙参考点，不能当作绘制位置，否则所有共享 mesh 的实例会叠在第一个实例上。
- **法线按世界朝向烘焙，光源方向也用世界方向**：原版实体路径的 `Normal` 在 CPU 侧被 pose 的 normal 矩阵乘过，里面含相机视图旋转，因此着色器里 `Light0/1_Direction` 也必须是视图空间方向（`setupShaderLights` 给的就是这个）；静态网格不含视图旋转，若照抄这套方向，点乘两侧不在同一空间，整批几何会统一偏亮/偏暗且随镜头变化。地形层没这个问题，是因为 `rendertype_solid.vsh` 不做方向光。
- **顶点格式与 draw mode 由库钉死**，这是「统一绘制」的前提。建议 `DefaultVertexFormat.NEW_ENTITY`（36 B/顶点）+ `QUADS` 为主、`TRIANGLES` 为辅。SBM 内部的 `BakedGeometryChunk` 同时存在 quads 与 polyMesh 两条流，`MeshSink` 负责补齐 color/overlay 并输出该格式。
- **库不枚举材质**，只负责消费者提供的 `RenderType` 的状态切换；MaterialKey → `RenderType` 的映射由实现方决定。

### 3.2 绘制循环

关键：**不要用 `VertexBuffer.drawWithShader`**。它内部对每个批次做完整 uniform 上传 + `apply()` + `clear()`（`[FORGE-SRC]/VertexBuffer.java:123-186`）；`clear()` 会把 `ShaderInstance.lastProgramId` 置为 `-1`（`ShaderInstance.java:316-320`），而 1.20.1 的 `ProgramManager.glUseProgram` → `GlStateManager._glUseProgram` 没有任何缓存（`GlStateManager.java:185-188`），所以**每个批次都有一次真实的 `glUseProgram`**，不存在「程序切换早已被缓存」。改走 `renderChunkLayer` 的模式：

```java
for (RenderType type : orderedMaterials) {
    type.setupRenderState();
    ShaderInstance sh = RenderSystem.getShader();   // RenderSystem.setShader 立即解析，立即生效
    设置本类型的共享 uniform（12 个 sampler、fog、color、game time、lights ...）;
    for (Shard s : visibleShardsOf(type)) {
        sh.MODEL_VIEW_MATRIX.set(baseView.translate(dx, dy, dz, tmp));
        sh.MODEL_VIEW_MATRIX.upload();              // 1 次 glUniformMatrix4fv
        s.buffer.bind();                            // 1 次 glBindVertexArray
        s.buffer.draw();                            // 1 次 glDrawElements
    }
    sh.clear();
    type.clearRenderState();
}
```

原版用的是 `CHUNK_OFFSET` uniform，这里把偏移烘进 `ModelViewMat`，等价且对 entity 类 shader 更稳妥。

共享 uniform 里**不要**调用 `RenderSystem.setupShaderLights(shader)`：它推送的是被视图旋转预乘过的方向。库应按世界空间方向直接设置 `LIGHT0_DIRECTION` / `LIGHT1_DIRECTION`（下界用 `Lighting.setupNetherLevel` 的那组），与烘焙进网格的世界朝向法线匹配。

注意一个**不要过度承诺**的点：`RenderType` 的身份包含贴图（`TextureStateShard`），`entityCutout(texA) != entityCutout(texB)`。所以「每材质一趟」在「每实例一张贴图」时退化为「每张贴图一趟」。真正的收益是抹掉每批次的固定开销（blend / cull / depthMask / lightmap / overlay / polygon offset 等 10-15 次调用）加上 `apply()` / `clear()` 与重复的 `glUseProgram`。

### 3.3 上传与预算

预算必须分成两段，不可混为一谈：

- **库侧只约束提交端**：每帧 N 个 / M 字节，按到摄像机距离排序，跨 Source round-robin。
- **真正的 drain 在下一帧 `compileChunks`**，`uploadAllPendingUploads` 一次性执行全部、本身无预算。因此提交端必须按顶点数与字节数保守估算，不能只按「个数」限流；在 `AFTER_BLOCK_ENTITIES` 期间提交的内容本帧不可见，时序语义要在 API 文档里写死。
- **网格生产（bake / pose / 填充顶点）的成本由实现方自限**，库不介入；库只负责「本轮该提交什么」的排序与 ready gate。
- 用返回的 future 聚合成跨 Source 的「静态几何就绪」判定，供加载界面 gate 使用。

### 3.4 去重与实例复用

库持有 `Map<MeshKey, SharedBuffer>`，同款实例（乃至跨 mod 的同模型）只上传一份，绘制 N 次不同偏移。

约束：**key 必须包含所有烘进顶点的实例状态**。参考实现把光照烘进 `uv2`（`[WD]/.../client/WallBatches.java:122`），所以严格 key 是 `(mesh, material, light)`。同房间同光照可合并，但不保证。把光照从顶点剥离可以扩大去重范围，但那需要手写 VAO——见第 6 节，已放弃。

结论：**去重是加分项，状态合并才是主收益。** 若做去重，它必须与第 1.3 节的引用计数配套，否则会出现双份释放或永久泄漏。

---

## 4. 参考实现暴露的问题 → 库侧约束

这一节的价值是**约束库的 API 形状**，不是本库的待办清单。参考实现的收集、过期与脏标记策略仍由实现方自己承担。

| 参考实现现状 | 位置 | 库侧对应机制 |
|---|---|---|
| 脏了就整批不画（可见回归）：`!batch.entries.equals(entries) -> continue` | `[WD]/.../client/WallBatches.java:82` | 库的 handle 在重建期间继续持有旧 buffer 引用；新 buffer 就绪后发布、旧 buffer 由实现方 release，绘制不会因 pending 中断 |
| 重建时 `close()` + `new VertexBuffer(...)`，每次产生 GL 对象 gen/delete | `:105`、`:127` | 池化 acquire/release；附带避开 `GlStateManager._glDeleteBuffers` 在 Linux 上的额外 workaround（`:321-330`） |
| 每帧 `O(N·M)` 重建索引：每帧 `new LinkedHashMap`、逐 `(entry, type) new Key(...)`、排序、列表 equals | `:46`、`:51`、`:55`、`:57` | 不属库；库只保证 handle 稳定，实现方自行改为版本号 / 事件驱动失效 |
| 上传与 priming 各有一个每帧配额（正常 2、加载 8） | 上传配额在 `:57`（配合 `WallWarmup.java:101` 的 `prepare(budget, 2)`）；priming 配额在 `:84-87` | 库统一提交预算；实现方的 priming 属于它自己的策略 |
| 半透明材质也在 `AFTER_BLOCK_ENTITIES`，绘制顺序 = HashMap 迭代顺序 | `:73`、`:92-99` | 库提供 `stageOrder` 与确定性排序；是否迁移到 `AFTER_TRANSLUCENT_BLOCKS` 由实现方验证后决定（阶段派发已确认可用，见 6.2） |
| 缓存无上限无引用计数（以完整 NBT 为 key，约 56 B/顶点） | `[WD]/.../client/GunMeshes.java:18`、`MeshCapture.java:23` | 实现方的过期/淘汰策略；库只提供引用计数与回收 API |
| 预热靠离屏真画 | `:84-89` | 实现方策略；`TextureManager.preload` 更接近 vanilla 做法 |

---

## 5. 光照更新

### 5.1 原版基线（本来就很贵）

| 触发 | 入口 | 影响 section 数 |
|---|---|---|
| 逐 section 光照数据变化 | `ClientChunkCache.onLightUpdate` → `LevelRenderer.setSectionDirty` | 1 |
| 光照更新包 | `ClientPacketListener.handleLightUpdatePacket` → `setSectionDirtyWithNeighbors` | **27（3x3x3）** |
| 方块变更 | `ClientLevel.setBlocksDirty` → `LevelRenderer.setBlockDirty(±1)` | 最多 8 |

每个脏 section = 4096 个方块位置全走一遍 + `BlockRenderDispatcher` + `VisGraph`。

节流点仅在**应用光照数据**一侧：`LevelRenderer.renderLevel` 每帧调一次 `level.pollLightUpdates()`（`:1148`），`ClientLevel.pollLightUpdates` 对队列 < 1000 的情况每帧最多处理 `max(10, size/10)`，≥ 1000 时全量。**标脏 → 重烘**一侧没有节流，且距离 < 768 平方格的段落会走 `rebuildChunkSync` 占主线程（`LevelRenderer.java:2116-2123`，可被 Forge 配置 `alwaysSetupTerrainOffThread` 关闭）。

### 5.2 采样语义（参考实现是对的）

`LevelRenderer.getLightColor` 与 `BlockEntityRenderDispatcher.setupAndRender` 给 BE 的是同一个函数（`sky<<20 | block<<4`，emissive 方块直接 `15728880`）。所以「整个实例一个光照值」不是偷懒，是与 vanilla BE 一致的语义。参考实现保留模型自带光照（发光部件用 `LightTexture.pack(15,15)`），`v.light()==0 ? entry.light : v.light()` 只在无自发光时用方块光照——这个分层也是对的。

天空光照值不随昼夜变化（昼夜在 `LightTexture` 里），所以光照只在方块增删、爆炸、区块加载时变；频率可控，但会突发。

### 5.3 实现方动作顺序（库提供支撑）

1. 脏了继续画旧缓冲（由库的 handle/引用计数支撑，消除可见回归）。
2. 重建复用 `VertexBuffer`（库池化）。
3. 版本号 + 事件驱动失效，去掉每帧 `O(N·M)` 索引重建。
4. 重建一律后台 + 自己限预算 + 按距离排序，绝不与 vanilla 的 `rebuildChunkSync` 抢主线程；GL 对象操作回渲染线程执行。
5. 实测后再决定是否需要更进一步。

### 5.4 已排除：光照独立顶点流

把光照拆成独立顶点流（几何 36 B/顶点 → 光照 4 B/顶点，可少一到两个数量级）本来是结构最优解，但需要手工管 VAO。因 Iris 会 patch `VertexBuffer.bind()/unbind()`（第 6.4 节），**此项放弃**。若将来库内自管 VAO 的方案被重新评估，可再打开。

替代手段：后台重建 + 脏画旧 + 按 cell 统一光照采样以减少失效单元数。

### 5.5 实现方的光照变体缓存（示例做法）

光照烘进 `uv2`，所以它是去重 key 的一部分：同光照共享一个 VBO，光照一变就是另一份烘焙。示例侧
（`StaticMeshCache`）按下面的策略把变体数量关进笼子——全部属于 L4，库不参与：

- 缓存分两层：**几何组**（模型 + 贴图 + 朝向 + 材质 + mode）下挂**光照变体**，每个变体一个 VBO。
- 每个几何组保留 `K` 个变体（默认 4），按访问顺序 LRU；超限时**优先淘汰没有实例在用的**（只剩缓存这一份引用），
  都有人用才淘汰最久未访问的那个。淘汰只归还缓存自己的引用：仍有实例引用着的缓冲照常绘制，
  引用归零后由库在上传完成后把缓冲放回池里复用；代价是同光照再回来时重烘一次。
- "这一趟没有几何"只与模型和 mode 有关（与光照、贴图无关），单独记 key，不随光照增长。
- 光照变化时新版先挂 `pending`，上传完成（下一帧）才提升为 `active` 并释放旧版——变化瞬间继续画旧版，
  不会掉一帧；代价是最多同时持有两版 mesh（参考实现正是缺了这一步，见第 4 节第一行）。

仍待补：被剔除（没进入方块实体渲染回调）期间察觉不到光照变化，回到视野前一直用旧值；光照突发时
还没有每帧重烘预算，需要时再按"每帧 N 条 + 按距离排序"补上。

### 5.6 原地只改光照（实验，待构建验证）

光照在 `NEW_ENTITY` 交错布局里的位置是固定的（UV2，顶点 +28 字节、两个 short），所以可以只重写这部分字节，
既不动几何也不动 VAO/属性指针——这与 §5.4 被排除的"独立光照流"是两回事：

- **机制**：`glBufferSubData`（`GlStateManager`/`RenderSystem` 都没包，直接调 LWJGL `GL15`），先绑 `GL_ARRAY_BUFFER`
  到该 VBO，写完绑回 0。需要 VBO 名字，因此用一行 access transformer 把 `VertexBuffer.vertexBufferId`
  （`f_231217_`）放开为 public。
- **运行段**：`MeshSink` 捕获时按"连续顶点 + 相同光照"做 RLE（自发光骨骼之外通常整块一段），库把运行段存进句柄；
  改写时只覆盖等于旧光照的段，其余字节原样保留。
- **合法性**：只有该变体没有别的实例在用时才允许原地改写（`StaticMeshCache` 维护每个 key 的实例计数）；
  否则会连带改掉其它实例的光照，此时退回完整重烘。改写成功后缓存 key 与 LRU 位置一起从旧光照搬到新光照，
  **当帧即可用**，连 `pending` 换挡都不需要。
- **判据要用句柄的真实引用数**（缓存占 1 份，所以"只有我一个用户" ⇔ `references() <= 2`），不要用实现方自己
  维护的 `users` 计数：那个计数在异常路径上可能少记，于是把别人还在用的 VBO 改写掉，表现成"某个模型的亮度坏掉"。
- **GL 绑定要恢复而不是清零**：写之前 `glGetInteger(GL_ARRAY_BUFFER_BINDING)` 记下原绑定，写完恢复原值，
  避免与 Embeddium 这类会缓存绑定状态的东西产生分歧。
- 排查开关：`/sbmstatic relight on|off`，可一键排除"是不是原地改写这条路径引入的渲染异常"。
- **收益**：一次光照变化从"骨骼遍历 + 36 B/顶点重放 + 整块 `glBufferData`"降到"4 B/顶点散写 + 一次 sub-upload"；
  以 1 万顶点的枪模为例：约 360 KB → 40 KB。
- **统计**：`staticWorld{... lightUpdates=N}`，与 `submits` 对照即可看出有多少次光照变化走了原地路径。
- **风险**：这是库内第一处裸 GL 调用，必须在渲染线程、不要落在 Sodium 的 managed code 区段里；因为不新建 VAO、
  不碰 `glVertexAttribPointer`，所以不涉及 Iris 那条红线（第 6 节），但仍需在 Embeddium/Oculus 下实测确认。

### 5.7 淘汰策略的两个硬约束（实测踩到）

实测出现过 `bakes ≈ evictions`（29k 量级，等于每帧重烘）并伴随画面闪烁，根因是淘汰规则的实现顺序：

- **刚烘出来的变体不能被自己挤掉**。`trim` 在 `bake` 内部执行，此时新句柄的引用数还是 1（实例的 `retain`
  要等调用方稍后做），于是"优先淘汰只剩缓存引用的变体"这条规则恰好命中了它：烘完立刻被回收，
  `ensure` 随后查回 null、返回空 key 列表，当帧该实例退回动态路径。下一次光照/几何一变又重烘，形成每帧循环，
  而静态与动态两条路径交替绘制同一个实例，表现为**表面闪烁 / 部分部件时有时无**。
- **其余变体都在用时，宁可超上限也不淘汰**。淘汰一个正被实例引用的变体只是归还缓存那一份引用——缓冲还被实例
  握着，内存一点没省——却让下次查询必然 miss 再重烘。K 的职责是清理**过期**变体，不是和活跃工作集对抗。

因此内存的真实上界是**同时活跃的 (几何, 光照) 组合数**（每个活跃实例当前用的 mesh 必须存在），而不是 K；
要压这个上界得减少"同时可见的光照值数量"（例如按 cell 统一采样），不是把 K 调小。
统计里的 `overflows` 就是"因其余变体都在用而放弃淘汰"的次数，持续增长说明活跃工作集 > K。

---

## 6. 兼容性红线

| 做法 | Embeddium | Oculus |
|---|---|---|
| `glBufferData`（经 `VertexBuffer.upload`） | 安全 | 安全 |
| 复用 VertexBuffer、不 close+new | 安全 | 安全 |
| 池化后跨顶点格式复用同一 buffer | 不安全（VAO 属性会被重设） | 不安全 |
| 手写 VAO / 多顶点流 | 有状态跟踪器分歧风险 | **高风险** |
| 依赖 `MultiBufferSource.endBatch` | 安全 | **静默失效** |
| 材质放到 `AFTER_*_BLOCKS` 阶段 | **已确认派发**（6.2） | 需实机确认排序 |
| 在 `AFTER_BLOCK_ENTITIES` 绘制 | — | shadow pass 不触发（6.3） |

### 6.1 `glBufferData` 为什么安全

- `VertexBuffer.upload()` 内部就是 `RenderSystem.glBufferData(34962, ...)` → `GlStateManager._glBufferData` → `GL15.glBufferData`（`[FORGE-SRC]/GlStateManager.java:300-308`）。参考实现现在就在用。
- 原版 `GlStateManager` **没有** buffer 绑定缓存：`_glBindBuffer` 直接转发（`:290-293`），只有 blend 有缓存。不存在「vanilla 缓存与实际状态分歧」。
- `glBufferData` 重新分配的是同一个 buffer 对象的存储，VAO 属性指针引用的是对象名而非存储区，对已建好的 VAO 无影响（前提：顶点格式不变；格式变了要走 `format.setupBufferState()`，要求当前绑定的是自己的 VAO）。
- Embeddium 0.3.31 的 `VertexBufferMixin` 只改写了 `_drawWithShader` 的 sampler 数量（`ModifyExpressionValue`），不碰 `upload` / `close` / `bind`，因此池化与复用不受影响。

**使用规则**：保持 `bind() -> upload() -> unbind()` 顺序。`upload()` 内部会 `glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ...)`（`VertexBuffer.java:80`），而 element array 绑定是**写进当前 VAO** 的；若未先绑自己的 VAO，会污染别人的（例如 Embeddium 的区块 VAO）。

### 6.2 已确认：Embeddium 仍派发 `AFTER_*_BLOCKS`

对 `[EMB]` 反编译 `me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin` 可直接看到：

- 它以 `@Overwrite` 接管了 `LevelRenderer.renderChunkLayer`（混淆名 `m_172993_`）：进入 `RenderDevice.enterManagedCode()` → `SodiumWorldRenderer.drawChunkLayer(...)` → `exitManagedCode()`；
- 随后调用 `ForgeHooksClient.dispatchRenderStage(renderType, levelRenderer, poseStack, projectionMatrix, ticks, camera, frustum)`，再做 `clearRenderState()`。

因此 Forge 的 `AFTER_SOLID_BLOCKS` / `AFTER_CUTOUT_MIPPED_BLOCKS` / `AFTER_CUTOUT_BLOCKS` / `AFTER_TRANSLUCENT_BLOCKS` / `AFTER_TRIPWIRE_BLOCKS` 在 Embeddium 下仍然按 RenderType 派发。`AFTER_BLOCK_ENTITIES` 本就由 `renderLevel` 直接派发（`LevelRenderer.java:1325`），不受地形管线替换影响。

剩余待办只是实机留档（第 8 节）。

### 6.3 已确认：Oculus 的 shadow pass 不触发 `AFTER_BLOCK_ENTITIES`

静态证据链（均出自 `[OCULUS]`）：

- Iris 在 `LevelRenderer.renderLevel` 上注入 `iris$renderTerrainShadows`，调用 `LevelRendererAccessor.renderShadows` → `ShadowRenderer.renderShadows`。
- shadow pass 的方块实体由 `MixinSodiumWorldRenderer` 接管：它注入 `SodiumWorldRenderer.renderBlockEntities(...)`、把逐 BE 调用重定向到 `ShadowRenderingState.BlockEntityRenderFunction`，再由 `ShadowRenderingState.renderBlockEntities(...)` 回放 `SodiumWorldRenderer.renderBlockEntity(...)`。

也就是说，shadow pass 走的是 Sodium 的方块实体路径，**不重入 `LevelRenderer.renderLevel`**；而 `AFTER_BLOCK_ENTITIES` 的派发点在 `renderLevel:1325`。结论：

- 静态几何不会在 shadow pass 被重画（不存在「双重绘制」问题）；
- 静态几何也不会进入阴影贴图，**不会投影**。

是否需要让静态几何参与投影是产品决策。若需要，必须另开一条 shadow 路径（并自行处理 shadow shader / framebuffer 状态），而不是依赖 `AFTER_BLOCK_ENTITIES`。

### 6.4 Oculus 的具体证据

来自 `[OCULUS]`：

1. **`net/irisshaders/iris/mixin/MixinVertexBuffer`** patch 了 `bind()V` 与 `unbind()V`，调用 `VertexBufferHelper.saveBinding()` / `restoreBinding()`（静态影子字段 `current`）。
   含义：走 `VertexBuffer` API 是被 Iris 主动维护的状态路径；手写 `glBindVertexArray` / `glVertexAttribPointer` 会绕过 save/restore。
2. **`net/irisshaders/batchedentityrendering/impl/FullyBufferedMultiBufferSource`**：内部为 `SegmentedBufferBuilder` + `BufferSegmentRenderer` + `RenderOrderManager`，几何全部攒着由 Iris 在受控时机统一 flush。其 `endBatch` 不是真 flush。
3. **`net/irisshaders/iris/shadows/ShadowRenderingState`**（`areShadowsCurrentlyBeingRendered`、`renderBlockEntities`、`setBlockEntityRenderFunction`）。
4. 原报告引用的 `MixinPreventRebuildNearInShadowPass` 在 Oculus 1.7.0 里**只有构造函数**（`javap -p` 可见：一个空 mixin），不能作为「阻止近处重建」的证据。真正做这件事的是 `net/irisshaders/iris/compat/sodium/mixin/shadow_map/MixinRenderSectionManager#cancelIfShadow`（注入 `update`）与 `useShadowRenderList`。结论（shadow pass 期间不做普通重建）成立，证据换到这里。

### 6.5 TACZ 的 Oculus 兼容层：设计可借鉴，代码不可复用

`[TACZ]` 已有按 Oculus 版本分派的兼容层：

- `com/tacz/guns/compat/oculus/OculusCompat.java`（`isUsingRenderPack()`、`isRenderShadow()`、`endBatch()`）
- `IUnflushableWrapper.java` + `mixin/client/oculus/UnflushableWrapperAccessor.java`
- `mixin/client/oculus/OculusMixinPlugin.java` + `resources/tacz.oculus.mixins.json`
- 调用点：`FirstPersonRenderEvent`、`BedrockModel`、`MuzzleFlashRender`、`ShellRender`、`BedrockAttachmentModel`

**不能照搬代码**：TACZ 依赖 SBM（`jarJar(implementation(...simplebedrockmodel...))`），SBM 反向依赖 TACZ 是循环依赖。库需要自己实现等价能力（或由实现方自行调用所在生态的兼容层，例如依赖 TACZ 的参考实现可以直接调 `OculusCompat`）。这层的存在说明「Oculus 兼容」是每个渲染 mod 都要写一遍、写错还静默失效的东西，属于最值得由库固化的部分。

---

## 7. SBM 原型计划

### 7.1 包布局

- `v2/common/model/baked`（已有）：补 `MeshSink`（把渲染输出导向打包数组）与 `StaticMeshCache`（引用计数 + 淘汰钩子）。
- `v2/client/world`（新增）：`@ApiStatus.Experimental`，独立 mixin json，独立运行开关，不启用时零开销。材质键（`MaterialKey`）由消费者提供，库内不硬编码 `RenderType` 枚举，但绘制通道负责消费者给出的 `RenderType` 的 state 切换。

### 7.2 原型范围

**只接一个 Source**，用 SBM 自己的 example 方块实体渲染器做 Source。参考实现不作为本库的接入目标，它的价值是第 4 节的约束清单。

### 7.3 步骤

| 步骤 | 内容 | 风险 |
|---|---|---|
| 1 | 抽出 `Source` / `ShardMeta` / `ShardHandle` 契约，库内实现 VBO 池与上传提交 | 低 | **已完成** |
| 2 | 实现统一绘制通道（单 Source 即可测出合并绘制与固定开销的收益） | 中 | **已完成** |
| 3 | 接 example Source，跑通四种环境并留档 | 中 | Source 已接，环境未跑 |
| 4 | 跨 mod 部分（共享预算、跨 Source 去重、ready gate 聚合）等第二个消费者出现后再做 | 中 | 未开始 |

### 7.5 原型产物（2026-09-18）

库侧：

| 文件 | 作用 |
|---|---|
| `v2/common/model/baked/MeshSink.java` | 打包顶点捕获 + 回放；钉死 `NEW_ENTITY` / QUADS / TRIANGLES |
| `v2/client/world/Source.java` | 消费者契约（`forEachShard` / `onInvalidate` / `stageOrder`） |
| `v2/client/world/ShardMeta.java` | 材质、世界包围盒、原点、烘焙光照 |
| `v2/client/world/ShardHandle.java` | 引用计数句柄；唯一释放入口 |
| `v2/client/world/VboPool.java` | 按顶点格式分桶的缓冲池（只池化，不子分配） |
| `v2/client/world/StaticWorldRenderer.java` | 提交、上传 future、单一阶段监听、统一绘制、统计 |

示例侧：

| 文件 | 作用 |
|---|---|
| `example/.../staticworld/ExampleStaticSource.java` | 把 TestBlock 的两个模型烘成静态几何；自带去重、引用计数与 128 格过期策略 |
| `example/.../staticworld/ExampleStaticCommands.java` | `/sbmstatic on|off|stats|drop` |
| `example/.../TreeTestBlockEntityRenderer.java` | 开启时只登记实例，绘制交给库 |

已知边界（本次刻意不做，留给后续）：

- 烘焙仍在渲染线程（`MeshSink` 已经允许后台，示例只是没搬）；
- 提交预算与按距离排序只记录了 `distanceSq`，没有真正限流；
- 示例的 TEST 模型是动画模型，静态路径渲染的是绑定姿势（POLY_MESH_TEST 本来就静态，观感应与动态路径一致）；
- 跨 Source 去重与 ready gate 聚合未接（只有一个 Source）。
- 世界卸载时若某个上传还没被 drain，该缓冲会随旧 `ChunkRenderDispatcher` 的队列一起搁置（不会崩，但也不再回到池里）；后续可以用"超时强制回收"补齐。
- entity 类 shader 的雾距用 `IViewRotMat * Position` 计算，而静态网格的 Position 是模型局部坐标，所以雾距误差约为模型自身半径（小块可忽略，超大模型需要另行处理）。

### 7.6 压测挂具（2026-09-18）

要回答的是「实例各不相同、去重失效时静态化还剩多少」。从 TACZ 默认枪包拷入 24 组 gun geo + 对应贴图
（`assets/example/models/bedrock/stress/`、`assets/example/textures/stress/`，属 example 命名空间，打包时被排除），
用**虚拟实例**（不落世界、不产生方块实体）在玩家前方排阵，同一份实例数据可在静态与动态之间切换：

| 命令 | 场景 | 静态侧的账 |
|---|---|---|
| `/sbmstatic stress same 48` | 同模型 + 同贴图 | 1 个 VBO、1 个材质组、48 次 draw |
| `/sbmstatic stress models 48` | 24 个不同模型 + 同一贴图 | 24 个 VBO、1 个材质组、48 次 draw |
| `/sbmstatic stress all 48` | 24 个不同模型 + 24 张贴图 | 24 个 VBO、24 个材质组 → 每实例一次 setup/clearState |
| `/sbmstatic stress dynamic 48` | 同 `all`，但走动态路径（装了 AR 即 AR 路径） | 每帧骨骼遍历 + MultiBufferSource |

判定口径在 `/sbmstatic stats`：`frameMs`（相邻两次 `AFTER_BLOCK_ENTITIES` 间隔的 120 帧均值，等价于 F3 的帧时间）、
`passUs`（本层绘制自身耗时）、以及 `draws` / `setups` / `clears` / `pool{created,reused,idle}`。

预期结论（待实测）：**模型是否相同几乎不影响**——去重只省显存与一次性上传；真正决定收益的是**材质是否相同**。
`all` 下每实例一次材质切换会把固定开销吃回来，相对 `models` / `same` 明显变慢，但相对 `dynamic` 仍应保有
「省掉逐帧骨骼遍历」的收益。

### 7.4 验收判据（可测量）

- 每帧每批次固定开销对应的 GL 调用数：从「批次数 ×（setup/clearState + 约 35 次 uniform/apply/clear）」降到「RenderType 数 × 一次 setup + 批次数 × 3」。注意「每贴图一趟」是预期结果，不能用「RenderType 数量是否下降」判成败。
- 稳态每帧分配：`groups` / `Key` / `ArrayList` / `Entry` 归零（针对复刻参考实现结构的 Source）。
- 光照突发：放置/破坏光源前后各跑一次统计，记录上传顶点数与 draw 数。
- GL 对象 churn：重建前后 `glGenBuffers` / `glDeleteBuffers` 计数为 0（池化生效）。
- 四种环境各跑一遍并留档：纯原版 / Embeddium / Oculus（光影开）/ Oculus + 阴影开；重点看 shadow pass 是否重画（预期否）、半透明部件观感、以及是否出现状态污染。
- 复用参考实现现成的 `-Psmoke runClient` 100 实例压力与资源重载思路（若 Source 复用其结构）。

---

## 8. 待实测确认

1. 四种环境实机留档：阶段派发与 shadow pass 行为已有静态结论（6.2 / 6.3），仍需一次真实运行确认，尤其是 shader pack 下的观感与状态。
2. 静态几何是否需要参与阴影投影（产品决策）。若要，需先设计 shadow 路径。
3. `NEW_ENTITY` + entity 类 `RenderType` 的统一绘制契约在 Oculus 光影下的正确性（sampler / uniform / overlay 语义）。
4. 半透明 shard 是否迁移到 `AFTER_TRANSLUCENT_BLOCKS`，以及迁移后的排序与观感。

---

## 9. 明确不做

- 不做共享 VBO 的**子分配**（一个大 VBO 切区间、自管子偏移）；池化与复用要做。
- 不做手写 VAO / 多顶点流 / 实例化（第 6 节）。
- 不共享策略：视距、剔除、光照采样、脏标记语义、实例收集与过期淘汰留在实现方。
- 不替实现方决定半透明阶段的归属，直到第 8.4 项验证完成。
- 不共享参考实现那套「渲染并捕获」机制（SBM 本来就有 `renderToBuffer(..., VertexConsumer, ...)`）。
- 不试图接进原版 chunk buffer：`ChunkRenderDispatcher` 硬绑 `RenderType.chunkBufferLayers()`（仅 solid / cutoutMipped / cutout / translucent / tripwire）、`DefaultVertexFormat.BLOCK` 与方块图集，且 `RegisterNamedRenderTypesEvent` 有 `getChunkLayerId() >= 0` 前置断言（`[FORGE-SRC]/RegisterNamedRenderTypesEvent.java:66-67`），Forge 1.20.1 无法新增 chunk layer。

---

## 附录 A：关键代码坐标

**参考实现（`[WD]`）**

| 文件 | 关注点 |
|---|---|
| `[WD]/.../client/WallBatches.java` | `:18-35` 数据结构；`:42-63` 每帧索引重建（`:45` 重置 priming 预算，`:57` 上传配额）；`:72-101` 绘制与 priming；`:102-133` rebuild |
| `[WD]/.../client/GunMeshes.java` | `:18` 无上限缓存；`:27-60` bake；`:41-48` FIXED 变换烘入顶点；`:61-76` normalize |
| `[WD]/.../client/MeshCapture.java` | `:23` `Vertex` record（10 字段） |
| `[WD]/.../client/WallWarmup.java` | `:94-102` 帧调度、门限、预算 |
| `[WD]/.../client/BatchLayout.java` | 2x2x2 cell，`MAX_GUNS = 8`（等于 cell 容量） |
| `[WD]/VERIFICATION.md` | 既有实测数据（帧时间、上传计数、预加载耗时） |

**原版**

| 关注点 | 位置 |
|---|---|
| 上传队列 | `ChunkRenderDispatcher.java:71`（队列类型）、`:196`（drain）、`:225`（uploadChunkLayer，public） |
| 脏标记语义 | `ChunkRenderDispatcher.java:303`（`compiled` 是 AtomicReference）、`:394-411`（setDirty 不清 compiled）、`:579`（成功后才 set） |
| 绘制循环 | `LevelRenderer.java:1473-1591`（`renderChunkLayer`） |
| 阶段派发 | `LevelRenderer.java:1589`（per-RenderType）、`:1184/1260/1325`（通用阶段） |
| 每帧 drain | `LevelRenderer.java:1190`（compileChunks）→ `:2133`（uploadAllPendingUploads） |
| 光照失效 | `LevelRenderer.java:1148`、`:2415-2462`；`ClientChunkCache.java:181`；`ClientPacketListener.java:2460` |
| 光照采样 | `LevelRenderer.getLightColor`（`sky<<20 \| block<<4`，emissive 返回 15728880） |
| 顶点格式/VAO 语义 | `VertexBuffer.java:54-101`（upload/bind）；`VertexFormat.java:71-103` |
| GL 无缓存证据 | `GlStateManager.java:185-188`（无 program 缓存）、`:290-308`（无 buffer 缓存）；Linux 删除 workaround `:321-330` |
| chunk layer 限制 | `RegisterNamedRenderTypesEvent.java:66-67` |

**Embeddium（`[EMB]`）**

| 关注点 | 位置 |
|---|---|
| 阶段派发 | `me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin`（`@Overwrite` `renderChunkLayer` → `drawChunkLayer` → `ForgeHooksClient.dispatchRenderStage` → `clearRenderState`） |
| 不干扰池化 | `me/jellysquid/mods/sodium/mixin/features/render/immediate/buffer_builder/VertexBufferMixin`（只改 `_drawWithShader` 的 sampler 数量） |

**Oculus（`[OCULUS]`）**

| 关注点 | 位置 |
|---|---|
| bind/unbind save-restore | `net/irisshaders/iris/mixin/MixinVertexBuffer`、`net/irisshaders/iris/helpers/VertexBufferHelper` |
| 缓冲式 MultiBufferSource | `net/irisshaders/batchedentityrendering/impl/FullyBufferedMultiBufferSource` |
| 阴影状态 | `net/irisshaders/iris/shadows/ShadowRenderingState`、`net/irisshaders/iris/shadows/ShadowRenderer` |
| shadow pass 的方块实体 | `net/irisshaders/iris/compat/sodium/mixin/shadow_map/MixinSodiumWorldRenderer` |
| shadow pass 不做普通重建 | `net/irisshaders/iris/compat/sodium/mixin/shadow_map/MixinRenderSectionManager#cancelIfShadow`（`MixinPreventRebuildNearInShadowPass` 是空类） |

**TACZ Oculus 兼容**：见 6.5 节。

---

## 附录 B：测量手段

- 参考实现自带 `/wallgun_stats`，输出 `bakes / failures / uploads / draws / visible / cachedBatches / uploadedVertices / maxBatchGuns / pendingModels / pendingBatches / warming`。
- 光照突发测量：站在展示墙前记录基线 → 在墙中间放置/破坏光源 → 立刻再记录，重点看 `uploadedVertices` 与 `pendingBatches`。
- 绘制合并收益测量：统计每帧每批次固定开销对应的 GL 调用次数与帧时间；不要以 `setupRenderState` 调用次数或 `RenderType` 数量作为唯一指标。
- GL 对象 churn 测量：统计 `glGenBuffers` / `glDeleteBuffers`，池化生效时应为 0。
- 既有冒烟用例（参考实现）：`gradlew --offline -Psmoke -PlodSmoke runClient`、`-Psmoke -PwarmupSmoke runClient`（100 原型存档、资源重载、新区域预加载）。
- 环境矩阵：纯原版 / Embeddium 0.3.31 / Oculus 1.7.0（光影开）/ Oculus + 阴影开。SBM 的 `build.gradle` 已声明 Embeddium（`maven.modrinth`）与 `libs/oculus`；跑矩阵前确认依赖可解析（本机的 Embeddium jar 在 `D:\Minecraft\cf\Instances\test\mods`）。

---

## 修订记录

**v3（2026-09-18）**

- 落地原型：库侧 `v2/client/world` 与 `MeshSink`，示例 Source 挂在 TestBlock 上，新增 `/sbmstatic` 运行开关与统计（7.5）。
- 按实现修正契约：`submit` 接收 `MeshSink` 而非 `RenderedBuffer`，`Handle` 定名为 `ShardHandle` 并明确 retain/release 与 hard reset 语义（3.1）。
- 记录原型边界：烘焙仍在渲染线程、提交预算未限流、TEST 模型冻结在绑定姿势、跨 Source 部分未接（7.5）。

**v3.1（2026-09-18）**

- 修复去重实例只画出一个的缺陷：实例绘制原点与世界包围盒改由 `forEachShard` 逐实例提供，`ShardMeta.origin()` 明确为烘焙参考点（3.1）。
- `ShardHandle.isAlive()` 收紧为「未失效且未回收」，示例侧 mesh 缓存命中已回收句柄时惰性清除并重烘。

**v3.2（2026-09-18）**

- 修复静态几何光照偏亮/偏暗：法线按世界朝向烘焙后，`Light0/1_Direction` 必须用世界方向，不能复用 `setupShaderLights` 的视图空间方向（3.1 / 3.2）。
- 记录法线空间契约：`MeshSink` 只接受世界朝向法线（不含相机视图旋转）。
- 备注：参考实现把局部朝向法线烘进顶点、又用 `drawWithShader` 走视图空间光照，存在同一处潜在偏差，只是枪械模型上不易察觉，不宜照抄。

**v3.3（2026-09-18）**

- 新增压测挂具：从 TACZ 默认枪包拷入 24 组模型与贴图，虚拟实例排阵，`same / models / all / dynamic` 四模式（7.6）。
- 统一绘制层补帧时间统计：`frameMs`（120 帧均值）与 `passUs`（本层耗时），用于不依赖 F3 的 A/B。
- 示例侧抽出 `StaticMeshCache`，方块实体 Source 与压测 Source 共用同一套烘焙/去重/引用计数逻辑。

**v3.4（2026-09-18）**

- 光照变体按几何组做 LRU：每组保留 K 个（默认 4），超限优先淘汰无实例引用的最旧变体，淘汰只归还缓存自己的引用（5.5）。
- "这一趟没有几何"的标记改为只与模型 + mode 相关，不再随光照值增长。
- 示例 Source 引入 `active` / `pending` 换挡：新光照的 mesh 上传完成后才替换旧版，修掉光照变化时掉一帧的问题（5.5）。

**v3.5（2026-09-18）**

- 新增"原地只改光照"实验：`MeshSink` 记录光照运行段，库侧 `StaticWorldRenderer.rewriteLight` 用 `glBufferSubData`
  只覆盖 UV2 字节；示例侧在"变体无其它用户 + 目标光照未缓存"时用它代替重烘（5.6）。
- 为此放开 `VertexBuffer.vertexBufferId`（access transformer `f_231217_`），并在统计里加 `lightUpdates`。

**v3.6（2026-09-20）**

- 修复淘汰策略导致的重烘风暴与画面闪烁：`trim` 不再把"刚烘出来、实例尚未 retain"的变体当作淘汰目标；
  其余变体都在用时放弃淘汰（宁可超过 K），避免"淘汰在用的变体省不下内存却必然引发重烘"（5.7）。
- 统计加 `overflows`，用于判断活跃工作集是否超过 K。

**v3.7（2026-09-20）**

- 原地改写的准入判据改用句柄真实引用数（`references() <= 2`），避免 `users` 计数少记时改写别人在用的 VBO（5.6）。
- `glBufferSubData` 前记录并恢复 `GL_ARRAY_BUFFER` 绑定，不再清零，避免与 Embeddium 的绑定缓存分歧。
- 新增 `/sbmstatic relight on|off` 开关，用于隔离原地改写路径是否引入渲染异常。

**v2（2026-09-18）**

- 修正 Embeddium 结论：原「未找到 jar、无法静态确认」作废；已用本机 0.3.31 反编译确认 `AFTER_*_BLOCKS` 仍然派发（6.2）。
- 修正 shadow pass 结论：原第 8 节的三个相关开放项作废；已确认 `AFTER_BLOCK_ENTITIES` 在 shadow pass 不触发，静态几何不会重画也不会投影（6.3）。
- 修正程序切换结论：原「程序切换早已被 ProgramManager 缓存」作废；1.20.1 无 program 缓存，且 `drawWithShader` 的 `clear()` 会重置 `ShaderInstance.lastProgramId`（3.2）。
- 修正证据链：`MixinPreventRebuildNearInShadowPass` 在 Oculus 1.7.0 是空类，改引 `MixinRenderSectionManager#cancelIfShadow`（6.4）。
- 重划 L3 职责：VBO 池化与统一绘制入库（单消费者即成立）；实例收集、过期淘汰、脏标记、网格生产归实现方；补 handle/引用计数与释放契约（1.1-1.3）。
- 池化与子分配分离：池化要做，子分配不做（第 9 节）。
- 钉死顶点格式与 draw mode 契约，修正原 `Shard` 缺字段的问题（3.1）。
- 预算拆成「库侧提交端」与「实现方网格生产」两段（3.3）。
- 删除 TACZ 迁移相关的开放项与「装饰枪改用 SBM bake 层」的步骤；参考实现只作为约束来源（背景、4、7.2）。
- 删除「整层复用 TACZ Oculus 兼容层代码」的说法，改为设计可借鉴、代码不可复用（6.5）。
