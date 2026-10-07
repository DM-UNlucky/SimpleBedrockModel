# 即时静态网格队列原型与对照测量

2026-10-07：原型迁移到 `v2.client.mesh`，无旧 API 适配；完整接口见 [静态网格 API](static-mesh-api.md)。

`StaticMeshRenderer.openBatch(owner, order)` 提供显式、限于当前渲染 pass 的命令缓冲。每次 `submit()` 复制当前 `RenderSystem modelView × PoseStack` 和实例方向光所需的逆线性变换，保存 light/overlay，并持有选中的缓存条目，直到 `flush()`、`endBatch()` 或 `close()`。

默认 `SUBMISSION` 保持提交及 Part 顺序，只合并相邻的相同材质。显式 `MATERIAL` 按 RenderType、网格分组，一组材质只 setup/apply/clear 一次，同一网格只 bind 一次，每个实例仍单独 draw。它没有加入 instancing，也没有降低实例的 draw 数。

## 测试指令

仅开发环境注册，与现有 `/sbmmesh attrib` 互斥。先用相同参数在预览模式之间切换，查看位置、朝向、光照、overlay、自发光是否一致：

```text
/sbmmesh batch view immediate same 128
/sbmmesh batch view ordered same 128
/sbmmesh batch view batched same 128
/sbmmesh batch status
/sbmmesh batch stop
```

相同布局和数量的预览切换保留原有位置、几何与姿势。`same` 可替换为 `models` 或 `all`：

| 布局 | 模型 | 贴图 | 用途 |
| --- | --- | --- | --- |
| `same` | 同一个 | 同一张 | 同一 VBO 重复提交，观察材质 setup 和 VAO bind 合并 |
| `models` | 最多 24 个 | 同一张 | 观察跨网格共享材质的收益；共用贴图用于控制变量 |
| `all` | 最多 24 个 | 对应各自贴图 | 模型和材质多样性的对照 |

| 路径 | 绘制方式 |
| --- | --- |
| `immediate` | 现有生产整数属性后端，逐物品立即绘制静态 VBO |
| `ordered` | 先收集 VBO 命令，再保序提交 |
| `batched` | 先收集 VBO 命令，再按材质和网格分组提交 |

自动对照测量：

```text
/sbmmesh batch start same 128 120 3
/sbmmesh batch start models 128 120 3
/sbmmesh batch start all 128 120 3
```

参数依次为物品绘制数量（1–512）、每块采样帧数（30–1200）、重复次数（1–10）。默认 128 / 120 / 3。每轮随机排列三条 VBO 路径，每块先预热 45 帧。网格捕获和上传在准备阶段完成，不计入采样。开始新场景只失效本 benchmark owner 的网格缓存，以重新生成该场景的几何统计。

场景在玩家前方放置虚拟掉落物网格。每项有相机相对平移、父层旋转及统一缩放，再叠加固定相位的掉落浮动、旋转、散布和 ItemRenderer 居中变换。提交辅助函数只拿到已经变换过的 PoseStack，队列 flush 前所有逐物品和父层 push 都已 pop。冻结动画相位保证各路径几何及姿势相同。几何包含 QUADS 普通和 `_illuminated` 子树两种材质，实例 light、overlay 也各不相同。

## 输出与判读

结果在游戏目录 `benchmark-results/mesh-batch-日期时间-布局/`：

- `samples.csv`：逐帧 CPU、完整帧间隔、异步 GPU elapsed、物品数、Part 数、实际 draw 数、材质 setup 和 VAO bind 数。
- `summary.csv`：每条路径的 CPU median/p95、帧间隔 median/p95、GPU median 和提交次数。
- `capture.csv`：各几何键的普通/固定发光 Part 数及顶点数。
- `environment.txt`：GPU、Java、布局、帧数、视点、帧率配置及测量方法。

CPU 计时包含姿势处理、命令收集、分组、绘制和释放引用。两种队列路径直接记录 setup/bind/draw 调用；即时路径按已成功绘制的 Part 数记录。遇到 PENDING 或队列取消时，不采集该帧，并丢弃当前块已经记录的样本；重新准备完整网格、重新预热 45 帧，再从该块第一帧开始采样。最多允许 3 次恢复，准备阶段每次最多 60 秒有效运行时间；暂停时间不计入期限。格式／上下文代次变化、资源／世界切换、绘制异常和 GL 错误会终止测量，避免混入不同管线的数据。GPU 计时使用异步 `GL_TIME_ELAPSED`，可能包含 CPU 提交期间的 GPU 等待；不可用时填 `-1`，它不等同于纯 shader 执行时间。

测量期间保持相机和世界负载稳定。现有 stress 场景也会影响完整帧间隔，比较前可以 `/sbmmesh stress clear`。`same` 下若普通和发光各有一个 Part，128 项对应即时及队列各 256 draw；材质分组队列应降到 2 次材质 setup、2 次 VBO bind。纯原版模式及其缓冲／逐帧顶点生成代码已删除，预览和自动测量都只保留这三种 VBO 模式。

## 光影下的准备与恢复

旧版本准备完成后，只要有一帧未拿到全部网格就直接打印 `missing items/parts` 并停止。默认缓存空闲期限为 30 秒；固定顺序的首轮先跑 BATCHED，再跑纯原版，最后回到 IMMEDIATE。光影下纯原版块耗时过长时，期间未使用的 VBO 会到期，回到即时路径便需要重新捕获／上传。日志中准备完成约 55 秒后停止，与这条路径吻合；旧日志没有记录失败帧的模式和数量，不能据此排除其他失效原因。

当前自动测量已移除纯原版块。仍保留默认缓存语义；暂停较久或网格暂时未就绪时，按上述恢复流程重跑当前块，不把重建或缺失画面计入性能。恢复和超时信息包含 mode、block、实际／预期 items 与 parts、pending、discarded、firstPending key 和缓存代次；每个测量块开始时也会记录模式。

指令保持：`/sbmmesh batch start all 128 120 3`，默认每轮三块、三轮共九块。修改 enum 和指令树后需要重新启动包含新代码的开发客户端。光影开启本身不触发禁用；修改后的光影画面与自动测量结果仍需在实际客户端重跑。

## 接入边界

此场景在 `AFTER_BLOCK_ENTITIES` 内完成收集和 flush，用于验证与比较队列机制；实际 TaCZ ItemEntity/BEWLR、阴影和第一人称入口尚未接入。测试命令和资源在正式 jar 中排除。

队列由 pass 所有者控制：须在 framebuffer、投影、共享 shader 参数（包括方向光）及光影实体上下文变化前提交。完整矩阵副本允许调用者立即 pop/reuse PoseStack；其他共享状态沿用当前 pass。世界、GL 上下文、投影或网格代次变化时旧队列会取消；单项网格失效也会在 flush 前剔除。`close()` / `discard()` 只取消，`flush()` 排空后继续接受提交，`endBatch()` 执行最后一次排空后关闭队列。资源失效后的条目释放不会重新进入当前缓存。

需要协调原版顺序时，由 pass 所有者在明确边界直接提交原版缓冲并调用网格 flush。`isCurrent` 只验证所属 pass 身份；没有 beforeFlush 回调。透明或其他顺序敏感材质需要正确的排序/提交边界，不能直接使用 `MATERIAL`。`QUEUED_PREVIOUS` 在入队时说明实际选择的兼容旧 LOD，供调用方生成对应动态效果。flush 抛异常时可能已经绘制部分 Part，同一次 pass 不应再运行模型 fallback。

原有矩阵快照测试随包名迁移，本轮不新增单元测试。隐藏 GL 窗口的整数属性检查：后者在同一次 begin/end 属性作用域内连续绘制 32 次，读取像素验证变化的 light/overlay 与固定发光 UV2。游戏内外观一致性、实际性能和光影兼容需要用以上场景继续验收。
