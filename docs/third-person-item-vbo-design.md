# 第三人称与掉落物静态网格接入

更新日期：2026-10-07。本文记录 TaCZ 原型的几何和调用方要求；SBM 现行 API 以 [静态网格 API](static-mesh-api.md) 为准。VBO 优化未正式发布，没有线上兼容约束，旧 `client.world` 和即时原型名称已直接移除。

## 实际范围

第三人称右手、GROUND 与世界内 FIXED 使用静态外观，运行时运动、掉落旋转和父层变换只进入实例矩阵。第一人称、GUI、第三人称左手和屏幕预览保持其现有入口。

TaCZ 当前 Java 原型仍依赖旧本机 SBM 坐标及旧 API。本轮仅整理 SBM 库、示例和文档；TaCZ 实体阶段、Oculus 主场景/shadow 队列接入见 [静态物品提交设计](D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/docs/static-item-mesh-submission-design.md)，尚未实施。不要把工作区版本字段当作正式发布记录。

## 外观与捕获

`ExternalGunAppearance` 解析不可变外观键与 Snapshot：资源修订、display、实际 LOD、配件/变体/adapter、弹膛及弹匣状态。far 可能引用 near，实际绘制/效果选择以 Snapshot.useLod() 为准。

`DisplayGunGeometry.capture` 完成完整外观捕获，普通与固定发光图元使用独立材质。第三人称和 GROUND 在捕获时应用固定定位节点及 display scale；ItemRenderer 入口的捕获包含 `(0.5,0.5,0.5)` 补偿，运行时不再补第二次。展示枪绕开 ItemRenderer，保留自己的 FIXED 变换。

geometryKey 必须包含视角和实际捕获上下文。世界位置、运动、pose、light 和 overlay 不进入共享键。静态的非均匀 display 缩放在捕获时按法线矩阵处理；额外运行时非均匀缩放仍需游戏内验证。

provider 在 submit / tryDraw 内按预算同步调用，队列不保留 ItemStack、provider 或可变模型实例。相同外观/视角共享 VBO，不进行逐帧顶点烘焙，也不建立每实体 VBO。

## 库入口与交接

`v2.client.mesh.StaticMeshRenderer` 的即时绘制与 `StaticMeshBufferSource` 使用同一 owner 缓存和共用 `MeshBatchRenderer`。WorldMeshGroup 继续管理展示枪的长期对象、剔除和 SECTION 生命周期；不能把临时物品登记成世界组对象。

```java
MeshDrawResult result = StaticMeshRenderer.tryDraw(
        owner, geometryKey, previousKey, provider, pose, light, overlay, retention);

try (StaticMeshBufferSource batch = StaticMeshRenderer.openBatch(owner, order, pass::isCurrent)) {
    MeshSubmitResult submitted = batch.submit(
            geometryKey, previousKey, provider, pose, light, overlay, retention);
    batch.endBatch();
}
```

QUEUED 只表示已选择网格并入队。PENDING 接管此次物品调用，不遍历旧枪体；只有明确不支持时返回 UNSUPPORTED。previous 仅查询兼容且已经就绪的网格，返回结果让调用方按实际选中的 Snapshot 对齐枪口和激光。

TaCZ 使用固定 owner `tacz:external_item_guns`；关闭开关仅调用 invalidateOwner，不清理其他接入方。资源重载、世界切换和 GL context/格式失效由库处理。正式接入新包时须使用包含新 API 的新版本坐标。

## 绘制边界与属性

pass 所有者负责打开 scope、判断其身份并在统一边界 endBatch。flush 只提前排空本 scope，之后仍可提交；close 只取消。没有 beforeDraw / beforeFlush 空回调，没有全局帧末队列，也不从任意 MultiBufferSource 反射寻找 delegate。

同一实例的完整矩阵在提交时复制；调用方可以立即 pop PoseStack。共享 shader 参数和目标在排空前须保持稳定。Oculus 的材质 wrapping、方向光/颜色快照与语义 IDs 接入仍需在真实 pass 阶段完成；烘入顶点的数据必须进 geometryKey，而非只在 flush 时恢复 IDs。

普通 UV2 和所有 UV1 从 512 KiB GL_INT 参数表读取，divisor=1；固定发光 UV2 保留实际上传格式中的几何来源和 divisor=0。Part 缓存来源、代次及 offset。能力缺失或任一完整 16 位分量超出 0–255 时，在外观解析前明确回退。

世界组保留 UNIFORM/FIXED/MUTABLE 属性策略。两类入口共用材质/shader/VAO draw 和清理，不统一其实际光照存储需求。

未知 pass 的已验证即时 VBO 仍有用途。首版忽略枪体 outline，枪体正常进入所属 pass；不因 outline source 或光影开启而自动回退或永久 PENDING。

## 动态效果与验收

枪口坐标和激光顶点在调用期、当前实体与实际 LOD 有效时生成。激光继续写原版 consumer，不在每次效果生成前拆开枪体队列；枪体按所属 pass 的集中边界提交。实际遮挡和透明次序以游戏内画面对照为准。

异常可能已经提交部分 Part；清理后取消余下命令，同次调用不能运行 previous 或旧枪体 fallback。零 retention 也不能提前释放待绘制条目；失效命令释放后不能重入新缓存。

本轮不新增单元测试。结构验证使用编译、打包与源码引用检查；整数属性用隐藏 GL 像素检查。真正验收仍需真实 ItemEntity/ItemFrame、堆叠副本、父矩阵、实际 LOD/previous、配件激光、枪口、明暗/overlay、F3+T、开关、世界切换、默认/Fabulous 及两个 Oculus 版本的主场景/shadow。

游戏内对照命令及历史结果见 [网格提交测量](immediate-mesh-batch-benchmark.md) 和 [整数属性测量](immediate-attribute-game-benchmark.md)。CPU 应统计 submit + flush；draw 数没有降低，主要收益来自材质 setup 和 VAO bind 合并。
