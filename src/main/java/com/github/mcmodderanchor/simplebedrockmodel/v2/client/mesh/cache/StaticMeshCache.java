package com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.cache;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.GeometryCollector;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.MeshGeometryProvider;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.MeshSink;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu.VertexBufferPool;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu.MeshIntegerAttributes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu.MeshVertexFormat;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.debug.MeshRenderDebug;

import com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 临时共享几何的所有权、捕获预算、上传生命周期和按所有者失效。 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = SimpleBedrockModel.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class StaticMeshCache {
    public enum Preparation { READY, PENDING, UNSUPPORTED }
    private static final int MAX_ENTRIES = 128;
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static final int FORMAT_CHECK_DRAWS = 60;
    private static VertexBufferPool POOL = new VertexBufferPool();
    public static final MeshIntegerAttributes INTEGER_ATTRIBUTES = new MeshIntegerAttributes();
    public static final Map<CacheKey, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final IdleMeshCache<CacheKey, Entry> IDLE = new IdleMeshCache<>(MAX_ENTRIES, MAX_BYTES,
            System::nanoTime, StaticMeshCache::discard);

    private static ClientLevel level;
    private static GLCapabilities geometryContext;
    private static VertexFormat probedFormat;
    private static int formatCheckCountdown = FORMAT_CHECK_DRAWS;
    public static long generation;
    private static long captureWindow = Long.MIN_VALUE;
    private static int capturesInWindow;
    private static long captureNanosInWindow;

    public static class Part {
        public final VertexBuffer buffer;
        public final VertexFormat requestedFormat;
        public final RenderType material;
        public final CompletableFuture<Void> upload;
        public final boolean instanceLight;
        public final GLCapabilities context;
        public final MeshIntegerAttributes.PartState attributes = new MeshIntegerAttributes.PartState();

        public Part(VertexBuffer buffer, VertexFormat requestedFormat, RenderType material,
             CompletableFuture<Void> upload, boolean instanceLight) {
            this.buffer = buffer;
            this.requestedFormat = requestedFormat;
            this.material = material;
            this.upload = upload;
            this.instanceLight = instanceLight;
            this.context = GL.getCapabilities();
        }
    }

    public record CacheKey(ResourceLocation owner, Object geometry) {}

    public static class Entry {
        public final CacheKey key;
        public int references;
        public List<Part> parts;
        public long bytes;
        public long retryAtNanos;
        public int failures;
        public boolean unsupported;

        public Entry(CacheKey key) { this.key = key; }

        public boolean ready() {
            return parts != null && !parts.isEmpty()
                    && parts.stream().allMatch(part -> part.upload.isDone() && !part.upload.isCompletedExceptionally());
        }

        public boolean failed() {
            return parts != null && parts.stream().anyMatch(part -> part.upload.isCompletedExceptionally());
        }
    }

    /**
     * 渲染线程上的轻量能力预检，供需要接管待准备网格的接入方使用。
     * 返回 false 表示缺少 GL 能力，或光照／覆盖坐标超出参数表范围，应使用旧渲染路径。
     * 此处不捕获几何，也不分配或上传参数表；分配失败仍可重试。
     */
    public static boolean supportsInstanceAttributes(int packedLight, int packedOverlay) {
        RenderSystem.assertOnRenderThread();
        GLCapabilities current = GL.getCapabilities();
        if (geometryContext != current) {
            if (geometryContext != null) {
                // 丢弃旧池的记录；不能在新上下文中删除或复用旧上下文的 GL 对象标识。
                POOL = new VertexBufferPool();
                clear();
            }
            geometryContext = current;
        }
        return INTEGER_ATTRIBUTES.supports(current, packedLight, packedOverlay);
    }

    public static Entry acquire(CacheKey key) {
        Entry entry = CACHE.computeIfAbsent(key, Entry::new);
        pin(entry);
        return entry;
    }

    public static void pin(Entry entry) {
        IDLE.remove(entry.key);
        entry.references++;
    }

    public static void releaseEntry(Entry entry, Duration idleRetention) {
        if (--entry.references == 0 && CACHE.get(entry.key) == entry) {
            IDLE.retain(entry.key, entry, entry.bytes, idleRetention);
        }
    }

    public static void discard(Entry entry) {
        CACHE.remove(entry.key, entry);
        retire(entry, true);
    }

    public static void failEntry(Entry entry) {
        if (entry.parts == null) return;
        retire(entry, true);
        entry.retryAtNanos = System.nanoTime() + retryDelay(entry.failures++);
    }

    public static boolean prepareRequest(Object key, MeshGeometryProvider provider, PoseStack pose,
                                          int packedLight, int packedOverlay, Duration retention) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative()) throw new IllegalArgumentException("Negative idle retention");
        retention.toNanos();
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null || !supportsInstanceAttributes(packedLight, packedOverlay)) return false;
        if (current != level) {
            clear();
            level = current;
        }
        if (--formatCheckCountdown <= 0) {
            formatCheckCountdown = FORMAT_CHECK_DRAWS;
            VertexFormat format = MeshVertexFormat.probe();
            if (format != null && probedFormat != null && probedFormat != format) clear();
            if (format != null) probedFormat = format;
        }
        IDLE.evictExpired();
        return true;
    }

    public static Preparation prepareTarget(Entry entry, MeshGeometryProvider provider) {
        if (entry.unsupported) return Preparation.UNSUPPORTED;
        if (entry.failed()) {
            retire(entry, true);
            entry.parts = null;
            entry.retryAtNanos = System.nanoTime() + retryDelay(entry.failures++);
        }
        if (entry.parts == null) {
            long now = System.nanoTime();
            if (now < entry.retryAtNanos || !captureBudgetAvailable(now)) return Preparation.PENDING;
            long start = System.nanoTime();
            boolean captured = capture(entry, entry.key, provider);
            capturesInWindow++;
            captureNanosInWindow += System.nanoTime() - start;
            if (!captured) {
                if (entry.unsupported) return Preparation.UNSUPPORTED;
                entry.retryAtNanos = System.nanoTime() + retryDelay(entry.failures++);
                return Preparation.PENDING;
            }
        }
        if (!entry.ready()) return Preparation.PENDING;
        if (!layoutSupported(entry)) return Preparation.UNSUPPORTED;
        if (MeshRenderDebug.attributes == null && !INTEGER_ATTRIBUTES.ensureReady()) return Preparation.PENDING;

        return Preparation.READY; // 网格已就绪，可立即绘制，也可由批次持有引用后入队。
    }

    public static boolean captureBudgetAvailable(long now) {
        long window = now / 16_000_000L;
        if (captureWindow != window) {
            captureWindow = window;
            capturesInWindow = 0;
            captureNanosInWindow = 0;
        }
        return capturesInWindow == 0 || capturesInWindow < 4 && captureNanosInWindow < 2_000_000L;
    }

    public static long retryDelay(int failures) {
        return Math.min(5_000_000_000L, 100_000_000L << Math.min(failures, 5));
    }

    public static boolean capture(Entry entry, Object key, MeshGeometryProvider provider) {
        ChunkRenderDispatcher dispatcher = Minecraft.getInstance().levelRenderer.getChunkRenderDispatcher();
        if (dispatcher == null) return false;
        GeometryCollector collector = new GeometryCollector();
        List<Part> parts = new ArrayList<>();
        try {
            if (!provider.capture(collector)) return false;
            Map<GeometryCollector.Pass, MeshSink> meshes = collector.snapshot();
            if (meshes.isEmpty()) {
                entry.unsupported = true;
                return false;
            }
            long bytes = 0;
            for (Map.Entry<GeometryCollector.Pass, MeshSink> pass : meshes.entrySet()) {
                MeshSink mesh = pass.getValue();
                boolean dynamic = false;
                boolean fixed = false;
                for (int i = 0; i < mesh.lightRunCount(); i++) {
                    if (mesh.lightRunValue(i) == 0) dynamic = true;
                    else fixed = true;
                }
                if (dynamic && fixed) {
                    parts.forEach(part -> release(part, generation, true));
                    SimpleBedrockModel.LOGGER.warn("Immediate mesh has mixed per-vertex lighting: {}", key);
                    entry.unsupported = true;
                    return false;
                }
                BufferBuilder.RenderedBuffer rendered = mesh.buildBuffer();
                if (rendered == null) continue;
                VertexBuffer buffer = POOL.acquire(mesh.format());
                parts.add(new Part(buffer, mesh.format(), pass.getKey().material(),
                        dispatcher.uploadChunkLayer(rendered, buffer), dynamic));
                bytes += mesh.estimatedBytes();
            }
            if (parts.isEmpty()) return false;
            entry.parts = parts;
            entry.bytes = bytes;
            entry.failures = 0;
            return true;
        } catch (RuntimeException exception) {
            parts.forEach(part -> release(part, generation, true));
            SimpleBedrockModel.LOGGER.warn("Failed to capture immediate static mesh {}", key, exception);
            return false;
        }
    }

    public static boolean layoutSupported(Entry entry) {
        for (Part part : entry.parts) {
            if (!INTEGER_ATTRIBUTES.layoutSupported(part.attributes, part.buffer)) return false;
        }
        return true;
    }

    public static void retire(Entry entry, boolean invalidated) {
        if (entry.parts == null) return;
        long retiringGeneration = generation;
        for (Part part : entry.parts) release(part, retiringGeneration, invalidated);
        entry.parts = null;
        entry.bytes = 0;
    }

    public static void release(Part part, long retiringGeneration, boolean invalidated) {
        part.upload.whenComplete((ignored, failure) -> {
            Runnable action = () -> {
                if (part.context != GL.getCapabilities()) return;
                if (invalidated || failure != null || retiringGeneration != generation) part.buffer.close();
                else POOL.recycle(part.requestedFormat, part.buffer);
            };
            if (RenderSystem.isOnRenderThread()) {
                action.run();
            } else {
                RenderSystem.recordRenderCall(action::run);
            }
        });
    }

    @SubscribeEvent
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) IDLE.evictExpired();
    }

    @SubscribeEvent
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        Runnable action = () -> {
            if (event.getLevel() == level) {
                clear();
                level = null;
            }
        };
        if (RenderSystem.isOnRenderThread()) {
            action.run();
        } else {
            RenderSystem.recordRenderCall(action::run);
        }
    }

    /** 失效指定接入方的缓存几何，并取消引用这些几何的待绘制命令。 */
    public static void invalidateOwner(ResourceLocation owner) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(owner, "owner");
        List<Entry> retiring = CACHE.values().stream().filter(entry -> entry.key.owner().equals(owner)).toList();
        for (Entry entry : retiring) {
            IDLE.remove(entry.key);
            discard(entry);
        }
    }

    /** 资源重载或渲染上下文丢失时，失效全部物品网格。 */
    public static void clear() {
        RenderSystem.assertOnRenderThread();
        if (geometryContext != null && geometryContext != GL.getCapabilities()) POOL = new VertexBufferPool();
        generation++;
        IDLE.clear();
        for (Entry entry : CACHE.values()) {
            retire(entry, true);
        }
        CACHE.clear();
        POOL.clear();
        INTEGER_ATTRIBUTES.close();
        probedFormat = null;
        formatCheckCountdown = FORMAT_CHECK_DRAWS;
    }
}
