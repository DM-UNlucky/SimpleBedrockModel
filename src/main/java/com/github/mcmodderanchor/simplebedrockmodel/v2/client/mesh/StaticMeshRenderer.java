package com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.MeshGeometryProvider;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.cache.StaticMeshCache;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.cache.MeshCachePolicy;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu.MeshIntegerAttributes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.render.MeshBatchRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.render.MeshDrawTransform;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.debug.MeshRenderDebug;
import static com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.cache.StaticMeshCache.*;

import com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 临时共享静态网格的即时绘制入口，以及显式渲染阶段批次入口。 */
@OnlyIn(Dist.CLIENT)
public class StaticMeshRenderer {
    public enum BatchOrder {
        SUBMISSION,
        MATERIAL
    }

    public StaticMeshRenderer() {}

    /** 阶段所有者负责协调原版缓冲，并在改变阶段状态前关闭批次。 */
    public static StaticMeshBufferSource openBatch(ResourceLocation owner, BatchOrder order,
                                                   java.util.function.BooleanSupplier isCurrent) {
        RenderSystem.assertOnRenderThread();
        if (MeshRenderDebug.attributes != null) throw new IllegalStateException("Batch uses production attributes");
        return new StaticMeshBufferSource(Objects.requireNonNull(owner), Objects.requireNonNull(order),
                Objects.requireNonNull(isCurrent));
    }

    public static StaticMeshBufferSource openBatch(ResourceLocation owner, BatchOrder order) {
        return openBatch(owner, order, () -> true);
    }

    public static boolean supportsInstanceAttributes(int packedLight, int packedOverlay) {
        return StaticMeshCache.supportsInstanceAttributes(packedLight, packedOverlay);
    }

    public static void invalidateOwner(ResourceLocation owner) { StaticMeshCache.invalidateOwner(owner); }

    public static MeshDrawResult tryDraw(ResourceLocation owner, Object key, MeshGeometryProvider provider,
                                         PoseStack pose, int light, int overlay) {
        return tryDraw(owner, key, null, provider, pose, light, overlay, MeshCachePolicy.DEFAULT.idleRetention());
    }

    /** 在当前调用中准备网格，旧几何只查询；PENDING 接管本次调用，也包括部分绘制失败。 */
    public static MeshDrawResult tryDraw(ResourceLocation owner, Object key, @Nullable Object previousKey,
                                         MeshGeometryProvider provider, PoseStack pose, int light, int overlay,
                                         Duration retention) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(owner, "owner");
        if (!prepareRequest(key, provider, pose, light, overlay, retention)) return MeshDrawResult.UNSUPPORTED;
        Entry target = acquire(new CacheKey(owner, key));
        Entry previous = previousKey == null || previousKey.equals(key) ? null : CACHE.get(new CacheKey(owner, previousKey));
        if (previous != null) pin(previous);
        try {
            Preparation prepared = prepareTarget(target, provider);
            if (prepared == Preparation.READY) {
                // 开始绘制后不能尝试其他网格，因为可能已经提交了部分绘制。
                return draw(target, pose, light, overlay) ? MeshDrawResult.DRAWN : MeshDrawResult.PENDING;
            }
            if (previous != null && previous.ready() && layoutSupported(previous)
                    && (MeshRenderDebug.attributes != null || INTEGER_ATTRIBUTES.ensureReady())) {
                return draw(previous, pose, light, overlay) ? MeshDrawResult.DRAWN_PREVIOUS : MeshDrawResult.PENDING;
            }
            return prepared == Preparation.UNSUPPORTED ? MeshDrawResult.UNSUPPORTED : MeshDrawResult.PENDING;
        } finally {
            releaseEntry(target, retention);
            if (previous != null) releaseEntry(previous, retention);
        }
    }

    public static int attributeIndex(VertexFormat format, com.mojang.blaze3d.vertex.VertexFormatElement element) {
        List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = format.getElements();
        for (int i = 0; i < elements.size(); i++) if (elements.get(i) == element) return i;
        return -1;
    }

    public static boolean draw(Entry entry, PoseStack pose, int packedLight, int packedOverlay) {
        MeshRenderDebug.DrawAttributes attributes = MeshRenderDebug.attributes;
        try {
            if (attributes != null) {
                attributes.beginItem();
                try {
                    return drawParts(entry, pose, packedLight, packedOverlay, attributes);
                } finally {
                    attributes.endItem();
                }
            }
            int previous = INTEGER_ATTRIBUTES.beginItem();
            try {
                return drawParts(entry, pose, packedLight, packedOverlay, null);
            } finally {
                INTEGER_ATTRIBUTES.endItem(previous);
            }
        } catch (RuntimeException exception) {
            if (attributes != null) throw exception;
            // 清理已完成，后续调用可重试；本次失败调用不能再绘制其他网格。
            SimpleBedrockModel.LOGGER.warn("Failed to draw immediate static mesh {}", entry.key, exception);
            failEntry(entry);
            return false;
        }
    }

    public static boolean drawParts(Entry entry, PoseStack pose, int packedLight, int packedOverlay,
                                     @Nullable MeshRenderDebug.DrawAttributes attributes) {
        MeshDrawTransform transform = new MeshDrawTransform(RenderSystem.getModelViewMatrix(), pose.last().pose());
        long lightOffset = MeshIntegerAttributes.parameterOffset(packedLight);
        long overlayOffset = MeshIntegerAttributes.parameterOffset(packedOverlay);
        try (MeshBatchRenderer batch = new MeshBatchRenderer(RenderSystem.getProjectionMatrix())) {
            for (Part part : entry.parts) {
                if (!batch.beginMaterial(part.material, transform.modelView)) {
                    throw new IllegalStateException("No active shader for " + part.material);
                }
                batch.bind(part.buffer);
                if (attributes != null) {
                    part.attributes.invalidate();
                    VertexFormat format = part.buffer.getFormat();
                    attributes.setupPart(part.buffer, format, part.instanceLight,
                            attributeIndex(format, DefaultVertexFormat.ELEMENT_UV2),
                            attributeIndex(format, DefaultVertexFormat.ELEMENT_UV1), packedLight, packedOverlay);
                } else {
                    INTEGER_ATTRIBUTES.setupPart(part.attributes, part.instanceLight, lightOffset, overlayOffset);
                }
                batch.drawItem(transform);
                batch.finishMaterial();
            }
        }
        return true;
    }

}
