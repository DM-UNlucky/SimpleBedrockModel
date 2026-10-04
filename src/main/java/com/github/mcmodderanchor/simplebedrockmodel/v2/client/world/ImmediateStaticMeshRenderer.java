package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Static meshes drawn at the caller's current item/entity render position. Geometry is shared by key;
 * the caller supplies the current pose, light and overlay. All entry points run on the render thread.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = SimpleBedrockModel.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ImmediateStaticMeshRenderer {
    public enum DrawResult {
        DRAWN, DRAWN_PREVIOUS, PENDING, UNSUPPORTED;

        public boolean drawn() { return this == DRAWN || this == DRAWN_PREVIOUS; }
    }

    @FunctionalInterface
    public interface GeometryProvider {
        boolean capture(GeometryCollector collector);
    }

    private static final int MAX_ENTRIES = 128;
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static final int FORMAT_CHECK_DRAWS = 60;
    private static final VertexBufferPool POOL = new VertexBufferPool();
    private static final Map<Object, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final IdleMeshCache<Object, Entry> IDLE = new IdleMeshCache<>(MAX_ENTRIES, MAX_BYTES,
            System::nanoTime, ImmediateStaticMeshRenderer::discard);

    private static ClientLevel level;
    private static VertexFormat probedFormat;
    private static int formatCheckCountdown = FORMAT_CHECK_DRAWS;
    private static long generation;
    private static long captureWindow = Long.MIN_VALUE;
    private static int capturesInWindow;
    private static long captureNanosInWindow;

    private static final class Part {
        final VertexBuffer buffer;
        final VertexFormat requestedFormat;
        final RenderType material;
        final CompletableFuture<Void> upload;
        final boolean instanceLight;

        Part(VertexBuffer buffer, VertexFormat requestedFormat, RenderType material,
             CompletableFuture<Void> upload, boolean instanceLight) {
            this.buffer = buffer;
            this.requestedFormat = requestedFormat;
            this.material = material;
            this.upload = upload;
            this.instanceLight = instanceLight;
        }
    }

    private static final class Entry {
        final Object key;
        int references;
        List<Part> parts;
        long bytes;
        long retryAtNanos;
        int failures;
        boolean unsupported;

        Entry(Object key) { this.key = key; }

        boolean ready() {
            return parts != null && !parts.isEmpty()
                    && parts.stream().allMatch(part -> part.upload.isDone() && !part.upload.isCompletedExceptionally());
        }

        boolean failed() {
            return parts != null && parts.stream().anyMatch(part -> part.upload.isCompletedExceptionally());
        }
    }

    private ImmediateStaticMeshRenderer() {}

    /**
     * PENDING owns this render call: no old renderer should run while capture or upload is pending.
     * beforeDraw is invoked only for a fully uploaded mesh, before changing RenderType state.
     */
    public static DrawResult tryDraw(Object key, GeometryProvider provider, PoseStack pose,
                                     int packedLight, int packedOverlay, Runnable beforeDraw) {
        return tryDraw(key, null, provider, pose, packedLight, packedOverlay, beforeDraw,
                MeshCachePolicy.DEFAULT.idleRetention());
    }

    /**
     * Prepares the target, then draws it or the caller's exact compatible previous key.
     * The previous key is looked up only; it is never captured as a fallback. DRAWN_PREVIOUS lets
     * the caller align dynamic effects with the mesh actually drawn. PENDING still owns the call.
     * Retention starts at the last draw/prepare call; zero disables idle retention. Active entries
     * are pinned, with at most 128 / 256 MiB of estimated vertex data retained in the idle LRU.
     */
    public static DrawResult tryDraw(Object key, Object previousKey, GeometryProvider provider, PoseStack pose,
                                     int packedLight, int packedOverlay, Runnable beforeDraw,
                                     Duration idleRetention) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(beforeDraw, "beforeDraw");
        Objects.requireNonNull(idleRetention, "idleRetention");
        if (idleRetention.isNegative()) throw new IllegalArgumentException("Negative idle retention");
        idleRetention.toNanos();
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return DrawResult.UNSUPPORTED;
        if (current != level) {
            clear();
            level = current;
        }
        if (--formatCheckCountdown <= 0) {
            formatCheckCountdown = FORMAT_CHECK_DRAWS;
            VertexFormat format = WorldMeshRenderer.probeFormat();
            if (format != null && probedFormat != null && probedFormat != format) {
                clear();
            }
            if (format != null) probedFormat = format;
        }

        IDLE.evictExpired();
        Entry entry = acquire(key);
        Entry previous = previousKey == null || previousKey.equals(key) ? null : CACHE.get(previousKey);
        if (previous != null) pin(previous);
        try {
            DrawResult result = tryDrawTarget(entry, provider, pose, packedLight, packedOverlay, beforeDraw);
            if (result.drawn() || previous == null || !previous.ready() || !layoutSupported(previous)) return result;
            // Failed draw calls may have submitted some parts: only reuse another mesh before any draw.
            if (entry.ready() && !entry.unsupported && layoutSupported(entry)) return result;
            beforeDraw.run();
            return draw(previous, pose, packedLight, packedOverlay) ? DrawResult.DRAWN_PREVIOUS : DrawResult.PENDING;
        } finally {
            releaseEntry(entry, idleRetention);
            if (previous != null) releaseEntry(previous, idleRetention);
        }
    }

    private static Entry acquire(Object key) {
        Entry entry = CACHE.computeIfAbsent(key, Entry::new);
        pin(entry);
        return entry;
    }

    private static void pin(Entry entry) {
        IDLE.remove(entry.key);
        entry.references++;
    }

    private static void releaseEntry(Entry entry, Duration idleRetention) {
        if (--entry.references == 0) IDLE.retain(entry.key, entry, entry.bytes, idleRetention);
    }

    private static void discard(Entry entry) {
        CACHE.remove(entry.key, entry);
        retire(entry, true);
    }

    private static DrawResult tryDrawTarget(Entry entry, GeometryProvider provider, PoseStack pose,
                                            int packedLight, int packedOverlay, Runnable beforeDraw) {
        if (entry.unsupported) return DrawResult.UNSUPPORTED;
        if (entry.failed()) {
            retire(entry, true);
            entry.parts = null;
            entry.retryAtNanos = System.nanoTime() + retryDelay(entry.failures++);
        }
        if (entry.parts == null) {
            long now = System.nanoTime();
            if (now < entry.retryAtNanos || !captureBudgetAvailable(now)) return DrawResult.PENDING;
            long start = System.nanoTime();
            boolean captured = capture(entry, entry.key, provider);
            capturesInWindow++;
            captureNanosInWindow += System.nanoTime() - start;
            if (!captured) {
                if (entry.unsupported) return DrawResult.UNSUPPORTED;
                entry.retryAtNanos = System.nanoTime() + retryDelay(entry.failures++);
                return DrawResult.PENDING;
            }
        }
        if (!entry.ready()) return DrawResult.PENDING;
        if (!layoutSupported(entry)) return DrawResult.UNSUPPORTED;

        beforeDraw.run();
        return draw(entry, pose, packedLight, packedOverlay) ? DrawResult.DRAWN : DrawResult.PENDING;
    }

    private static boolean captureBudgetAvailable(long now) {
        long window = now / 16_000_000L;
        if (captureWindow != window) {
            captureWindow = window;
            capturesInWindow = 0;
            captureNanosInWindow = 0;
        }
        return capturesInWindow == 0 || capturesInWindow < 4 && captureNanosInWindow < 2_000_000L;
    }

    private static long retryDelay(int failures) {
        return Math.min(5_000_000_000L, 100_000_000L << Math.min(failures, 5));
    }

    private static boolean capture(Entry entry, Object key, GeometryProvider provider) {
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
                VertexBuffer buffer = POOL.acquire(mesh.format());
                BufferBuilder builder = new BufferBuilder(Math.max(1536, mesh.estimatedBytes() / 6 + 64));
                builder.begin(mesh.mode(), mesh.format());
                mesh.emitTo(builder);
                BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
                if (rendered == null) {
                    POOL.recycle(mesh.format(), buffer);
                    continue;
                }
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

    private static boolean layoutSupported(Entry entry) {
        for (Part part : entry.parts) {
            VertexFormat format = part.buffer.getFormat();
            if (format == null || attributeIndex(format, DefaultVertexFormat.ELEMENT_UV1) < 0
                    || attributeIndex(format, DefaultVertexFormat.ELEMENT_UV2) < 0) return false;
        }
        return true;
    }

    private static int attributeIndex(VertexFormat format, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = format.getElements();
        for (int i = 0; i < elements.size(); i++) if (elements.get(i) == element) return i;
        return -1;
    }

    private static boolean draw(Entry entry, PoseStack pose, int packedLight, int packedOverlay) {
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        for (Part part : entry.parts) {
            part.material.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) return false;
                WorldMeshRenderer.uploadSharedUniforms(shader, modelView, RenderSystem.getProjectionMatrix());
                uploadItemLighting(shader, pose);
                part.buffer.bind();
                VertexFormat format = part.buffer.getFormat();
                int overlayIndex = attributeIndex(format, DefaultVertexFormat.ELEMENT_UV1);
                int lightIndex = attributeIndex(format, DefaultVertexFormat.ELEMENT_UV2);
                GlStateManager._disableVertexAttribArray(overlayIndex);
                GL30.glVertexAttribI2i(overlayIndex, packedOverlay & 0xFFFF, packedOverlay >>> 16);
                if (part.instanceLight) {
                    GlStateManager._disableVertexAttribArray(lightIndex);
                    GL30.glVertexAttribI2i(lightIndex, packedLight & 0xFFFF, packedLight >>> 16);
                } else {
                    GlStateManager._enableVertexAttribArray(lightIndex);
                }
                part.buffer.draw();
            } finally {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) shader.clear();
                VertexBuffer.unbind();
                part.material.clearRenderState();
            }
        }
        return true;
    }

    /** Match the current entity pass's light directions to normals kept in model space. */
    private static void uploadItemLighting(ShaderInstance shader, PoseStack pose) {
        if (shader.LIGHT0_DIRECTION == null && shader.LIGHT1_DIRECTION == null) return;
        RenderSystem.setupShaderLights(shader);
        Matrix3f inverseLinear = new Matrix3f(pose.last().pose());
        float determinant = inverseLinear.determinant();
        if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-10F) inverseLinear.invert();
        else inverseLinear.identity();
        uploadLocalLight(shader.LIGHT0_DIRECTION, inverseLinear);
        uploadLocalLight(shader.LIGHT1_DIRECTION, inverseLinear);
    }

    private static void uploadLocalLight(Uniform uniform, Matrix3f inverseLinear) {
        if (uniform == null) return;
        FloatBuffer values = uniform.getFloatBuffer();
        Vector3f light = new Vector3f(values.get(0), values.get(1), values.get(2))
                .mul(inverseLinear);
        if (light.lengthSquared() > 1.0E-12F) light.normalize();
        uniform.set(light);
        uniform.upload();
    }

    private static void retire(Entry entry, boolean invalidated) {
        if (entry.parts == null) return;
        long retiringGeneration = generation;
        for (Part part : entry.parts) release(part, retiringGeneration, invalidated);
        entry.parts = null;
        entry.bytes = 0;
    }

    private static void release(Part part, long retiringGeneration, boolean invalidated) {
        part.upload.whenComplete((ignored, failure) -> {
            Runnable action = () -> {
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) IDLE.evictExpired();
    }

    @SubscribeEvent
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

    /** Invalidate all item meshes on resource reload or render context loss. */
    public static void clear() {
        RenderSystem.assertOnRenderThread();
        generation++;
        IDLE.clear();
        for (Entry entry : CACHE.values()) {
            retire(entry, true);
        }
        CACHE.clear();
        POOL.clear();
        probedFormat = null;
        formatCheckCountdown = FORMAT_CHECK_DRAWS;
    }
}
