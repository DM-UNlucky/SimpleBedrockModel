# SBM 第三人称与掉落物 VBO 渲染设计

整理日期：2026-09-21。本文记录针对 `TimelessAndClassicsZeroF` 的设计结论，尚未实现新的物品渲染后端。

## 目标与适用场景

为第三人称手持和掉落物建立独立的 SBM 模型表示，将几乎静态的局部几何缓存到 GPU。
每次绘制更新实例矩阵、光照和少量骨骼显隐，减少逐帧顶点变换、写入及上传。

物品整体跟随实体运动、旋转，或掉落物旋转，不会使局部几何失效。
少量骨骼显隐可以通过跳过对应绘制组处理；独立刚性运动可以通过组矩阵处理。
因此这类模型适合共享 VBO、逐实例提交的路径。

第一人称继续使用现有旧模型和渲染流程。外部视角副本独立维护状态，避免依赖第一人称模板缓冲和嵌套回调。
副本是从同一份模型资源构造的另一套运行时表示，无需维护两份作者模型，也不为每个 ItemStack 复制完整几何。

## TACZ 接入边界

目标项目：`D:\Minecraft\Dev\MCModderAnchor\TimelessAndClassicsZeroF`。

首批接入 `GROUND` 和实际使用的第三人称手持入口。
当前 `GunItemRendererWrapper.renderByItem()` 跳过第一人称和第三人称左手，需要沿用项目实际的视角分发规则。
GUI、预览、透明效果和特殊兼容场景可先保留原路径。

接入时保留模型／LOD 和贴图选择、display 缩放、定位节点变换等语义。
旧模型的坐标翻转与 SBM 烘焙坐标约定需要明确适配，不能直接叠加旧矩阵造成重复翻转。
display 可能包含非均匀缩放，因此法线处理也必须覆盖该情形。

在原物品渲染调用位置使用完整实例矩阵、光照和 overlay 提交绘制，并处理与前后缓冲批次的顺序。
当前静态方块层的 `AFTER_BLOCK_ENTITIES` 路径以世界固定朝向、实例平移为前提，
不能直接承担手持物品的矩阵、法线、阴影 pass 和渲染目标语义。

参考入口：[GunItemRendererWrapper.java:317](D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/src/main/java/com/tacz/guns/client/renderer/item/GunItemRendererWrapper.java:317)。

## 几何组织与外观状态

目标绘制粒度是“固定枪身 + 少量可变组 + 配件”：

- 将固定枪身和固定后代变换烘焙合并，按材质及必要的光照语义划分绘制组。
- 为弹匣、机械瞄具等需要切换的部分保留独立显隐组；有真实相对运动的部分保留独立变换。
- 配件使用共享网格和各自挂点矩阵，处理默认配件、替换件及 adapter 规则。
- 保留枪口、激光和配件挂点等查询变换；粒子、动态文字和其他副作用由外层按需执行。
- 显式适配 `_illuminated` 等旧模型约定，不能假定 SBM 默认状态与 TACZ 一致。

从枪械数据生成弹药有无、扩容等级、瞄具安装等外观状态，避免通过旧模型完整 render 来更新显隐。
需要核对弹膛／弹匣弹药、标准与扩容弹匣、瞄具安装后的机械瞄具和提把、护木及默认配件等条件。
共享几何与实例状态分开，防止不同枪械实例之间串用显隐结果。

SBM 烘焙时必须保留需要运行时显隐或变换的节点边界，不能先把这些节点无条件折叠进固定枪身。
完整显隐组合缓存可以作为后续方案，但需要评估组合数量和显存上限。

状态语义参考：[BedrockGunModel.java:73](D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/src/main/java/com/tacz/guns/client/model/BedrockGunModel.java:73)。

## 绘制方式与收益边界

每个绘制组的局部几何上传一次，绘制时绑定 VBO 并提交当前矩阵。
`drawWithShader()` 可以作为直接绘制入口，但法线、动态属性与渲染状态必须满足后文约束。
概念上的调用顺序为：

```text
读取本次实例的外观状态与变换
确认当前 pass 和渲染目标适用
处理前序缓冲与本次直接绘制的顺序
按材质设置渲染状态
    对每个可见绘制组：绑定共享 VBO，提交组矩阵与动态参数，绘制
清理本次状态，继续原渲染流程
```

一次物品渲染可能包含多个 draw；一个材质状态包围多次 draw 不等于合批。
只有几何已合并且材质、变换和属性要求允许时，整组才能一次绘制。
逐实例共享 VBO 也不等于硬件实例化，不会自动把不同物品合成一次 draw。

主要收益来自减少每帧几何处理和上传。相比每骨骼分别绘制，合并固定枪身还可以减少提交次数。
实际收益取决于模型复杂度、可变组数量、实例数量及现有后端；TACZ 的 AR 路径已有几何缓存，
不能预设新路径在启用 AR 时也更快。

## 法线、光照与动态参数

### 法线和方向光

局部位置通过矩阵变换正确，不代表局部法线自动进入正确的光照空间。
本地 Minecraft 1.20.1 的 `assets/minecraft/shaders/core/rendertype_entity_cutout_no_cull.vsh` 中：

```glsl
gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
```

方向光直接使用输入 `Normal`，不会先通过 `ModelViewMat` 变换它。
因此后端必须明确处理法线与光源的坐标空间，并支持旋转和非均匀缩放。
可使用能接收正确法线矩阵的 shader／后端；特定条件下转换光源方向也需明确适用范围。

`drawWithShader()` 会设置 shader 的光源方向，不能假设调用前写入的自定义光源 uniform 会原样保留。
光影兼容需要单独验证，包括正常实体绘制和阴影 pass。

### 光照、overlay 和颜色

几何缓存与实例光照、overlay、颜色及透明度分离，避免因光照变化复制或重建完整几何。
整组统一光照可以借鉴当前 SBM 的常量 UV2 attribute；普通与自发光混合时需要分组或独立属性机制。
具体实现必须兼顾 shader 属性布局、状态恢复和方向光语义。

满亮 UV2 只解决光照贴图采样，不能自动替代 TACZ `UnshadedVertexConsumer` 的方向光处理。
overlay、颜色和透明度应由后端正确传递；首版不支持的效果应明确回退。

## 缓存与资源生命周期

按模型资源及烘焙配置共享几何，实例只保留外观状态和变换等必要数据。
模型／LOD 替换、资源重载、烘焙配置变化和格式不兼容应使对应缓存失效。
缓存就绪状态必须与实际 VBO 生命周期一致，GPU 资源在正确线程显式释放。

若采用异步或限额上传，未就绪时保留普通绘制回退，避免模型缺帧。
应以实际绘制上下文判断回退，而不是仅凭菜单是否打开来影响全部世界物品。
Oculus、AR 等环境需要明确的兼容分支，不能仅靠切换光影时清空缓存来保证正确性。

## 建议实施顺序与验证

1. 建立第三人称／掉落物专用 SBM 表示，先验证坐标、外观状态、配件和挂点。
2. 接入共享 VBO 后端，完成法线、光照、overlay、绘制顺序和缓存生命周期。
3. 根据实际 draw 数与耗时合并固定组，再评估显隐组合缓存等进一步优化。

后续实现以编译检查和游戏实测为主，不新增单元测试。
游戏内对比第三人称手持、掉落旋转、非均匀缩放、明暗环境变化、自发光、配件和显隐切换，
并检查 GUI／预览回退、资源重载、光影开启／关闭、阴影 pass 及 AR 环境。

性能对照同时观察 draw 数、顶点上传量、CPU 提交时间、帧时间波动和显存。
平均 FPS 或 GPU 占用率单独变化不足以证明某个环节的收益；当前世界网格绘制层 `cpuUs` 是 CPU 计时，不是 GPU 计时。
静态方块区块合批的已有实验另见 [Section 合批实验](D:/Minecraft/Dev/SimpleBedrockModel/docs/world-mesh-section-prototype.md)。

当前世界网格渲染组 API 已移除 Source，通过对象接口／适配器提供几何、有效性与状态，使用 track/markDirty/untrack 接入；物品路径仍需独立的完整矩阵绘制入口。分工与策略覆盖见 [世界网格渲染组接口](D:/Minecraft/Dev/SimpleBedrockModel/docs/world-mesh-rendering.md)。
