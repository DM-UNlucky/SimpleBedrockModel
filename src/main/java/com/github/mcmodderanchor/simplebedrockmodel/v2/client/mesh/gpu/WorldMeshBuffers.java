package com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.MeshSink;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.world.WorldMeshMetrics;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 管理世界网格的 GPU 所有权和可变光照流；阶段调度由世界渲染入口负责。 */
public class WorldMeshBuffers {
    private static final int LIGHT_ATTRIBUTE_INDEX = 4;
    private final WorldMeshMetrics metrics;
    private final List<WorldMeshPart> parts = new ArrayList<>();
    public final VertexBufferPool pool = new VertexBufferPool();
    private long generation;

    public WorldMeshBuffers(WorldMeshMetrics metrics) {
        this.metrics = metrics;
    }

    public int size() {
        return parts.size();
    }

    public void clear() {
        RenderSystem.assertOnRenderThread();
        generation++;
        dropAllHandles();
        pool.clear();
    }

    public void removeOwner(String ownerId) { dropHandlesOf(ownerId); }

    /** 在绑定几何 VAO 后调用；每组连续的相同网格与光照只执行一次。 */
    public boolean prepareDraw(WorldMeshPart part, int packedLight) {
        if (part.lightMode() == WorldMeshPart.LightMode.UNIFORM) {
            GlStateManager._disableVertexAttribArray(LIGHT_ATTRIBUTE_INDEX);
            GL30.glVertexAttribI2i(LIGHT_ATTRIBUTE_INDEX, packedLight & 0xFFFF, packedLight >>> 16);
        } else {
            if (!part.isLightStreamAttached() && !attachLightStream(part)) return false;
            if (part.lightMode() == WorldMeshPart.LightMode.MUTABLE) flushLightUpdates(part);
        }
        return true;
    }

    /**
     * 提交局部网格；光照语义必须显式指定。调用前 owner 必须已注册。
     * 返回句柄由提交者独占管理，上传排队后由原版渲染线程处理；空网格或无世界返回 null。
     * MeshSink 在本方法内同步读取，返回后可复用。所有操作必须在渲染线程执行。
     */
    @Nullable
    public WorldMeshPart submit(String ownerId, MeshSink mesh, RenderType material, MeshLighting lighting) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(lighting, "lighting");
        if (mesh.isEmpty() || Minecraft.getInstance().level == null) return null;
        ChunkRenderDispatcher dispatcher = Minecraft.getInstance().levelRenderer.getChunkRenderDispatcher();
        if (dispatcher == null) {
            return null;
        }

        WorldMeshPart.LightMode lightMode = MeshLighting.classify(mesh, lighting);

        BufferBuilder.RenderedBuffer rendered = mesh.buildBuffer();
        if (rendered == null) return null;
        VertexBuffer buffer = pool.acquire(mesh.format());

        int lightBufferId = -1;
        ByteBuffer lightScratch = null;
        if (lightMode == WorldMeshPart.LightMode.MUTABLE) {
            lightScratch = ByteBuffer.allocateDirect(mesh.vertexCount() * 4).order(ByteOrder.nativeOrder());
            fillLightTemplate(lightScratch, mesh);
            lightBufferId = createLightBuffer(lightScratch);
        }

        WorldMeshPart handle = new WorldMeshPart(this, ownerId, buffer, mesh.format(),
                material, mesh.bounds(), generation, dispatcher.uploadChunkLayer(rendered, buffer),
                lightBufferId, lightScratch, lightMode);
        parts.add(handle);
        metrics.recordSubmit(lightMode);
        return handle;
    }

    /** 初始化 MUTABLE 光照流：动态范围留 0，固定范围写入捕获值。 */
    public void fillLightTemplate(ByteBuffer data, MeshSink mesh) {
        for (int i = 0, count = mesh.lightRunCount(); i < count; i++) {
            int value = mesh.lightRunValue(i);
            if (value == 0) {
                continue;
            }
            int end = Math.min(mesh.lightRunStart(i) + mesh.lightRunLength(i), mesh.vertexCount());
            for (int vertex = mesh.lightRunStart(i); vertex < end; vertex++) {
                data.putShort(vertex * 4, (short) (value & 0xFFFF));
                data.putShort(vertex * 4 + 2, (short) (value >>> 16));
            }
        }
        data.clear();
    }

    /** 建光照流缓冲（DYNAMIC：会被反复改写，让驱动把它放在适合动态更新的位置）。 */
    public int createLightBuffer(ByteBuffer data) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int id = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
        // DYNAMIC_DRAW：光照会被反复改写，让驱动把它放到适合动态更新的位置。
        RenderSystem.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        metrics.recordLightBufferCreated();
        return id;
    }

    /** 仅上传 MUTABLE 流的脏区间；几何缓冲及其它实例的光照均保持不变。 */
    public void flushLightUpdates(WorldMeshPart handle) {
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
                metrics.recordLightRangeUpload(endByte - startByte);
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
    public int findLightOffset(VertexFormat format) {
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
    public boolean attachLightStream(WorldMeshPart handle) {
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

    public void release(WorldMeshPart handle) {
        RenderSystem.assertOnRenderThread();
        if (!handle.retire()) return;
        parts.remove(handle);
        metrics.recordRelease();
        recycleWhenUploaded(handle);
    }

    /** LRU／TTL 淘汰时直接释放 GPU 存储，不再放回无容量上限的缓冲池。 */
    public void discard(WorldMeshPart handle) {
        RenderSystem.assertOnRenderThread();
        if (!parts.remove(handle)) return;
        handle.kill();
        metrics.recordRelease();
        recycleWhenUploaded(handle);
    }

    public void dropHandlesOf(String ownerId) {
        parts.removeIf(handle -> {
            if (!handle.ownerId().equals(ownerId)) {
                return false;
            }
            handle.kill();
            recycleWhenUploaded(handle);
            return true;
        });
    }

    public void dropAllHandles() {
        for (WorldMeshPart handle : parts) {
            handle.kill();
            recycleWhenUploaded(handle);
        }
        parts.clear();
    }

    /**
     * 只有上传完成后才能把缓冲放回池：提交是异步入队的，提前回收会让下一帧的 drain 写进别人的数据。
     */
    public void recycleWhenUploaded(WorldMeshPart handle) {
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

    public void recycle(WorldMeshPart handle) {
        if (!handle.markRecycled()) {
            return;
        }
        // 光照流缓冲随句柄一起回收。VAO 里可能还残留对它的引用（attribute 4 的绑定），
        // 但下一次使用这个几何 VAO 之前必定会重挂（新句柄的 lightStreamAttached 初值为 false），
        // 所以不会读到悬空的旧缓冲。
        if (handle.hasLightBuffer()) {
            RenderSystem.glDeleteBuffers(handle.lightBufferId());
            metrics.recordLightBufferReleased();
        }
        if (handle.isInvalidated() || handle.uploadFailed() || handle.generation() != generation) {
            handle.buffer().close();
        } else {
            pool.recycle(handle.format(), handle.buffer());
        }
    }

}
