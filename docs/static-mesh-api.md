# 静态网格 API 与实现结构

更新日期：2026-10-07。VBO 优化尚未正式发布，没有线上调用方。本轮直接替换原型 API 和包路径，不保留兼容适配器。

## 按职责组织

包根为 `com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh`，具体实现按数据与执行职责拆分：

| 包 | 直接职责与主要类型 |
| --- | --- |
| `mesh` | 临时提交入口、scope 和结果：StaticMeshRenderer、StaticMeshBufferSource、MeshDrawResult、MeshSubmitResult、MeshFlushStats；资源监听 MeshLifecycle |
| `mesh.capture` | CPU 几何捕获与图元分流：GeometryCollector、MeshGeometryProvider、MeshSink、LightPassVertexRouter |
| `mesh.cache` | 临时共享缓存、捕获预算、引用和失效：StaticMeshCache、IdleMeshCache、MeshCachePolicy |
| `mesh.gpu` | VBO 池、上传/回收、光照流、整数属性与格式：VertexBufferPool、WorldMeshBuffers、WorldMeshPart、MeshIntegerAttributes、LightRangeUpdates、MeshLighting、MeshVertexFormat |
| `mesh.render` | 两类实例共用的材质/shader/VAO draw：MeshBatchRenderer、MeshDrawTransform、EmissiveMeshRenderTypes |
| `mesh.world` | 长期对象、世界阶段、剔除、INSTANCE/SECTION、每组 CPU 几何缓存与统计 |
| `mesh.debug` | 开发属性对照：MeshRenderDebug |

这次拆分同时移动职责：StaticMeshRenderer 不再持有缓存表、捕获队列预算或上传释放状态；这些归 StaticMeshCache。WorldMeshRenderer 不再直接上传、回收 GPU 缓冲或维护光照流；这些归 WorldMeshBuffers。两种来源共用 MeshBatchRenderer 的材质/shader/VAO 执行和清理，不再各写一套 draw 循环。

不使用 `final class`，实现类和可公开的方法全部 `public`。不通过访问限制规定使用路线，也不增加公开接口的转发 facade。GeometryCollector 的构造、Pass、snapshot、MeshSink 和 GPU 句柄可直接使用；标准入口仍在捕获回调中提供 collector。MeshRenderDebug 只保留真实开发属性覆盖，不再转发几何统计或光照分流。

## 临时网格

```java
ResourceLocation owner = new ResourceLocation("mymod", "static_items");

try (StaticMeshBufferSource batch = StaticMeshRenderer.openBatch(
        owner, StaticMeshRenderer.BatchOrder.MATERIAL, pass::isCurrent)) {
    MeshSubmitResult result = batch.submit(key, previousKey, provider,
            pose, light, overlay, Duration.ofSeconds(30));
    // provider 在 submit 内按预算同步调用；返回后不会保存 provider 或业务对象。
    // result.hasMesh() 后，可立即按 result.usedPrevious() 选择动态效果的模型。
    batch.endBatch();
}
```

`SUBMISSION` 保留调用和 Part 顺序，只合并连续相同材质；`MATERIAL` 明确允许按实际 RenderType 和共享 Part 重排。同材质 setup/apply/clear 一次，同网格 bind 一次，实例仍单独 draw。透明排序仍由调用方负责。

| 操作 | 契约 |
| --- | --- |
| `submit` | 准备目标，或选择已经完整就绪的兼容 previous；复制完整 modelView 和方向光逆线性变换，pin 选中条目 |
| `flush` | 排空已有命令、释放引用，继续接受本 pass 后续提交；只返回本次增量统计 |
| `endBatch` | 执行最后一次 flush 后关闭；不累加此前统计 |
| `discard` / `close` | 幂等取消并关闭，不隐式绘制 |

`QUEUED` / `QUEUED_PREVIOUS` 仅表示选择了完整网格并入队，不等于 DRAWN。`PENDING` 仍接管此次调用，不运行旧模型。只有 `UNSUPPORTED` 允许旧路径。

世界、GL context、投影、资源/格式代次失效时，排空会取消旧命令。owner 失效时，仅剔除其旧条目对应的命令。零 retention 不会关闭仍被命令持有的网格；旧命令释放也不会把失效条目放回新缓存。

绘制异常可能发生在部分 Part 已提交后。库清理 shader、VAO、材质和参数缓冲绑定，取消剩余引用，关闭 scope 并抛出异常；调用方不得在同一次 pass 运行 previous 或旧枪体 fallback。正常结束用 endBatch，异常 finally 用 close。

即时入口仍有独立用途：未接入队列的已验证调用可以使用同一缓存直接绘制。

```java
MeshDrawResult result = StaticMeshRenderer.tryDraw(
        owner, key, previousKey, provider, pose, light, overlay, retention);
```

没有 beforeDraw / beforeFlush 空回调。需要协调原版缓冲时，pass 所有者直接在确定的边界调用原版提交与网格 flush；库不会解包 MultiBufferSource 或接管材质调度。

## 内部职责

| 实现 | 职责 |
| --- | --- |
| `StaticMeshCache.Entry / Part` | 临时共享网格、引用、准备预算、重试、上传 future 与整数属性状态 |
| `StaticMeshBufferSource.Command` | 短期命令；只持有选中网格和实例参数 |
| `WorldMeshGroup`、`GeometryCache`、`InstanceMeshBatches`、`SectionMeshBatches` | 长期对象、捕获任务、共享缓存、剔除与策略换版 |
| `WorldMeshBuffers / WorldMeshPart` | 世界网格 GPU 所有权、异步上传后的回收、可变光照流 |
| `MeshBatchRenderer` | 两种来源共用的材质状态、共享 uniform、矩阵/方向光、VAO bind/draw 与异常清理 |
| `MeshIntegerAttributes` | 临时网格 UV1/UV2 的整数参数表及每 Part 来源缓存 |
| `MeshSink`、`LightPassVertexRouter`、`MeshVertexFormat` | CPU 捕获、光照分流与实际格式探测 |
| `VertexBufferPool`、`IdleMeshCache` | 资源池与空闲淘汰 |

世界绘制保留 UNIFORM/FIXED/MUTABLE 属性策略；临时物品保留整数参数表和动态 overlay。统一绘制后端不把这两套真实属性需求混成一套可配置公开策略，也不把物品登记成世界对象。

缓存和捕获/上传的生命周期仍由各入口拥有：世界 CPU 几何还需要供 SECTION 拼接，临时缓存只需持有 Part。共用执行后端不强行合并这两类存储。INSTANCE 的 active/pending 直接由 Resident 持有，删除只服务单处的泛型 MeshSwap 回调包装。WorldMeshPart 直接持有所属 WorldMeshBuffers，释放不再回调全局 WorldMeshRenderer。

## owner 与生命周期

临时缓存键为 `(ResourceLocation owner, geometryKey)`。`invalidateOwner(owner)` 只失效该接入方；资源重载、世界卸载、实际格式变化和 GL context 变化由库统一处理。关闭一个物品优化开关不再清空其他模组的临时缓存。

geometryKey 必须包含影响捕获结果的所有状态，包括实际 LOD、材质、视角变换及光影烘入顶点的语义 IDs。位置、每帧 pose、light 和 overlay 不进入 key。需要光影 geometrySalt 时，接入方把不可变 salt 组合进自己的 key；兼容 previous 也必须带相同捕获上下文。

所有操作在渲染线程执行。调用方可在 submit 后立即 pop PoseStack。队列只保存矩阵及整数参数，当前共享 shader 状态仍需在排空前保持稳定；传入的 isCurrent 应校验所属 pass、目标和 pipeline 身份。同投影不代表同 pass。

## 本轮明确保留的边界

本轮完成库结构和 API 清理，没有新增 TaCZ 实体/shadow hook、Oculus context 快照或 outline 双路绘制。`MeshPassBinding`、`MeshRenderContext`、通用 native 材质 bridge 仍是设计候选，不作为本轮公开接口。

TaCZ 下一步应直接迁移新包与新结果类型，使用固定 owner，并在真实 pass 接入时补齐共享光照/颜色快照、实际材质 wrapping 和烘入顶点的语义 IDs。激光和枪口仍在调用期生成，枪体在明确 pass 边界排空。未知 pass 的即时 VBO 不属于弃用路线。

版本号没有在本轮提升或发布；现有 `2.5.23-forge-mc1.20.1` 字段是工作区状态，不能据此推断远程依赖含有这些 API。正式发布使用新坐标，再同步 TaCZ 最低依赖范围。

本轮不新增单元测试。验证采用 Java 编译、打包、源码引用检查及隐藏 GL 像素检查；原有矩阵/整数属性测试只迁移包名。游戏内世界组、材质合批性能和 TaCZ 主场景/shadow 仍需实机验收。

本轮已通过 `compileJava reobfJarJar sourcesJar --offline`。正式 jar 核对不含旧 `client.world`、平铺实现类、MeshSwap 或 example 资源。隐藏 OpenGL 3.3 属性检查通过，覆盖同一作用域连续 32 次 draw、交替 VAO、变化 light/overlay、固定发光、绑定被改写及参数表重建；这项检查不覆盖游戏 shader 和实际实体/shadow pass。
