# TaCZ 即时物品整数属性接入评估

2026-10-07 更新：本文保留历史属性测量。现行库结构与签名见 [静态网格 API](static-mesh-api.md)，TaCZ 调用方待迁移。后续批量接入以现有即时 VBO 画面为基线，按实际状态差异处理阶段共享参数、材质和扩展分类字段。

日期：2026-10-06。状态：第一阶段代码已接入，工作区即时物品默认使用缓冲整数属性；游戏内性能、目标移动设备及真实 TaCZ pass 仍待验收。

## 第一阶段实现范围

- `MeshIntegerAttributes` 独占 512 KiB GL_INT 参数表，按当前 GLCapabilities 检查 IPointer/core/ARB divisor 的版本、扩展和函数指针。仅首次初始化或上下文变化时查询能力上限。
- `StaticMeshCache.Part` 持有实际上传格式、几何来源及参数来源代次、UV1/UV2 offset 和挂载状态。新 Part、格式变化、参数缓冲重建及开发属性覆盖都会重新挂载；固定 UV2 使用上传格式中的实际类型、stride、offset，并恢复 divisor=0。
- `supportsInstanceAttributes(packedLight, packedOverlay)` 在渲染线程检查能力与完整 16 位坐标范围，不捕获网格、不上传参数表。TaCZ 在外观解析前调用它，让明确不支持的调用直接走旧路径；其他已识别外观仍沿用 PENDING 策略。
- 参数表分配/上传失败在任何 draw 之前进入退避重试；绘制过程失败使用独立 DRAW_FAILED 内部状态，清理和失效后仍禁止同次 previous LOD 或旧模型绘制。
- TaCZ compile/jarJar 已同步为 `2.5.23-forge-mc1.20.1`，最低范围 `[2.5.23,)`。本机 Maven 发布用于联调；远程正式发布是后续交付步骤。
- 基准新增 `PRODUCTION` 模式，直接测默认后端及 pointer 更新时的绑定保护。该模式不向生产路径插入计数器，pointer/bind 列记为 -1；part 数从实际成功绘制的捕获记录核对。
- 第一阶段保持 WorldMesh 的 UNIFORM I2I 路径、shader、外观捕获、物品上下文、LOD/激光顺序和传入缓冲提交策略。

已验证：SBM、TaCZ 编译和 reobf/JarJar 打包通过，SBM 3 项 JUnit 测试通过；TaCZ 内嵌依赖元数据及 jar 核对为 2.5.23 / `[2.5.23,)`，SBM 包含正式 helper 并排除 example 类。隐藏 OpenGL 3.3 上使用正式 helper 与 Minecraft VertexBuffer 验证了普通 draw + divisor=1、非 16 倍数坐标、多个 VAO 交错、绑定被 hook 改写、缓存命中、新 Part/同格式重新上传、固定满亮来源恢复、开发覆盖失效及参数表重建，无 GL 错误。桌面 GPU 为 RTX 4060 Laptop / NVIDIA 591.74；这不是 TaCZ 游戏内或移动端验收。

以下测量是迁移前开发对照结果，不是 2.5.23 生产后端的新测量。

## 决策

参数后端保持 **整数属性缓冲 + divisor=1 + 每 Part/VAO 缓存 offset**。当前库的即时和队列入口共用这一后端；TaCZ 后续按实际 pass 集中提交枪体，未知 pass 保留即时 VBO。集中提交仍在所属实体或 shadow pass 内完成，不登记为世界组对象，也不改为硬件实例化 draw。

本次取舍优先考虑移动端兼容与统一属性来源，接受当前场景约 5% 以内的绘制提交 CPU 增量作为工程预算。该预算针对受控测试的模型绘制 pass；移动设备上的 CPU、GPU 和整帧变化仍需分别测量，不能承诺全部设备下降不超过 5%。

I4I 是 OpenGL ES 3.0 正式提供的接口，当前规范仍支持它；I2I 没有同名 GLES 3.0 原生入口，桌面 GL 转译器需要处理该调用。是否常用不宜单独作为可靠性判断。采用缓冲方案的理由是避开已观察到问题的常量整数属性路径，并让 UV1/UV2 使用明确的整数数组来源。[Khronos GLES 整数属性接口](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/es3.0/glVertexAttrib.xml)

GLES 3.0 同时提供 IPointer 和 divisor，桌面 GL 可使用 OpenGL 3.3 或 ARB_instanced_arrays。功能存在不等于转译实现必然正确：普通 draw 配合 divisor=1 读取 instance 0、固定与实例属性混合、VAO 切换，都是目标设备验收项。[GLES IPointer](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/es3.0/glVertexAttribPointer.xml)、[GLES divisor](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/es3.0/glVertexAttribDivisor.xml)、[桌面实例数组规范](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_instanced_arrays.txt)

I2I/I4I 保留为开发 A/B 对照即可，不建议在每帧或每物品中反复切换生产后端。能力不足时应有明确的旧顶点渲染回退，避免静默显示黑模型或一直 PENDING。

## 已有测量

环境：RTX 4060 Laptop、NVIDIA 591.74、OpenGL 4.6、Java 17.0.19；每项 10 轮、1200 采样帧/轮，共 12000 样本。下面的时间是 **整批 128 次真实 tryDraw 调用** 的 CPU 中位数，不是单个 API 调用时间。

| 场景 | I2I | I4I | 每 part 绑定 | 每物品绑定 | 每物品绑定 + 缓存 |
|---|---:|---:|---:|---:|---:|
| 单 part：128 items / 128 parts | 493.3 us | 499.1 us | 511.9 us | 510.8 us | 511.5 us |
| 普通 + 荧光：128 items / 234 parts | 788.2 us | 791.0 us | 820.4 us | 809.8 us | 802.5 us |

推荐实现相对 I2I，单 part 增量约 3.69%，荧光场景约 1.81%。荧光场景每物品约增加 112 ns；GPU 查询区间增加约 2.7%，它可包含提交相关空闲，不代表纯 shader 执行开销。

荧光场景经 capture.csv 核对为 128 个实例光照 part、106 个固定满亮 part。提前绑定将 bind 从 468 次降到 256 次，十轮每轮都更快；缓存将 IPointer 从 362 次降到 112 次，在八轮中更快。最优缓冲方案比逐 part 绑定节省约 17.9 us，缩小了超过一半的额外 CPU 成本。

原始结论：[单 part 长测分析](/D:/Minecraft/Dev/SimpleBedrockModel/run/benchmark-results/immediate-attributes-20261006-161540/analysis.md)、[荧光长测分析](/D:/Minecraft/Dev/SimpleBedrockModel/run/benchmark-results/immediate-attributes-glow-20261006-170548/analysis.md)。

虚拟网格在 AFTER_BLOCK_ENTITIES 调用真实即时绘制实现；尚未测量 TaCZ 的外观解析、实际 BEWLR/实体层、配件组合或阴影 pass。两场景的相机方向与捕获规则不同，不直接用绝对耗时之差衡量荧光成本。生产保护检查的额外成本也需要重新测量。

## SBM 侧实现

### 1. 常驻整数对参数表

参数表存储 `(u, v)`，每个分量 0–255；记录为两个 GL_INT、8 字节 stride，65536 条共 512 KiB。光照和 overlay 共用同一 VBO，分别选择 offset：

```java
int u = packed & 0xFFFF;
int v = packed >>> 16;
// 先检查两个完整分量，不截断到 8 位。
long offset = ((long) v * 256 + u) * 8;
```

标准 Minecraft 光照/overlay 坐标均在此范围内，非 16 整数倍的光照坐标也能精确保留。使用与测试一致的 GL_INT，保持现有 I2I 的低/高 16 位正整数语义；不为了缩小参数表改用可能发生符号扩展的 SHORT。

初始化只上传一次，常规绘制没有 BufferSubData、映射、逐 draw 覆写或同步等待。超出表范围的参数必须明确处理：第一版在任何 draw 前返回“不支持该参数”的状态，让调用方使用旧顶点路径，禁止 clamp、取低 8 位或返回黑暗值。若需要支持扩展坐标，另做保持整数精度的溢出记录机制，并测量其成本。

第一步参数表由即时渲染器独占，生命周期与它的网格/GL 上下文协调。后续世界渲染复用逻辑时，明确各自所有权；不能由一个 renderer 清理另一个仍引用的共用缓冲。

### 2. 每次物品调用的作用域

```text
确认所有 part 上传就绪、布局与参数可用
由所属 pass 在明确边界协调原版提交；库没有 beforeDraw 回调
保存原 ARRAY_BUFFER 绑定
绑定参数 VBO
遍历该物品的 part：
    setup material、上传 shader / 矩阵 / 方向光
    绑定几何 VAO
    按真实上传格式初始化/核对 UV1、UV2 来源
    只更新变化的 pointer
    draw → clear shader、VAO、material
finally 恢复原 ARRAY_BUFFER 绑定
```

每物品一次保存绑定查询、一次绑定、一次恢复均计入性能预算。作用域包含一个物品的所有材质 part；即时调用不跨越其他物品；显式队列可以覆盖当前 pass 的多个实例。普通 draw 沿用当前 VertexBuffer.draw；不会自动减少 draw 数。

shader/material hook 可能改变 ARRAY_BUFFER。正式实现应在需要更新 pointer 时核对当前绑定，必要时重绑参数 VBO；没有 pointer 更新时，VAO 已记录的数据来源可直接使用。这个保护的成本需计入新基准。它不覆盖 hook 重写 VAO 或 shader 语义的问题，相关组合仍需画面验收。

### 3. 两种 UV2 来源

| 属性/part | 来源 | divisor |
|---|---|---:|
| 所有 part 的 UV1 | 参数表中的本次 overlay 记录 | 1 |
| 普通 part 的 UV2 | 参数表中的本次 packedLight 记录 | 1 |
| 自发光 part 的 UV2 | 几何 VBO 中捕获时的固定满亮 | 0 |

保持 TaCZ 已有的 `_illuminated` 标记、普通/自发光分流和 DisplayGunRenderType.emissive。该路径已存在于 DisplayGunModelInstance / DisplayGunGeometry，TaCZ 不需要重新划分骨骼。

### 4. 将缓存放进 Part

从测试用的全局 IdentityHashMap 移到生产 Part 的所有权记录中，缓存实际上传格式、attribute 索引、几何 stride/UV2 offset、参数缓冲 ID 与代次、最后 UV1/UV2 offset，以及是否完成来源挂载。

只缓存 offset 不够：buffer ID、格式和来源代次变化时，相同数字 offset 也必须重新挂载。Part 新建、VBO 池复用、重新上传、格式漂移、参数缓冲重建和资源失效都使配置失效。第一次绘制前依据 `buffer.getFormat()` 重挂；固定 UV2 显式恢复几何指针和 divisor=0。

格式探针只覆盖部分格式漂移，不能当作完整 shader 兼容性证明。不能用一个全局 lastLight 缓存代替每 VAO 的状态。

### 5. 能力、异常与释放

- 按当前 GL 上下文检测 IPointer 与 divisor 的可调用入口；桌面区分 core 与 ARB 入口。版本/扩展和函数指针都检查，诊断记录具体缺失项，不按 GPU 品牌猜测。
- 后端选择在上下文初始化/能力失效时确定，逐物品只做便宜的状态与范围检查。
- 失败必须在任何 part draw 前确定，才允许本次旧路径回退。已经提交部分 part 后不能再整件旧绘制，以免双绘制。
- 参数 VBO 重建更新代次，让所有存活 Part 重新挂载。清理时先结束作用域，处理已排队上传，再释放几何 VAO/池及参数缓冲；全部 GPU 操作在渲染线程。
- 保持资源重载、世界切换、优化开关、上传失败和重试退避的现有生命周期。表是纯整数，但其 GPU ID 与 GL 上下文绑定。
- 开发模式的统一回调可继续作为对照入口；生产实现直接走专用 helper，避免发布版依赖 example 类或测试控制器。

## TaCZ 侧改动

### TaCZ 原型调用方待迁移

[ExternalGunItemVboRenderer](/D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/src/main/java/com/tacz/guns/client/renderer/item/ExternalGunItemVboRenderer.java) 继续传 geometryKey、previousKey、pose、packedLight、packedOverlay。GL 操作集中在 SBM；TaCZ 不创建或逐物品更新另一套光照缓冲，也不新增 shader uniform。

[GunItemRendererWrapper](/D:/Minecraft/Dev/MCModderAnchor/TimelessAndClassicsZeroF/src/main/java/com/tacz/guns/client/renderer/item/GunItemRendererWrapper.java) 保持本次调用内完成绘制：THIRD_PERSON_RIGHT_HAND、GROUND 与受支持的 FIXED；第一人称、GUI/预览和屏幕内 FIXED 保持当前路径。DRAWN_PREVIOUS 仍对应实际绘制 LOD，后续激光和枪口位置继续与之对齐。

### 必须修正能力回退的接入点

当前 ExternalGunItemVboRenderer 把 tryDraw 的 UNSUPPORTED 转成 PENDING，以便已识别外观继续保留在 VBO 路径。直接新增“缺少 divisor → UNSUPPORTED”会被这层吞掉，设备可能一直不显示模型。

已在外观解析/tryDraw 之前调用 SBM 的轻量 `supportsInstanceAttributes(int packedLight, int packedOverlay)` 接口；确定不支持时直接从 TaCZ 入口返回 UNSUPPORTED，走已有旧顶点渲染。

| 状态 | TaCZ 行为 |
|---|---|
| 缓冲后端可用，网格就绪 | 本次即时绘制 |
| 捕获/上传进行中、可重试资源故障 | PENDING，推进准备；沿用当前接管语义 |
| 可用的兼容旧 LOD 网格 | DRAWN_PREVIOUS，效果与实际 LOD 对齐 |
| GL 能力确实缺失、参数范围不支持 | 在入口直接 UNSUPPORTED，允许旧顶点路径 |
| 已提交部分 part 后发生异常 | 本次不再旧绘制，记录失败并失效/重试 |

不要简单移除全部 UNSUPPORTED → PENDING 的转换，避免改变原有已识别外观的故障/接管策略。也不能只因为 Oculus/AR 或光影启用就禁用后端。

### 发布与已有绘制边界

TaCZ compile/jarJar 依赖已提升为 `2.5.23-forge-mc1.20.1`，允许范围 `[2.5.23,)`。发布 TaCZ 前需要提供包含该 API 的 SBM 新修订版，避免运行时拿到旧库出现 NoSuchMethodError。不要以同一版本号覆盖已发布的不同实现。

保持 EnableStaticItemGunVbo 的现有默认开启与旧路径开关。资源版本仍由 ClientIndexManager.resourceRevision 进入外观键；F3+T 的 SBM 生命周期监听已清理即时和世界缓存。自定义枪包 reload、旧 LOD 交接也需实测。

迁移前 beforeDraw 回调为空、buffers 参数未用于 flush；新 SBM API 已删除该空回调。历史文档描述过提交先前缓冲的契约，这一项应在真实 BEWLR/包装 MultiBufferSource 中单独核对；不要为了接入参数缓冲改用全局 bufferSource 或延迟整批物品 draw。

## 尚有一条 WorldMesh 路径

TaCZ 展示枪使用 WorldMeshRenderer；其 UNIFORM UV2 仍调用 I2I。只修改即时物品路径不能宣称所有 TaCZ 模型的移动端光照问题已解决。

建议第二阶段把世界 UNIFORM 光照也改为整数属性缓冲；其按 handle/light 分组有不同的绑定摊销边界，要独立测量。FIXED 与 MUTABLE 光照继续读各自逐顶点流、divisor=0，池复用必须恢复该值。第一阶段不要顺便改变 SECTION 策略或世界模型绘制阶段。

## 验收与接入顺序

1. SBM 实现生产 Part 状态、参数表和能力查询，默认选择缓冲后端；保留开发 I2I/I4I/指针对照指令。
2. TaCZ 提升依赖并接能力预检查，保留接口、LOD/效果顺序与准备期语义；编译两个项目。
3. 重跑单 part、真实荧光、overlay 变化与带保护检查的性能对照；CPU pass 增量以约 5% 为预算，同时观察 GPU 区间与帧时间。
4. 桌面实际手持/掉落/FIXED，配件多材质、hurt overlay、近远 LOD、满空弹、F3+T、枪包 reload、开关和世界切换验证。
5. 用实际故障移动设备及其转译器验证：普通与荧光亮度、普通 draw + divisor=1、多个 VAO 交错、资源重载/池复用，确认没有黑模型或属性串用。记录 GPU/驱动/转译器和当前 GL 能力。
6. 对项目实际使用的 Oculus/AR 与光影主场景/阴影 pass 做画面对照，再决定明确不支持的组合；随后迁移 WorldMesh UNIFORM。

第一阶段已完成缓冲属性默认方案及 TaCZ 能力预检查接入。移动端可靠性、真实 TaCZ pass 和含生产保护检查的性能仍属于正式验收，现有桌面数据不替代这些检查。
