package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.config.WorldMeshConfig;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 世界空间网格绘制的库侧入口（原型）。
 *
 * <p>职责：VBO 池化与复用、上传提交（转交 {@link ChunkRenderDispatcher#uploadChunkLayer}）、
 * 单一 {@link RenderLevelStageEvent} 监听与统一绘制。{@link WorldMeshGroup} 管理对象、缓存、策略和失效，
 * 调用方只提供几何描述与对象状态。底层句柄和上传入口不作为公共 API。</p>
 *
 * <p>绘制阶段固定为 {@code AFTER_BLOCK_ENTITIES}：它由 {@code LevelRenderer.renderLevel} 直接派发，
 * 不受 Embeddium 替换地形管线影响，也不在 Iris/Oculus 的 shadow pass 中触发。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class WorldMeshRenderer {
    private static final int MAX_SAMPLERS = 12;

    /** 光照的 attribute 位置：{@code VertexFormat} 元素列表序号（NEW_ENTITY 里是 UV2）。 */
    private static final int LIGHT_ATTRIBUTE_INDEX = 4;

    /**
     * 世界空间光源方向，取自 {@code com.mojang.blaze3d.platform.Lighting}（那边是 private 常量）。
     * 静态网格的法线按世界朝向烘焙，所以必须用世界方向，不能复用 {@link RenderSystem#setupShaderLights}。
     */
    private static final Vector3f LIGHT_0_OVERWORLD = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1_OVERWORLD = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f LIGHT_0_NETHER = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1_NETHER = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    private static final Map<String, WorldMeshGroup<?>> MESH_GROUPS = new LinkedHashMap<>();

    public enum Invalidation { WORLD, RESOURCES, SHUTDOWN }

    private static final List<ShardHandle> SHARDS = new ArrayList<>();
    private static final VertexBufferPool POOL = new VertexBufferPool();
    private static final WorldMeshMetrics METRICS = new WorldMeshMetrics();

    private static final List<CollectedShard> COLLECTED = new ArrayList<>();
    private static final LinkedHashMap<RenderType, List<CollectedShard>> GROUPS = new LinkedHashMap<>();
    private static final List<RenderType> GROUP_ORDER = new ArrayList<>();
    private static final Matrix4f SCRATCH_VIEW = new Matrix4f();
    private static int collectedCount;

    private static boolean enabled = true;
    private static boolean listening;
    private static long stateUpdateTick;
    private static ClientLevel level;

    /** 顶点格式漂移的探测间隔；光影开关会让 Iris/AR 换掉 NEW_ENTITY 对应的格式。 */
    private static final int FORMAT_CHECK_FRAMES = 60;
    private static int formatCheckCountdown = FORMAT_CHECK_FRAMES;
    private static VertexFormat probedFormat;
    private static long generation;

    /** 绘制枚举结果：句柄 + 本实例的原点与包围盒。去重后同一个句柄会被多个实例重复枚举。 */
    private static final class CollectedShard {
        ShardHandle handle;
        Vec3 origin;
        AABB bounds;
        /** 归一后的实例光照：INSTANCE 使用实例值，FIXED 归零。 */
        int light;
    }

    private WorldMeshRenderer() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 运行时开关；关闭时丢弃几何缓存，保留渲染组对象，重新打开后会重新捕获。 */
    public static void setEnabled(boolean value) {
        RenderSystem.assertOnRenderThread();
        if (value == enabled) {
            return;
        }
        enabled = value;
        if (value) {
            ensureListening();
        } else {
            invalidate(Invalidation.SHUTDOWN);
        }
    }

    public static <K> WorldMeshGroup<K> createGroup(String id, WorldMeshStrategy defaultStrategy) {
        return createGroup(id, defaultStrategy, EnumSet.allOf(WorldMeshStrategy.class));
    }

    /** 在渲染线程创建渲染组；同 ID 不允许重复创建，关闭后可重新创建。 */
    public static <K> WorldMeshGroup<K> createGroup(String id, WorldMeshStrategy defaultStrategy,
                                                Set<WorldMeshStrategy> supported) {
        RenderSystem.assertOnRenderThread();
        if (MESH_GROUPS.containsKey(id)) throw new IllegalArgumentException("Duplicate group: " + id);
        WorldMeshGroup<K> group = new WorldMeshGroup<>(id, defaultStrategy, supported);
        MESH_GROUPS.put(id, group);
        group.applyPolicy();
        ensureListening();
        return group;
    }

    static void unregister(WorldMeshGroup<?> group) {
        if (!MESH_GROUPS.remove(group.id(), group)) return;
        dropHandlesOf(group.id());
        clearFrameData();
        if (MESH_GROUPS.isEmpty()) {
            generation++;
            POOL.clear();
        }
    }

    /** 修改客户端配置中的策略选择，并立即更新已注册渲染组。 */
    public static void setStrategy(StrategyOverride value) {
        RenderSystem.assertOnRenderThread();
        WorldMeshConfig.STRATEGY.set(Objects.requireNonNull(value, "value"));
        WorldMeshConfig.STRATEGY.save();
        for (WorldMeshGroup<?> group : MESH_GROUPS.values()) group.applyPolicy();
    }

    public static List<WorldMeshGroupStats> groupStats() {
        RenderSystem.assertOnRenderThread();
        return MESH_GROUPS.values().stream().map(WorldMeshGroup::stats).toList();
    }

    /** 保留逻辑对象，重新解析几何描述并重建缓存。 */
    public static void clearCaches() { invalidate(Invalidation.RESOURCES); }

    /**
     * 提交局部网格；光照语义必须显式指定。调用前 owner 必须已注册。
     * 返回句柄由提交者独占管理，上传排队后由原版渲染线程处理；空网格或无世界返回 null。
     * MeshSink 在本方法内同步读取，返回后可复用。所有操作必须在渲染线程执行。
     */
    @Nullable
    static ShardHandle submit(WorldMeshGroup<?> owner, MeshSink mesh, RenderType material, MeshLighting lighting) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(lighting, "lighting");
        if (MESH_GROUPS.get(owner.id()) != owner) {
            throw new IllegalArgumentException("Group is not registered: " + owner.id());
        }
        if (!enabled) {
            throw new IllegalStateException("World mesh renderer is disabled");
        }
        if (mesh.isEmpty() || !ensureLevel()) {
            return null;
        }
        ChunkRenderDispatcher dispatcher = Minecraft.getInstance().levelRenderer.getChunkRenderDispatcher();
        if (dispatcher == null) {
            return null;
        }

        VertexBuffer buffer = POOL.acquire(mesh.format());
        BufferBuilder builder = new BufferBuilder(Math.max(1536, mesh.estimatedBytes() / 6 + 64));
        builder.begin(mesh.mode(), mesh.format());
        mesh.emitTo(builder);
        BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
        if (rendered == null) {
            POOL.recycle(mesh.format(), buffer);
            return null;
        }

        int runCount = mesh.lightRunCount();
        int[] runStarts = new int[runCount];
        int[] runLengths = new int[runCount];
        int[] runValues = new int[runCount];
        boolean anyFollowsInstance = false;
        boolean anyModelConstant = false;
        for (int i = 0; i < runCount; i++) {
            runStarts[i] = mesh.lightRunStart(i);
            runLengths[i] = mesh.lightRunLength(i);
            runValues[i] = mesh.lightRunValue(i);
            // 模板值 0 = 跟随实例光照；非 0 = 模型自带光照（自发光 bone）。
            if (lighting == MeshLighting.INSTANCE && runValues[i] == 0) {
                anyFollowsInstance = true;
            } else {
                anyModelConstant = true;
            }
        }

        ShardHandle.LightMode lightMode;
        int lightBufferId = -1;
        ByteBuffer lightScratch = null;
        if (lighting == MeshLighting.MUTABLE) {
            lightMode = ShardHandle.LightMode.MUTABLE;
            lightScratch = ByteBuffer.allocateDirect(mesh.vertexCount() * 4).order(ByteOrder.nativeOrder());
            fillLightTemplate(lightScratch, runStarts, runLengths, runValues, runCount, mesh.vertexCount());
            lightBufferId = createLightBuffer(lightScratch);
        } else if (anyFollowsInstance && anyModelConstant) {
            // 混合：固定段与动态段必须在同一份按顶点的数据里，只能上光照缓冲。
            lightMode = ShardHandle.LightMode.STREAM;
            lightScratch = ByteBuffer.allocateDirect(mesh.vertexCount() * 4).order(ByteOrder.nativeOrder());
            fillLightTemplate(lightScratch, runStarts, runLengths, runValues, runCount, mesh.vertexCount());
            lightBufferId = createLightBuffer(lightScratch);
        } else if (anyFollowsInstance) {
            // 全动态：整个 draw 是同一个光照值 → 用常量属性，零缓冲零上传。
            lightMode = ShardHandle.LightMode.UNIFORM;
        } else {
            // 全固定：光照已经烘在几何 buffer 里，attribute 4 直接读它。
            lightMode = ShardHandle.LightMode.FIXED;
        }

        ShardHandle handle = new ShardHandle(owner.id(), buffer, mesh.format(),
                material, mesh.bounds(), generation, dispatcher.uploadChunkLayer(rendered, buffer), runStarts, runLengths, runValues,
                lightBufferId, lightScratch, lightMode);
        SHARDS.add(handle);
        METRICS.recordSubmit(lightMode);
        return handle;
    }

    /**
     * 把"光照模板"写进 CPU 侧缓冲：值为 0 的顶点先留 0（绘制前按实例光照 patch），其余写模型自带的常量。
     *
     * <p>这是"部分 bone 固定满亮"的落点：{@code TreeBedrockModel.renderBone} 用
     * {@code bone.illuminated ? LightTexture.pack(15,15) : packedLight} 决定光照，所以只要<b>烘焙时传 0</b>，
     * 模板里的值就精确地是"0 = 跟随实例、非 0 = 模型固定"，不需要改 SBM 的渲染路径。</p>
     */
    private static void fillLightTemplate(ByteBuffer data, int[] runStarts, int[] runLengths, int[] runValues,
                                          int runCount, int vertexCount) {
        for (int i = 0; i < runCount; i++) {
            int value = runValues[i];
            if (value == 0) {
                continue;
            }
            int end = Math.min(runStarts[i] + runLengths[i], vertexCount);
            for (int vertex = runStarts[i]; vertex < end; vertex++) {
                data.putShort(vertex * 4, (short) (value & 0xFFFF));
                data.putShort(vertex * 4 + 2, (short) (value >>> 16));
            }
        }
        data.clear();
    }

    /** 建光照流缓冲（DYNAMIC：会被反复改写，让驱动把它放在适合动态更新的位置）。 */
    private static int createLightBuffer(ByteBuffer data) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int id = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
        // DYNAMIC_DRAW：光照会被反复改写，让驱动把它放到适合动态更新的位置。
        RenderSystem.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        METRICS.recordLightBufferCreated();
        return id;
    }

    /**
     * 把实例光照写进光照流：只覆盖"模板值为 0"的运行段，自发光段保持创建时写入的常量。
     *
     * <p>整个缓冲是 4 B/顶点的连续数组，所以这里不存在交错布局那种"按 run 算偏移会写错字节"的问题。</p>
     */
    private static void patchLight(ShardHandle handle, int packedLight) {
        ByteBuffer data = handle.lightScratch();
        if (data == null) {
            return;
        }
        short low = (short) (packedLight & 0xFFFF);
        short high = (short) (packedLight >>> 16);
        int runs = handle.lightRunCount();
        for (int i = 0; i < runs; i++) {
            if (handle.lightRunValue(i) != 0) {
                continue;
            }
            int end = handle.lightRunStart(i) + handle.lightRunLength(i);
            for (int vertex = handle.lightRunStart(i); vertex < end; vertex++) {
                data.putShort(vertex * 4, low);
                data.putShort(vertex * 4 + 2, high);
            }
        }
        data.clear();

        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, handle.lightBufferId());
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        handle.setLightValue(packedLight);
        METRICS.recordLightPatch();
    }

    /** 仅上传 MUTABLE 流的脏区间；几何缓冲及其它实例的光照均保持不变。 */
    private static void flushLightUpdates(ShardHandle handle) {
        LightRangeUpdates updates = handle.lightUpdates();
        if (updates == null || updates.ranges().isEmpty()) return;
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, handle.lightBufferId());
        try {
            for (Map.Entry<Integer, Integer> range : updates.ranges().entrySet()) {
                int startByte = range.getKey() * 4;
                int endByte = range.getValue() * 4;
                ByteBuffer data = handle.lightScratch().duplicate();
                data.position(startByte).limit(endByte);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) startByte, data);
                METRICS.recordLightRangeUpload(endByte - startByte);
            }
            updates.clear();
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        }
    }

    /**
     * 光照（UV2）在该格式里的字节偏移；找不到返回 -1。
     *
     * <p>必须按<b>实际上传后的格式</b>算，不能写死 NEW_ENTITY：Oculus 在光影包要求扩展顶点格式时会把
     * {@code DefaultVertexFormat.NEW_ENTITY} 的 setupBufferState 重定向到 {@code IrisVertexFormats.ENTITY}，
     * stride 与偏移都会变。</p>
     */
    private static int findLightOffset(VertexFormat format) {
        int offset = 0;
        for (VertexFormatElement element : format.getElements()) {
            if (element == DefaultVertexFormat.ELEMENT_UV2) {
                return offset;
            }
            offset += element.getByteSize();
        }
        return -1;
    }

    /** 几何 VAO 绑定后设置 UV2 来源；上传和池复用后的首次绘制必须重新设置。 */
    private static boolean attachLightStream(ShardHandle handle) {
        int geometryBuffer = handle.buffer().vertexBufferId;
        int target;
        int stride;
        int offset;
        if (handle.hasLightBuffer()) {
            target = handle.lightBufferId();
            stride = 4;
            offset = 0;
        } else {
            VertexFormat format = handle.buffer().getFormat();
            if (format == null) return false;
            offset = findLightOffset(format);
            if (offset < 0) return false;
            target = geometryBuffer;
            stride = format.getVertexSize();
        }
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, target);
        GlStateManager._enableVertexAttribArray(LIGHT_ATTRIBUTE_INDEX);
        GlStateManager._vertexAttribIPointer(LIGHT_ATTRIBUTE_INDEX, 2, GL11.GL_SHORT, stride, offset);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, geometryBuffer);
        handle.markLightStreamAttached();
        return true;
    }

    /**
     * 顶点格式漂移自愈：每隔 {@link #FORMAT_CHECK_FRAMES} 帧用一次 1 个 quad 的探针确认"现在
     * {@code NEW_ENTITY} 会被哪个格式接手"，一变就整体失效重烘。
     *
     * <p>为什么需要它：Iris 在有光影包时无条件打开扩展顶点格式，AR 还会把 Iris 的 ENTITY 换成自己的
     * 扩展版本。已上传的 VBO 不会自动跟随格式变化；这里只检测格式对象变化，
     * 不能覆盖所有 shader 语义变化或兼容性问题。资源内容重载由独立监听处理。</p>
     */
    private static void checkFormatDrift() {
        if (--formatCheckCountdown > 0) {
            return;
        }
        formatCheckCountdown = FORMAT_CHECK_FRAMES;
        VertexFormat probed = probeFormat();
        if (probed == null) {
            return;
        }
        if (probedFormat == null) {
            probedFormat = probed;
            return;
        }
        if (probedFormat != probed) {
            probedFormat = probed;
            METRICS.recordFormatChange();
            invalidate(Invalidation.RESOURCES);
        }
    }

    /** 用最小代价问一次"当前生效的顶点格式"：构造 1 个 quad 再丢弃，不碰 GL。 */
    private static VertexFormat probeFormat() {
        BufferBuilder probe = new BufferBuilder(1024);
        probe.begin(VertexFormat.Mode.QUADS, MeshSink.FORMAT);
        for (int i = 0; i < 4; i++) {
            probe.vertex(0.0, 0.0, 0.0).color(255, 255, 255, 255).uv(0.0F, 0.0F)
                    .overlayCoords(0).uv2(0).normal(0.0F, 1.0F, 0.0F).endVertex();
        }
        BufferBuilder.RenderedBuffer rendered = probe.endOrDiscardIfEmpty();
        if (rendered == null) {
            return null;
        }
        VertexFormat format = rendered.drawState().format();
        rendered.release();
        return format;
    }

    static void release(ShardHandle handle) {
        RenderSystem.assertOnRenderThread();
        if (!handle.retire()) return;
        SHARDS.remove(handle);
        METRICS.recordRelease();
        recycleWhenUploaded(handle);
    }

    /** 失效全部渲染组缓存；世界切换、资源重载与运行开关关闭都会走这里。 */
    static void invalidate(Invalidation kind) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(kind, "kind");
        generation++;
        dropAllHandles();
        POOL.clear();
        for (WorldMeshGroup<?> group : MESH_GROUPS.values()) {
            group.invalidate(kind);
        }
        clearFrameData();
    }

    private static void clearFrameData() {
        COLLECTED.clear();
        GROUPS.clear();
        GROUP_ORDER.clear();
        collectedCount = 0;
        METRICS.clearFrame();
    }

    /** 按需获取统计快照；与渲染状态一样，应在渲染线程读取。 */
    public static WorldMeshStats stats() {
        RenderSystem.assertOnRenderThread();
        return METRICS.snapshot(enabled, MESH_GROUPS.size(), SHARDS.size(), POOL);
    }

    private static void ensureListening() {
        if (listening) {
            return;
        }
        listening = true;
        MinecraftForge.EVENT_BUS.addListener(WorldMeshRenderer::onRenderStage);
        MinecraftForge.EVENT_BUS.addListener(WorldMeshRenderer::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(WorldMeshRenderer::onClientTick);
    }

    static long stateUpdateTick() { return stateUpdateTick; }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) stateUpdateTick++;
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        Runnable unload = () -> {
            if (event.getLevel() == level) {
                level = null;
                invalidate(Invalidation.WORLD);
            }
        };
        if (RenderSystem.isOnRenderThread()) {
            unload.run();
        } else {
            RenderSystem.recordRenderCall(unload::run);
        }
    }

    /**
     * 在渲染组登记实例前同步世界身份；可能触发 WORLD 失效，故须先调用再写入实例数据。
     * 返回是否存在客户端世界，不代表渲染器开启或上传就绪。
     */
    static boolean ensureLevel() {
        RenderSystem.assertOnRenderThread();
        ClientLevel current = Minecraft.getInstance().level;
        if (current == level) {
            return current != null;
        }
        level = current;
        invalidate(Invalidation.WORLD);
        return current != null;
    }

    private static void dropHandlesOf(String ownerId) {
        SHARDS.removeIf(handle -> {
            if (!handle.ownerId().equals(ownerId)) {
                return false;
            }
            handle.kill();
            recycleWhenUploaded(handle);
            return true;
        });
    }

    private static void dropAllHandles() {
        for (ShardHandle handle : SHARDS) {
            handle.kill();
            recycleWhenUploaded(handle);
        }
        SHARDS.clear();
    }

    /**
     * 只有上传完成后才能把缓冲放回池：提交是异步入队的，提前回收会让下一帧的 drain 写进别人的数据。
     */
    private static void recycleWhenUploaded(ShardHandle handle) {
        CompletableFuture<Void> upload = handle.upload();
        if (upload.isDone()) {
            recycle(handle);
            return;
        }
        upload.whenComplete((ignored, failure) -> {
            if (RenderSystem.isOnRenderThread()) {
                recycle(handle);
            } else {
                RenderSystem.recordRenderCall(() -> recycle(handle));
            }
        });
    }

    private static void recycle(ShardHandle handle) {
        if (!handle.markRecycled()) {
            return;
        }
        // 光照流缓冲随句柄一起回收。VAO 里可能还残留对它的引用（attribute 4 的绑定），
        // 但下一次使用这个几何 VAO 之前必定会重挂（新句柄的 lightStreamAttached 初值为 false），
        // 所以不会读到悬空的旧缓冲。
        if (handle.hasLightBuffer()) {
            RenderSystem.glDeleteBuffers(handle.lightBufferId());
            METRICS.recordLightBufferReleased();
        }
        if (handle.isInvalidated() || handle.uploadFailed() || handle.generation() != generation) {
            handle.buffer().close();
        } else {
            POOL.recycle(handle.format(), handle.buffer());
        }
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        if (!ensureLevel() || MESH_GROUPS.isEmpty()) return;
        if (!enabled) {
            for (WorldMeshGroup<?> group : MESH_GROUPS.values()) group.refreshObjects();
            return;
        }
        long now = System.nanoTime();
        METRICS.beginFrame(now);
        checkFormatDrift();
        long passStart = now;

        for (WorldMeshGroup<?> group : MESH_GROUPS.values()) {
            group.prepareFrame();
        }

        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        Frustum frustum = event.getFrustum();
        Matrix4f baseView = event.getPoseStack().last().pose();
        Matrix4f projection = event.getProjectionMatrix();

        collectedCount = 0;
        for (WorldMeshGroup<?> group : MESH_GROUPS.values()) {
            group.collect(WorldMeshRenderer::collect);
        }

        for (List<CollectedShard> group : GROUPS.values()) {
            group.clear();
        }
        GROUP_ORDER.clear();

        int pending = 0;
        int failed = 0;
        int culled = 0;
        int shards = 0;
        for (int i = 0; i < collectedCount; i++) {
            CollectedShard shard = COLLECTED.get(i);
            ShardHandle handle = shard.handle;
            if (!handle.isAlive()) {
                continue;
            }
            if (handle.uploadFailed()) {
                failed++;
                continue;
            }
            if (!handle.isUploaded()) {
                pending++;
                continue;
            }
            if (shard.bounds != null && !frustum.isVisible(shard.bounds)) {
                culled++;
                continue;
            }
            RenderType material = handle.material();
            List<CollectedShard> group = GROUPS.computeIfAbsent(material, ignored -> new ArrayList<>());
            if (group.isEmpty()) {
                GROUP_ORDER.add(material);
            }
            group.add(shard);
            shards++;
        }

        METRICS.recordPrepared(shards, culled, pending, failed);
        if (GROUP_ORDER.isEmpty()) {
            METRICS.endFrame(0, 0, passStart);
            return;
        }

        GROUP_ORDER.sort(Comparator.comparingInt(WorldMeshRenderer::materialOrder));

        int draws = 0;
        int setups = 0;
        for (RenderType type : GROUP_ORDER) {
            List<CollectedShard> shardsOfType = GROUPS.get(type);
            // 同一个几何体的实例必须连续，且同一份光照连续，才能在切组时只 patch 一次光照流。
            // 没有光照流的 shard（光照全为常量）统一按 light=0 归一，不参与分组。
            shardsOfType.sort(Comparator
                    .comparingInt((CollectedShard shard) -> shard.handle.id())
                    .thenComparingInt(shard -> shard.light)
                    .thenComparingDouble(shard ->
                            shard.bounds == null ? 0.0 : shard.bounds.distanceToSqr(cameraPosition)));
            type.setupRenderState();
            setups++;
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) {
                    continue;
                }
                uploadSharedUniforms(shader, baseView, projection);
                int index = 0;
                int size = shardsOfType.size();
                while (index < size) {
                    CollectedShard first = shardsOfType.get(index);
                    ShardHandle handle = first.handle;
                    int light = first.light;
                    int end = index + 1;
                    while (end < size) {
                        CollectedShard next = shardsOfType.get(end);
                        if (next.handle != handle || next.light != light) {
                            break;
                        }
                        end++;
                    }

                    handle.buffer().bind();
                    if (handle.lightMode() == ShardHandle.LightMode.UNIFORM) {
                        // 全动态：整个 draw 是同一个光照值 → 关掉 attribute 数组、给整型常量属性。
                        // 数组启用位属于 VAO，常量属性值属于上下文；每个光照组都要设置。
                        GlStateManager._disableVertexAttribArray(LIGHT_ATTRIBUTE_INDEX);
                        GL30.glVertexAttribI2i(LIGHT_ATTRIBUTE_INDEX,
                                light & 0xFFFF, light >>> 16);
                    } else {
                        // 重挂是正确性要求（不是探针）：几何上传与池复用都会把 attribute 4 冲掉。
                        if (!handle.isLightStreamAttached() && !attachLightStream(handle)) {
                            index = end;
                            continue;
                        }
                        if (handle.lightMode() == ShardHandle.LightMode.MUTABLE) {
                            flushLightUpdates(handle);
                        } else if (handle.lightMode() == ShardHandle.LightMode.STREAM && handle.lightValue() != light) {
                            patchLight(handle, light);
                        }
                    }
                    for (int i = index; i < end; i++) {
                        Vec3 origin = shardsOfType.get(i).origin;
                        SCRATCH_VIEW.set(baseView).translate(
                                (float) (origin.x - cameraPosition.x),
                                (float) (origin.y - cameraPosition.y),
                                (float) (origin.z - cameraPosition.z));
                        if (shader.MODEL_VIEW_MATRIX != null) {
                            shader.MODEL_VIEW_MATRIX.set(SCRATCH_VIEW);
                            shader.MODEL_VIEW_MATRIX.upload();
                        }
                        handle.buffer().draw();
                        draws++;
                    }
                    index = end;
                }
            } finally {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    shader.clear();
                }
                VertexBuffer.unbind();
                type.clearRenderState();
            }
        }

        METRICS.endFrame(draws, setups, passStart);
    }

    private static void collect(ShardHandle handle, Vec3 origin, AABB worldBounds, int packedLight) {
        CollectedShard shard;
        if (collectedCount < COLLECTED.size()) {
            shard = COLLECTED.get(collectedCount);
        } else {
            shard = new CollectedShard();
            COLLECTED.add(shard);
        }
        shard.handle = handle;
        shard.origin = origin;
        shard.bounds = worldBounds;
        // FIXED / MUTABLE 使用网格自身的光照，忽略实例值；
        // 全动态（UNIFORM）虽然不占缓冲，但它的常量属性值就是实例光照，所以必须按光照分组。
        shard.light = handle.lightMode() == ShardHandle.LightMode.FIXED
                || handle.lightMode() == ShardHandle.LightMode.MUTABLE ? 0 : packedLight;
        collectedCount++;
    }

    /**
     * 每个 RenderType 只做一次：共享 uniform + 一次 {@code apply()}。
     * 逐 shard 只上传 ModelViewMat、bind、draw，这就是相对 {@code drawWithShader} 的收益来源。
     */
    private static void uploadSharedUniforms(ShaderInstance shader, Matrix4f baseView, Matrix4f projection) {
        for (int i = 0; i < MAX_SAMPLERS; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(baseView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.GLINT_ALPHA != null) {
            shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }

        boolean constantAmbient = Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.effects().constantAmbientLight();
        if (shader.LIGHT0_DIRECTION != null) {
            shader.LIGHT0_DIRECTION.set(constantAmbient ? LIGHT_0_NETHER : LIGHT_0_OVERWORLD);
        }
        if (shader.LIGHT1_DIRECTION != null) {
            shader.LIGHT1_DIRECTION.set(constantAmbient ? LIGHT_1_NETHER : LIGHT_1_OVERWORLD);
        }
        shader.apply();
    }

    private static int materialOrder(RenderType type) {
        int chunkLayer = type.getChunkLayerId();
        return chunkLayer >= 0 ? chunkLayer : 100;
    }
}
