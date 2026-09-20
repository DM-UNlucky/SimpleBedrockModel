package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.MeshSink;
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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 静态方块实体渲染的库侧入口（原型）。
 *
 * <p>职责：VBO 池化与复用、上传提交（转交 {@link ChunkRenderDispatcher#uploadChunkLayer}）、
 * 单一 {@link RenderLevelStageEvent} 监听与统一绘制。实例收集、过期淘汰、脏标记与网格生产由
 * {@link Source} 实现方决定；释放动作必须经过 {@link ShardHandle#release()}，引用归零后由本类回收缓冲。</p>
 *
 * <p>绘制阶段固定为 {@code AFTER_BLOCK_ENTITIES}：它由 {@code LevelRenderer.renderLevel} 直接派发，
 * 不受 Embeddium 替换地形管线影响，也不在 Iris/Oculus 的 shadow pass 中触发。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class StaticWorldRenderer {
    /** 运行开关；未打开时本层零开销（不注册事件、不建缓冲池）。 */
    public static final String ENABLE_PROPERTY = "simplebedrockmodel.staticWorld";

    private static final int MAX_SAMPLERS = 12;
    private static final int FRAME_SAMPLES = 120;

    /** NEW_ENTITY 交错布局里 UV2（光照）的字节偏移；与 {@link MeshSink#FORMAT} 一起钉死。 */
    private static final int LIGHT_OFFSET = lightOffset(MeshSink.FORMAT);

    /**
     * 世界空间光源方向，取自 {@code com.mojang.blaze3d.platform.Lighting}（那边是 private 常量）。
     * 静态网格的法线按世界朝向烘焙，所以必须用世界方向，不能复用 {@link RenderSystem#setupShaderLights}。
     */
    private static final Vector3f LIGHT_0_OVERWORLD = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1_OVERWORLD = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f LIGHT_0_NETHER = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1_NETHER = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static final List<Source> ORDERED_SOURCES = new ArrayList<>();
    private static final List<ShardHandle> SHARDS = new ArrayList<>();
    private static final VboPool POOL = new VboPool();

    private static final List<CollectedShard> COLLECTED = new ArrayList<>();
    private static final LinkedHashMap<RenderType, List<CollectedShard>> GROUPS = new LinkedHashMap<>();
    private static final List<RenderType> GROUP_ORDER = new ArrayList<>();
    private static final Matrix4f SCRATCH_VIEW = new Matrix4f();
    private static int collectedCount;

    private static boolean enabled = Boolean.getBoolean(ENABLE_PROPERTY);
    private static boolean listening;
    private static ClientLevel level;

    private static int submits;
    private static int releases;
    private static int lightUpdates;
    private static int lastShards;
    private static int lastDraws;
    private static int lastSetups;
    private static int lastClears;
    private static int lastCulled;
    private static int lastPending;

    private static final long[] FRAME_DELTAS = new long[FRAME_SAMPLES];
    private static int frameSampleIndex;
    private static int frameSampleCount;
    private static long lastFrameNanos;
    private static double lastPassMicros;

    /** 绘制枚举结果：句柄 + 本实例的原点与包围盒。去重后同一个句柄会被多个实例重复枚举。 */
    private static final class CollectedShard {
        ShardHandle handle;
        Vec3 origin;
        AABB bounds;
    }

    private StaticWorldRenderer() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 运行时开关；关闭时丢弃全部几何并通知实现方（SHUTDOWN），重新打开后会重新烘焙。 */
    public static void setEnabled(boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        if (value) {
            ensureListening();
        } else {
            invalidate(Source.Kind.SHUTDOWN);
        }
    }

    public static void register(Source source) {
        SOURCES.put(source.id(), source);
        reorderSources();
        if (enabled) {
            ensureListening();
        }
    }

    public static void unregister(String id) {
        Source source = SOURCES.remove(id);
        if (source == null) {
            return;
        }
        reorderSources();
        dropHandlesOf(id);
        source.onInvalidate(Source.Kind.SHUTDOWN);
    }

    /**
     * 提交一份已捕获的静态网格。必须在渲染线程调用；返回的句柄初始引用数为 1，由提交者持有。
     * 上传在下一帧 {@code LevelRenderer.compileChunks} 中由原版 drain，future 完成即代表已进入显存。
     */
    public static ShardHandle submit(Source owner, MeshSink mesh, ShardMeta meta, double distanceSq) {
        if (!enabled) {
            throw new IllegalStateException("Static world layer is disabled");
        }
        RenderSystem.assertOnRenderThread();
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
        for (int i = 0; i < runCount; i++) {
            runStarts[i] = mesh.lightRunStart(i);
            runLengths[i] = mesh.lightRunLength(i);
            runValues[i] = mesh.lightRunValue(i);
        }

        ShardHandle handle = new ShardHandle(owner.id(), buffer, mesh.format(), mesh.vertexCount(),
                meta, distanceSq, dispatcher.uploadChunkLayer(rendered, buffer), runStarts, runLengths, runValues);
        SHARDS.add(handle);
        submits++;
        return handle;
    }

    /**
     * 原地只改光照：把 mesh 中等于 {@code oldLight} 的顶点运行段改写成 {@code newLight}，其余字节不动。
     *
     * <p>调用方必须保证没有别的实例共享该句柄（否则会把它们的光照一起改掉）；这里只负责 GL 与句柄账本。
     * 成功后句柄的 {@link ShardHandle#packedLight()} 与运行段表同步更新，几何与包围盒不变。</p>
     */
    public static boolean rewriteLight(ShardHandle handle, int oldLight, int newLight) {
        if (!enabled || oldLight == newLight || !handle.isAlive() || !handle.isUploaded()) {
            return false;
        }
        int runs = handle.lightRunCount();
        if (runs == 0) {
            return false;
        }
        RenderSystem.assertOnRenderThread();

        int bufferId = handle.buffer().vertexBufferId;

        int from = Integer.MAX_VALUE;
        int to = -1;
        for (int i = 0; i < runs; i++) {
            if (handle.lightRunValue(i) == oldLight) {
                from = Math.min(from, handle.lightRunStart(i));
                to = Math.max(to, handle.lightRunStart(i) + handle.lightRunLength(i));
            }
        }
        if (to < 0 || to > handle.vertexCount()) {
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc((to - from) * 4);
            for (int i = 0; i < runs; i++) {
                int start = handle.lightRunStart(i);
                int length = handle.lightRunLength(i);
                if (start + length <= from || start >= to) {
                    continue;
                }
                int value = handle.lightRunValue(i) == oldLight ? newLight : handle.lightRunValue(i);
                for (int vertex = start; vertex < start + length; vertex++) {
                    int index = (vertex - from) * 4;
                    data.putShort(index, (short) (value & 0xFFFF));
                    data.putShort(index + 2, (short) (value >>> 16));
                }
            }
            // 先记下原来的绑定再恢复：Embeddium 等会缓存自己的绑定状态，直接绑 0 会让它们的缓存与实际不一致。
            int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
            try {
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,
                        (long) from * MeshSink.VERTEX_STRIDE_BYTES + LIGHT_OFFSET, data);
            } finally {
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            }
        }

        handle.applyLightRewrite(oldLight, newLight);
        lightUpdates++;
        return true;
    }

    private static int lightOffset(VertexFormat format) {
        int offset = 0;
        for (VertexFormatElement element : format.getElements()) {
            if (element == DefaultVertexFormat.ELEMENT_UV2) {
                return offset;
            }
            offset += element.getByteSize();
        }
        throw new IllegalStateException("Static mesh format has no light element");
    }

    static void release(ShardHandle handle) {
        int references = handle.releaseCount();
        if (references != 0) {
            return;
        }
        handle.retire();
        SHARDS.remove(handle);
        releases++;
        recycleWhenUploaded(handle);
    }

    /** 丢弃全部几何并通知实现方；世界切换、资源重载与运行开关关闭都会走这里。 */
    public static void invalidate(Source.Kind kind) {
        dropAllHandles();
        for (Source source : SOURCES.values()) {
            source.onInvalidate(kind);
        }
        lastShards = 0;
        lastDraws = 0;
        lastSetups = 0;
        lastClears = 0;
        lastCulled = 0;
        lastPending = 0;
    }

    public static String stats() {
        return "staticWorld{enabled=" + enabled
                + ", sources=" + SOURCES.size()
                + ", shards=" + SHARDS.size()
                + ", last{shards=" + lastShards + ", draws=" + lastDraws
                + ", setups=" + lastSetups + ", clears=" + lastClears
                + ", culled=" + lastCulled + ", pending=" + lastPending + "}"
                + ", frameMs=" + String.format(Locale.ROOT, "%.2f", averageFrameMillis())
                + ", passUs=" + String.format(Locale.ROOT, "%.1f", lastPassMicros)
                + ", submits=" + submits + ", releases=" + releases + ", lightUpdates=" + lightUpdates
                + ", pool{created=" + POOL.created() + ", reused=" + POOL.reused() + ", idle=" + POOL.idleCount() + "}}";
    }

    /** 相邻两次 AFTER_BLOCK_ENTITIES 的间隔，即整帧耗时（含全部渲染与逻辑）。 */
    private static double averageFrameMillis() {
        if (frameSampleCount == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < frameSampleCount; i++) {
            total += FRAME_DELTAS[i];
        }
        return total / 1_000_000.0 / frameSampleCount;
    }

    private static void ensureListening() {
        if (listening) {
            return;
        }
        listening = true;
        MinecraftForge.EVENT_BUS.addListener(StaticWorldRenderer::onRenderStage);
    }

    private static void reorderSources() {
        ORDERED_SOURCES.clear();
        ORDERED_SOURCES.addAll(SOURCES.values());
        ORDERED_SOURCES.sort(Comparator.comparingInt(Source::stageOrder));
    }

    private static boolean ensureLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == level) {
            return current != null;
        }
        level = current;
        invalidate(Source.Kind.WORLD);
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
        POOL.recycle(handle.format(), handle.buffer());
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        if (!enabled || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            FRAME_DELTAS[frameSampleIndex] = now - lastFrameNanos;
            frameSampleIndex = (frameSampleIndex + 1) % FRAME_SAMPLES;
            if (frameSampleCount < FRAME_SAMPLES) {
                frameSampleCount++;
            }
        }
        lastFrameNanos = now;
        lastPassMicros = 0.0;
        if (!ensureLevel() || SOURCES.isEmpty() || SHARDS.isEmpty()) {
            return;
        }
        long passStart = now;

        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        Frustum frustum = event.getFrustum();
        Matrix4f baseView = event.getPoseStack().last().pose();
        Matrix4f projection = event.getProjectionMatrix();

        collectedCount = 0;
        for (Source source : ORDERED_SOURCES) {
            source.forEachShard(StaticWorldRenderer::collect);
        }

        for (List<CollectedShard> group : GROUPS.values()) {
            group.clear();
        }
        GROUP_ORDER.clear();

        int pending = 0;
        int culled = 0;
        int shards = 0;
        for (int i = 0; i < collectedCount; i++) {
            CollectedShard shard = COLLECTED.get(i);
            ShardHandle handle = shard.handle;
            if (!handle.isAlive()) {
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
            RenderType material = handle.meta().material();
            List<CollectedShard> group = GROUPS.computeIfAbsent(material, ignored -> new ArrayList<>());
            if (group.isEmpty()) {
                GROUP_ORDER.add(material);
            }
            group.add(shard);
            shards++;
        }

        lastPending = pending;
        lastCulled = culled;
        lastShards = shards;
        if (GROUP_ORDER.isEmpty()) {
            lastDraws = 0;
            lastSetups = 0;
            lastClears = 0;
            return;
        }

        GROUP_ORDER.sort(Comparator.comparingInt(StaticWorldRenderer::materialOrder));

        int draws = 0;
        int setups = 0;
        int clears = 0;
        for (RenderType type : GROUP_ORDER) {
            List<CollectedShard> shardsOfType = GROUPS.get(type);
            shardsOfType.sort(Comparator.comparingDouble(shard ->
                    shard.bounds == null ? 0.0 : shard.bounds.distanceToSqr(cameraPosition)));
            type.setupRenderState();
            setups++;
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) {
                    continue;
                }
                uploadSharedUniforms(shader, baseView, projection);
                for (CollectedShard shard : shardsOfType) {
                    Vec3 origin = shard.origin;
                    SCRATCH_VIEW.set(baseView).translate(
                            (float) (origin.x - cameraPosition.x),
                            (float) (origin.y - cameraPosition.y),
                            (float) (origin.z - cameraPosition.z));
                    if (shader.MODEL_VIEW_MATRIX != null) {
                        shader.MODEL_VIEW_MATRIX.set(SCRATCH_VIEW);
                        shader.MODEL_VIEW_MATRIX.upload();
                    }
                    shard.handle.buffer().bind();
                    shard.handle.buffer().draw();
                    draws++;
                }
            } finally {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    shader.clear();
                }
                VertexBuffer.unbind();
                type.clearRenderState();
                clears++;
            }
        }

        lastDraws = draws;
        lastSetups = setups;
        lastClears = clears;
        lastPassMicros = (System.nanoTime() - passStart) / 1000.0;
    }

    private static void collect(ShardHandle handle, Vec3 origin, AABB worldBounds) {
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
