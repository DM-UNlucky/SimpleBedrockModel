# OpenGL 整数属性输入基准：2026-10-06

本机真实 OpenGL 驱动测量；独立隐藏 GLFW 上下文，不加载 Minecraft。生产渲染代码未修改。

## 测量结论

- 仅 setter：I2I 59.2 ns/op、I4I 59.9 ns/op、IPointer 26.0 ns/op。包含共同循环开销。
- 单 VAO + draw：IPointer 118.0 ns/draw；加上绑定参数 VBO / 恢复几何 VBO 后为 163.3，I2I 为 139.9。
- 64 VAO + mat4 + draw：IPointer 223.3 ns/draw，比 I2I 255.8 少 12.7%；带两次绑定为 270.1，比 I2I 多 5.6%。
- 同一光照连续 16 draw：缓存后的 I2I 89.0 与 IPointer 89.7 ns/draw 接近；收益来自跳过重复状态设置。
- 本机 IPointer 修改 offset 本身没有显示出较高成本；逐次 bind/restore 可反转一属性路径的比较结果。实际方案应控制绑定次数，同时在目标移动设备复测。

## 环境与复现

- GPU：NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2
- OpenGL / 驱动：3.3.0 NVIDIA 591.74
- LWJGL：3.3.1 build 7；Java：17.0.7+8-LTS-224；系统：Windows 11 10.0
- 3 个独立 JVM；每 JVM 全局预热 2 秒，每个场景/模式预热 12 批，再测 31 批。
- 每批随机模式顺序；同一轮各模式使用相同参数序列。结果为全部 JVM 样本合并后的批平均 ns/op 中位数及 P95。
- OpenGL 3.3 core、无 debug context；8×8 RGBA8 FBO；无窗口交换、无垂直同步；无深度、混合或裁剪剔除。
- 整数参数查表 VBO 初始化上传一次，使用两个 GL_INT、8 字节 stride、divisor=1；没有计时内缓冲写入。

```powershell
./tools/run_vertex_attrib_benchmark.ps1
python tools/analyze_vertex_attrib_benchmark.py
```

依赖从已有 `build/classpath/runClient_minecraftClasspath.txt` 读取，限定 LWJGL core / GLFW / OpenGL 和 Windows x64 natives。
源码在 `tools/VertexAttribBenchmark.java`；参数可用 `-Forks`、`-Warmup`、`-Samples`、`-OutputDirectory` 调整。

## 模式

| 模式 | 计时区间内的属性操作 |
|---|---|
| CONSTANT_FIXED | 常量属性提前设置，逐 draw 不改参数；开销下界，不代表变化光照 |
| ARRAY_FIXED | 属性数组提前设置固定 offset，逐 draw 不改参数；开销下界 |
| I2I / I4I | 每次调用对应常量整数属性 setter |
| I2I_CACHED | 相同值跳过 setter；缓存属于上下文，基准中没有外部 draw 干扰 |
| IPOINTER_EVERY | 参数 VBO 在计时前绑定，每次只调用 IPointer 更新 offset |
| IPOINTER_BIND_RESTORE | 每次绑定参数 VBO，设置 IPointer，再绑定回几何 VBO |
| IPOINTER_CACHED | 参数 VBO 在计时前绑定，按每个 VAO/属性缓存 offset，相同值跳过 |

数组模式的固定 overlay 也启用数组并从表读取；常量模式固定 overlay 使用常量属性。
两个属性使用分别变化的光照/overlay 序列，但轮换 VAO 时某些 overlay 值重复，因此缓存可跳过部分 UV1 设置。

## 正确性验证与计时边界

- 每个 JVM、每种模式、一/两个变化属性，都执行同一 VAO 的两次连续 draw，中间不等待 GPU。
- 两个 draw 通过 scissor 分别写入左/右区域，再读回像素，对照四个整数分量计算出的颜色，允许最多 1/255 转换误差。全部通过。
- GLSL 使用真实 `ivec2` 输入参与输出颜色，检查 attribute location 和矩阵 uniform 有效；不会只测未使用的属性。
- 每批计时前完成 VAO 设置和 glFinish；CPU 列只包括循环、属性 setter、场景指定的 bind/uniform、draw 提交。
- Completion 包括批末 glEndQuery 和 glFinish 等待，不包括计时前准备或查询结果读取。
- GPU elapsed 来自 GL_TIME_ELAPSED，是该批 GPU 时间线间隔，可包含 CPU 提交不及时造成的空闲，不能当作纯着色器执行时间。
- 仅 setter 场景没有 draw，驱动可能合并尚未使用的状态；结论应优先参考带 draw 的场景。
- ARRAY_BUFFER 的绑定独立于 VAO：先绑定参数 VBO 后，轮换几何 VAO 不会把它重新绑定成几何 VBO。因此 optimized 模式可成立。

## 结果

单位均为 ns/op；draw 场景的一次 op 是一次 draw，state_only 是一次循环操作。

### state_only_light

仅修改 UV2 属性，无 draw（每批 100,000 次）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 2.4 | 3.3 | 2.2–2.5 | 2.4 | 0.0 |
| ARRAY_FIXED | 2.4 | 3.2 | 2.3–2.5 | 2.4 | 0.0 |
| I2I | 59.2 | 64.8 | 58.7–59.8 | 60.3 | 0.0 |
| I4I | 59.9 | 66.6 | 59.3–60.6 | 61.2 | 0.0 |
| I2I_CACHED | 59.4 | 70.0 | 58.2–60.8 | 61.0 | 0.0 |
| IPOINTER_EVERY | 26.0 | 28.3 | 25.8–26.2 | 26.0 | 0.0 |
| IPOINTER_BIND_RESTORE | 70.8 | 77.5 | 70.6–71.0 | 70.8 | 0.0 |
| IPOINTER_CACHED | 26.6 | 29.5 | 26.3–26.9 | 26.6 | 0.0 |

### draw_shared_light

同一 VAO，每 draw 改 UV2，overlay 固定（每批 2,048 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 87.6 | 107.9 | 85.6–88.6 | 105.8 | 24.5 |
| ARRAY_FIXED | 87.9 | 102.2 | 87.4–88.7 | 107.6 | 15.5 |
| I2I | 139.9 | 183.7 | 139.4–141.1 | 163.2 | 62.5 |
| I4I | 142.2 | 188.6 | 141.1–143.5 | 165.1 | 67.5 |
| I2I_CACHED | 139.9 | 169.7 | 138.3–141.9 | 161.9 | 77.5 |
| IPOINTER_EVERY | 118.0 | 159.7 | 117.9–118.2 | 140.4 | 80.0 |
| IPOINTER_BIND_RESTORE | 163.3 | 207.4 | 163.0–163.6 | 183.0 | 109.0 |
| IPOINTER_CACHED | 118.6 | 157.4 | 117.1–119.1 | 141.3 | 84.0 |

### draw_shared_both

同一 VAO，每 draw 改 UV1/UV2（每批 2,048 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 85.4 | 106.3 | 85.2–87.7 | 103.4 | 31.0 |
| ARRAY_FIXED | 87.2 | 129.2 | 86.3–90.8 | 105.6 | 33.5 |
| I2I | 196.3 | 270.8 | 195.9–197.5 | 221.9 | 126.5 |
| I4I | 201.2 | 233.0 | 197.3–211.2 | 227.3 | 136.0 |
| I2I_CACHED | 199.0 | 257.3 | 196.0–200.8 | 222.1 | 126.5 |
| IPOINTER_EVERY | 142.7 | 202.7 | 139.8–146.0 | 167.1 | 109.5 |
| IPOINTER_BIND_RESTORE | 191.7 | 258.5 | 191.4–193.7 | 212.4 | 128.0 |
| IPOINTER_CACHED | 142.5 | 190.8 | 141.2–144.5 | 165.2 | 106.0 |

### draw_shared_group16_light

同一 VAO，每 16 draw 使用同一光照（每批 2,048 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 86.3 | 112.1 | 85.4–86.7 | 104.7 | 15.0 |
| ARRAY_FIXED | 87.1 | 103.0 | 85.9–87.7 | 104.4 | 15.0 |
| I2I | 139.8 | 185.7 | 139.4–142.7 | 165.9 | 76.5 |
| I4I | 140.2 | 193.8 | 139.7–143.6 | 169.3 | 79.5 |
| I2I_CACHED | 89.0 | 118.4 | 88.0–90.5 | 105.8 | 19.5 |
| IPOINTER_EVERY | 118.7 | 161.9 | 118.1–119.7 | 142.9 | 85.5 |
| IPOINTER_BIND_RESTORE | 164.7 | 209.2 | 161.7–167.9 | 184.5 | 109.0 |
| IPOINTER_CACHED | 89.7 | 116.5 | 89.6–90.6 | 107.3 | 26.0 |

### draw_item64_light

轮换 64 VAO、逐 draw 上传 mat4、改 UV2（每批 2,048 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 184.7 | 229.3 | 180.3–193.9 | 214.0 | 145.5 |
| ARRAY_FIXED | 193.1 | 254.8 | 188.3–207.5 | 222.5 | 171.0 |
| I2I | 255.8 | 317.9 | 253.0–262.0 | 286.6 | 210.0 |
| I4I | 250.5 | 325.9 | 245.6–258.9 | 282.7 | 209.0 |
| I2I_CACHED | 247.7 | 323.2 | 236.4–265.9 | 270.5 | 188.0 |
| IPOINTER_EVERY | 223.3 | 298.3 | 219.6–228.6 | 255.7 | 192.5 |
| IPOINTER_BIND_RESTORE | 270.1 | 351.4 | 267.5–285.9 | 300.8 | 231.5 |
| IPOINTER_CACHED | 225.1 | 289.6 | 218.3–236.8 | 266.2 | 195.0 |

### draw_item64_both

轮换 64 VAO、逐 draw 上传 mat4、改 UV1/UV2（每批 2,048 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 187.6 | 254.7 | 174.8–197.1 | 218.1 | 136.5 |
| ARRAY_FIXED | 192.3 | 263.9 | 182.9–202.9 | 227.7 | 156.5 |
| I2I | 308.3 | 374.2 | 296.7–311.1 | 342.5 | 248.0 |
| I4I | 312.6 | 394.7 | 301.7–328.1 | 350.9 | 249.5 |
| I2I_CACHED | 301.5 | 373.0 | 299.0–313.2 | 337.2 | 235.5 |
| IPOINTER_EVERY | 244.0 | 331.1 | 235.4–249.0 | 275.8 | 191.0 |
| IPOINTER_BIND_RESTORE | 295.9 | 373.2 | 283.8–310.7 | 333.7 | 234.5 |
| IPOINTER_CACHED | 221.4 | 296.3 | 210.6–232.5 | 245.8 | 174.5 |

### draw_item64_mesh128_light

轮换 64 VAO、逐 draw 上传 mat4、128 三角形、改 UV2（每批 512 draw）

| 模式 | CPU 中位数 | CPU P95 | 各 JVM CPU 中位数范围 | Completion 中位数 | GPU elapsed 中位数 |
|---|---:|---:|---:|---:|---:|
| CONSTANT_FIXED | 182.8 | 229.1 | 179.9–187.3 | 1059.6 | 784.0 |
| ARRAY_FIXED | 190.0 | 226.8 | 177.9–195.5 | 1055.7 | 786.0 |
| I2I | 255.5 | 392.3 | 248.6–266.6 | 1129.3 | 784.0 |
| I4I | 241.4 | 363.9 | 230.7–252.1 | 1171.7 | 784.0 |
| I2I_CACHED | 243.0 | 362.8 | 228.3–248.4 | 1148.6 | 784.0 |
| IPOINTER_EVERY | 213.7 | 275.8 | 212.5–216.6 | 1117.0 | 786.0 |
| IPOINTER_BIND_RESTORE | 266.4 | 395.6 | 259.6–284.8 | 1131.4 | 786.0 |
| IPOINTER_CACHED | 223.0 | 287.4 | 215.6–234.0 | 1093.2 | 786.0 |

## 适用范围

这是本机 NVIDIA 桌面驱动的微基准，不是 TaCZ 游戏内帧时间，也不是移动端 GL/GLES 转译层测量。
128 三角形场景是重复全屏三角形，用于观察 GPU 工作增加后的差异，不模拟枪模几何或材质成本。
材质切换、shader apply、贴图、Mojang 状态封装、Oculus/AR hook 等未进入计时区间。
VAO 设置、enable/divisor 和常量初值都在计时前处理，各模式都按稳态路径比较；不能直接等同当前生产代码整条调用链。
I2I_CACHED 的上下文缓存只有在能保证没有其他启用数组的 draw 改变 current attribute 时有效，不能照搬为跨 Minecraft 物品调用的全局缓存。

[OpenGL 顶点属性指针状态定义](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glVertexAttribPointer.xhtml)；
[GL_TIME_ELAPSED 定义](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glBeginQuery.xhtml)。

原始样本保存在同目录 `samples.csv`；汇总保存在 `summary.csv`；每个 JVM 的环境和日志在 `build/vertex-attrib-benchmark/fork-*`。
