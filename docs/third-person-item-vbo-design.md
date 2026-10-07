# 第三人称与掉落物静态网格接入

更新日期：2026-10-07。以现有即时 VBO 的画面表现为基线，迁移到所属阶段的批量提交。SBM 现行接口见 [静态网格 API](static-mesh-api.md)，TaCZ 阶段方案见 [静态物品提交设计](D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/docs/static-item-mesh-submission-design.md)。

## 状态与职责

SBM 已实现共享缓存、临时队列、共用绘制后端和整数属性。TaCZ 的 Java 调用方及实体／shadow hook 待迁移。库实现类可扩展，可公开的方法保持 public。

第三人称右手、GROUND 和世界内 FIXED 使用静态外观。物品入口负责外观与实际 LOD，阶段所有者管理队列边界，SBM 负责捕获、上传、共享及绘制。展示枪继续由 WorldMeshGroup 管理长期对象。

## 调用流程

1. 当前渲染阶段打开共享队列。
2. 物品入口检查支持范围，解析 ExternalGunAppearance 的不可变快照。
3. 根据资源修订、外观、实际 LOD、视角及固定变换查询缓存。缺失几何在当前调用内按预算捕获，再异步上传。
4. 选择完整就绪的目标或兼容 previous，保存 modelView、方向光逆线性变换、light 和 overlay 后提交。
5. GROUND 调用返回；第三人称右手按选中的 Snapshot 立即生成枪口坐标和激光顶点。
6. 所属阶段结束前按实际材质和网格集中绘制枪体。

## 几何与实例参数

`DisplayGunGeometry.capture` 在捕获时应用视角定位节点和 display scale。ItemRenderer 入口包含 `(0.5,0.5,0.5)` 捕获补偿；展示槽位使用自己的 FIXED 变换。

外观键包括模型、材质、实际 LOD、配件／变体／adapter、弹膛与弹匣状态。相同外观和视角共享 VBO。世界位置、运动、旋转、姿势、light 和 overlay 保存为实例参数。

普通与固定发光几何使用各自材质。普通 UV2 和所有 UV1 读取整数参数表，divisor=1；固定发光 UV2 使用几何来源，divisor=0。实际上传格式和属性来源由 Part 校验。

## 结果与生命周期

| 结果 | 调用方行为 |
| --- | --- |
| 已提交／已绘制 | 使用实际选中 Snapshot.useLod()，对齐动态效果 |
| PENDING | 本次调用由 VBO 接管，等待准备完成 |
| UNSUPPORTED | 交给既有模型／AR 路径 |

previous 使用兼容的已准备网格。矩阵在提交时复制，调用方随后可弹出 PoseStack。命令持有网格引用直到排空或取消。

正常结束用 endBatch，提前排空用 flush，异常退出用 close。失效命令按所属代次退役；绘制失败时完成清理，本次保持 VBO 接管，后续调用重新准备。

TaCZ 使用 owner `tacz:external_item_guns`。关闭功能调用 invalidateOwner；资源重载、世界切换及格式／上下文变化由库生命周期处理。新 API 随新版本坐标发布，再同步 TaCZ 依赖。

## 阶段与光影

普通实体阶段覆盖掉落物、第三人称右手和 ItemFrame，在实体循环结束、原版集中提交前排空。BER 使用独立 scope。Oculus 主场景和 shadow 分别结束，shadow 在集中提交及深度复制前绘制。

现有捕获、材质和光照行为继续作为基线。同阶段固定的共享状态由 scope 维护，实际逐对象差异才加入提交保存或分组。

Oculus 启用扩展格式时会通过 BufferBuilder Mixin 自动写入当前分类字段，这一机制也作用于旧即时路径。先核对真实格式和捕获值，再按影响输出的实际差异处理缓存键或绘制状态。

尚未建立队列接入的已验证调用使用同缓存即时 VBO。首版 outline 行为为正常枪体绘制，实体其他几何沿用原版处理。

## 动态效果与验收

枪口和激光在当前实体、姿势栈和实际 LOD 有效时生成。激光写入现有原版 consumer，枪体在所属阶段集中绘制。通过游戏内对照确认最终遮挡和提交次序。

验收覆盖真实 ItemEntity／ItemFrame、堆叠副本、父矩阵、浮动／旋转、LOD／previous、配件激光、枪口、明暗／overlay、资源重载、开关、世界切换，以及默认／Fabulous／两个 Oculus 版本的主场景和 shadow。

结构验证采用编译、打包与引用检查，整数属性采用隐藏 GL 像素检查。性能记录见 [合批测量](immediate-mesh-batch-benchmark.md) 和 [整数属性测量](immediate-attribute-game-benchmark.md)，CPU 统计包含 submit 与 flush。
