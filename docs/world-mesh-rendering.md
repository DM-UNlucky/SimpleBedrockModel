# 世界网格渲染组：分工、API 与策略覆盖

更新日期：2026-09-23。本文描述当前已落地的渲染组接口；原型接口直接迁移，不保留 Source 或手动句柄接口的兼容层。

## 分工

对象自身是状态的权威来源。调用方只登记对象或适配器，SBM 遍历登记项，通过回调维护渲染表示。渲染组不按 BlockPos 主动查询世界。

| 调用方负责 | SBM 负责 |
|---|---|
| 收集哪些对象、捕获哪些几何，提供资源标识与外观状态 | 几何缓存、VBO 上传、引用与释放 |
| 通过回调返回位置、光照、显隐；或主动 markDirty | 遍历对象、按需读取并比较状态，判断重建／局部光照更新 |
| 提供有效性判断（如 !isRemoved）、登记／主动解除登记 | 自动淘汰无效对象、section 分组、实例共享、版本切换及重建预算 |
| 声明渲染组支持的策略和默认策略 | 配置策略解析与缓存迁移 |
| 光照采样、对象有效性和业务距离限制 | 视锥剔除、资源重载和世界卸载处理 |

公开 Source 接口已删除。调用方不实现 prepareFrame、forEachShard、onInvalidate，也不持有 GPU 句柄。
库直接遍历自己管理的具体 WorldMeshGroup 对象，没有再用一个可由调用方实现的 Source 包装渲染组。

## 对象登记接口

```java
WorldMeshGroup<MyBlockEntity> group = WorldMeshRenderer.createGroup(
        "mymod:decorations", WorldMeshStrategy.SECTION);

group.track(blockEntity);          // 对象实现 MeshRenderable
group.track(blockEntity, adapter); // 或使用共享 MeshRenderableAdapter<MyBlockEntity>
group.markDirty(blockEntity);      // 对象状态主动变化，下一次遍历强制刷新
group.untrack(blockEntity);        // 可选：主动解除登记；isValid=false 也会自动移除
group.clear();
group.close();
```

两种 track 是替代关系，不需要同时调用。渲染组以对象引用身份登记，不使用其 equals/hashCode，也不要求额外提供 BlockPos key。
相同对象重复 track 无操作，不更换回调、不强制刷新，因此可以在 BER 中重复调用。
如需更换该对象的适配器，先 untrack 再 track。不同对象即使 equals 相等，也分别登记。
渲染组持有对象引用直到无效、untrack、clear、close 或世界卸载；对象保持有效时不会因 GC 自动丢失。

对象接口／适配器直接提供以下方法；适配器形式在参数中额外接收业务对象：

| 方法 | 作用 | 默认行为 |
|---|---|---|
| `isValid()` | false 时自动解除登记 | true |
| `needsUpdate()` | 是否重新读取轻量属性 | true |
| `isVisible()` | 整个对象是否可见；隐藏仍保留登记 | true |
| `origin()` | 当前世界原点 | 必须提供 |
| `packedLight()` | 当前实例光照 | 必须提供 |
| `geometryKey()` | 不可变的共享几何 key | 必须提供，不能为 null |
| `collectGeometry(collector)` | 向库提供的收集器写入局部几何 | 必须提供；资源暂不可用返回 false |

StaticObjectState 和 StaticGeometry 已删除；渲染组直接保存比较字段，缓存直接按 geometryKey 管理几何。
渲染组内部读取属性、保存比较快照，决定是否重建或更新光照；调用方无需创建状态包装、几何描述或 Pass 列表。

几何 key 必须包含影响捕获结果的全部状态：模型、材质／贴图、拓扑、局部姿势、骨骼显隐等。
世界原点、实例光照和整体显隐独立提供，通常不进入几何 key。
相同 key 表示完整几何及材质结果可共享；不同适配器在同一渲染组中也必须遵守这一约定。

没有可靠脏标记时保留 needsUpdate 默认值；有可靠通知时可以返回 false，并在变化后 markDirty。
有效性回调独立于 needsUpdate，状态不更新也不妨碍淘汰移除对象。
所有操作及回调在客户端渲染线程执行，track 要求当前存在世界。
markDirty/untrack 对未登记对象无操作；close 可重复调用，关闭后不能继续登记。

### 方块实体的适配器示意

```java
MeshRenderableAdapter<MyBlockEntity> adapter = new MeshRenderableAdapter<>() {
    public boolean isValid(MyBlockEntity entity) {
        return !entity.isRemoved() && entity.getLevel() != null;
    }
    public Vec3 origin(MyBlockEntity entity) {
        return Vec3.atLowerCornerOf(entity.getBlockPos());
    }
    public int packedLight(MyBlockEntity entity) {
        return LevelRenderer.getLightColor(entity.getLevel(), entity.getBlockPos());
    }
    public Object geometryKey(MyBlockEntity entity) {
        return GeometryCollector.blockModelKey(modelId, texture, Direction.NORTH);
    }
    public boolean collectGeometry(MyBlockEntity entity, GeometryCollector collector) {
        return collector.blockModel(modelId, texture, Direction.NORTH);
    }
};
group.track(blockEntity, adapter);
```

modelId/texture 为接入方已有资源标识；实际朝向和外观变化应在对应方法中从对象读取。
blockModelKey 是便捷 key 生成方法，与 blockModel 使用相同参数。也可以返回接入方自己的不可变 record key。
对象直接实现 MeshRenderable 时，方法相同，只省去 entity 参数。

ExampleBlockMeshGroup 使用一个共享适配器，读取实体 isRemoved、世界、距离、BlockState 和光照；没有 Observed 或额外对象表。
ExampleStressMeshGroup 的测试对象直接实现接口，needsUpdate=false，计时器只改字段并 markDirty。

## 几何收集

GeometryCollector 由库创建并传入 collectGeometry，只在这次回调内使用，不能自行构造或保存供以后写入。

- `collector.blockModel(modelId, texture, facing)`：按资源 ID 获取当前 SBM 树模型，绑定姿势、局部平移 `(0.5,0,0.5)`、应用朝向；自动收集 QUADS 与 TRIANGLES，资源暂缺返回 false。
- `collector.buffer(material, mode)`：获取 VertexConsumer，供自定义模型直接写顶点；同材质／拓扑的多次写入自动合并。

自定义捕获示意：

```java
public boolean collectGeometry(GeometryCollector collector) {
    VertexConsumer output = collector.buffer(material, VertexFormat.Mode.QUADS);
    // 将局部顶点写入 output，或将它交给已有模型渲染方法。
    // 普通顶点 UV2 写 0，自发光顶点写固定非零值。
    renderModel(output);
    return true;
}
```

NEW_ENTITY 顶点语义支持 QUADS／TRIANGLES；局部法线应包含对象静态朝向，不能包含相机旋转。
返回 true 且没有顶点是有效空几何；返回 false 会丢弃本次部分结果并限额重试。
资源重载后会重新调用收集方法，因此通过资源 ID 获取当前模型，不永久使用旧资源引用。

轻量属性读取不会调用 collectGeometry。只有缓存缺失、几何 key 改变或资源失效时才按需捕获，且同 key 对象共享结果。
捕获时库从当前仍有效、仍使用该 key 的对象中选择提供者，不永久绑定首次登记的实体。
材质 pass 在捕获后由库生成；section 在共享 CPU 几何准备好后才建立材质批次。

## 遍历与更新规则

每次世界绘制阶段遍历登记项：

1. 检查 isValid，无效项解除登记并交给库释放缓存引用。
2. 初次登记、markDirty 或资源失效时，强制读取 geometryKey、origin、packedLight、isVisible。
3. 其余对象每客户端 tick 至多检查一次 needsUpdate，返回 true 才读取属性。
4. 库比较内部快照，按需共享／捕获几何、更新位置、更新光照或切换显隐。

关闭静态绘制时，只要世界仍渲染，就继续有效性和属性检查，但不捕获或上传几何。
资源重载及 clearCaches 不受 needsUpdate=false 阻挡。markDirty 只请求读取最新属性；key 未变且只有光照变化时不会重新收集几何。
属性回调中再次 markDirty 的通知保留到下一次遍历。回调不应创建／关闭渲染组或修改全局渲染配置。

对象的位置、光照和显隐直接从自身属性返回。当前世界原点只支持平移，局部姿势、缩放和骨骼显隐由 collectGeometry 处理，并体现在 geometryKey 中。

## 策略选择

优先级为：**用户配置 → 调用方默认策略**。
AUTO 使用调用方默认策略。

默认 createGroup 重载允许 INSTANCE 和 SECTION。受限渲染组可以明确声明：

```java
WorldMeshGroup<MyObject> group = WorldMeshRenderer.createGroup(
        "mymod:instance_only", WorldMeshStrategy.INSTANCE,
        Set.of(WorldMeshStrategy.INSTANCE));
```

不支持的配置策略会被该渲染组拒绝，改用调用方默认策略；渲染组统计和命令反馈包含拒绝原因及实际选择。
默认策略必须在支持集合内。能力约束与偏好分开，不能因为用户选择 SECTION 就把任意渲染组放入世界合批。

用户配置文件为 `config/simplebedrockmodel-client.toml`：

```toml
[worldMesh]
strategy = "AUTO" # AUTO / INSTANCE / SECTION
```

库随包提供的客户端命令：

```text
/sbmrender strategy instance
/sbmrender strategy section
/sbmrender strategy auto
/sbmrender groups
```

命令直接修改客户端配置文件中的 `worldMesh.strategy`，与手动修改配置使用同一个设置；AUTO 使用调用方默认策略。
旧配置中的 `[staticWorld]` 不再读取；已有自定义策略需将该节名改为 `[worldMesh]`。
每个渲染阶段检查已加载的配置，修改实际策略时保留所有对象、光照、显隐及几何描述，释放旧缓存并重建。
当前迁移仍有预热窗口，未承诺策略切换时新旧后端无缝双缓冲。

示例的 `/sbmmesh path auto|instance|section` 修改同一配置项，同时启用世界网格绘制层。
生产环境不需要 example 就能通过 sbmrender 修改策略。

## 生命周期

| 事件 | 逻辑对象 | 渲染缓存 |
|---|---|---|
| 对象光照变化并通知／被回调检测 | 保留并更新 | INSTANCE 更新实例参数；SECTION 更新普通顶点的局部 UV2 范围 |
| 几何／材质／位置／显隐变化 | 保存最新描述 | 库自动选择换版或受影响批次重建；INSTANCE 原点变化仅更新位置 |
| 资源重载、手动 clearCaches | 保留 | 重新读取属性、获取资源、捕获几何、上传 |
| 策略切换 | 保留 | 迁移到新的几何组织策略 |
| setEnabled(false) | 保留，仍可更新状态 | 释放缓存，暂停静态绘制；重新开启后重建 |
| isValid=false／untrack／clear／close | 移除相应登记 | 释放库内引用；close 同时注销渲染组 |
| 世界卸载／切换 | 清空各渲染组对象 | 失效并释放 |

渲染组跨世界保持注册，清空后的同一渲染组可在新世界重新 track。
对象有效性判断由绑定回调提供，渲染组负责遍历和淘汰；无需调用方另建观察表。动态效果或压测计时器仍属于业务对象的行为。
示例压测的对象和计时器现在也不会因资源重载、手动清缓存或开关世界网格绘制层而被删除。

GPU 上传仍经由原版 ChunkRenderDispatcher。已排队的缓冲在完成前不会被复用，旧代上传完成后不会回流到新代池。
尚未增加主动取消原版排队上传的机制，旧调度器不再 drain 时的及时释放仍是已知边界。

## 库内部结构

- WorldMeshGroup：按对象身份保存对象／适配器引用和比较字段，统一执行属性读取、几何收集和策略准备。
- GeometryCache：按共享 key 保存捕获任务及结果，不调用业务对象；逐实例模式共享 GPU 网格，最后一个库内引用释放后淘汰。
- InstanceMeshBatches：共享网格、实例位置与光照、active/pending 换版和限额重试。
- SectionMeshBatches：section＋材质＋拓扑分组、重建队列、版本独立的成员光照范围。
- WorldMeshRenderer：上传队列、缓冲池、统一世界绘制和统计。
- WorldMeshMetrics：内部累计计数与帧采样；`WorldMeshRenderer.stats()` 从中构造不可变的 `WorldMeshStats` 快照。

ShardHandle、MeshLighting 和原始 submit 是内部实现；ShardMeta 已移除。共享计数只在几何缓存维护，不再由 GPU 句柄重复计数。
局部光照仍采用独立 4 B/顶点流，合并相邻／重叠脏区间，并在可见绘制前上传；调用方只需修改自身光照并 markDirty，或通过 needsUpdate 和属性方法被动报告变化。
自发光范围由模板识别后排除，不要求调用方记录顶点编号。

每个渲染组独立缓存，目前没有跨组几何去重。渲染组统一限额捕获共享几何，策略只处理已捕获结果；捕获和网格准备各有最多 4 项、约 2ms 的软预算。
单次捕获或单个大批次仍可能超过预算；光照上传暂未设置独立字节预算。
首帧、资源重载和策略切换可能短暂缺几何；SECTION 增删／显隐变化可能保留旧快照直到新版就绪。
固定阶段为 AFTER_BLOCK_ENTITIES；不处理透明排序、第三人称物品矩阵或阴影 pass。

## 诊断和迁移

全局 stats 返回 WorldMeshStats；groupStats 与 group.stats 返回 WorldMeshGroupStats，包含默认／实际策略、来源、拒绝原因、对象数和缓存数据。
命令只按需输出，不恢复逐帧日志或 VAO 微计时探针。

- `/sbmmesh enabled true|false`：控制世界网格绘制，保留对象。
- `/sbmmesh stats [detail]`：全局统计；detail 追加每个渲染组和压力计时器统计。
- `/sbmmesh cache clear`：仅失效缓存，保留对象。
- `/sbmmesh stress same|models|all|dynamic [count]`、`stress clear`：生成／清除压力渲染组。
- `/sbmmesh stress light start [intervalTicks] [batchSize]`、`stop`、`reset`：定时光照实验。

旧 Source 的 register／枚举／失效回调、手动 submit、retain/release 均不再是调用方接入点。
上一版按 id 推送 put/setLight/setVisible/remove 的接口也已移除，统一为 track/markDirty/untrack。
readState 及要求调用方创建 StaticObjectState／StaticGeometry 的用法已移除，改为直接提供属性与 collectGeometry。
原 example 的 StaticMeshCache 和 SectionMeshBatches 已移除；通用机制归入库，示例只保留业务采集与压测。
INSTANCE／MUTABLE 是内部提交语义，公共策略只表达 INSTANCE／SECTION；固定 UV2 布局由捕获结果自动选择。

frameMs 是最多 120 次世界绘制阶段间隔均值，cpuUs 是本层 CPU 时间，不是 GPU 计时。
局部上传和压力计时的具体判读见 [Section 与光照压测](D:/Minecraft/Dev/SimpleBedrockModel/docs/world-mesh-section-prototype.md)。
第三人称入口的后续设计见 [物品 VBO 设计](D:/Minecraft/Dev/SimpleBedrockModel/docs/third-person-item-vbo-design.md)。
验证采用编译检查与游戏内对照，不新增单元测试。

完整执行顺序、冷路径／稳态差异和资源归属见 [静态渲染调用链](D:/Minecraft/Dev/SimpleBedrockModel/docs/world-mesh-call-chain.md)。
