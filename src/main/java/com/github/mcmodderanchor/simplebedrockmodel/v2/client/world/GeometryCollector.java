package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.resource.BedrockModelResources;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 库提供的一次性几何收集出口；同材质／拓扑的顶点自动合并，不接触 GPU。 */
public final class GeometryCollector {
    /** 捕获结果的材质／拓扑分组键，仅为内部数据。 */
    record Pass(RenderType material, VertexFormat.Mode mode) {
        Pass {
            Objects.requireNonNull(material, "material");
            if (mode != VertexFormat.Mode.QUADS && mode != VertexFormat.Mode.TRIANGLES) {
                throw new IllegalArgumentException("Unsupported geometry mode: " + mode);
            }
        }
    }
    private final Map<Pass, MeshSink> meshes = new LinkedHashMap<>();

    GeometryCollector() {}

    /** 普通顶点 UV2 写 0，固定自发光写非零值。返回的 consumer 只在本次收集回调中有效。 */
    public VertexConsumer buffer(RenderType material, VertexFormat.Mode mode) {
        return this.meshes.computeIfAbsent(new Pass(material, mode), pass -> new MeshSink().begin(mode));
    }

    private record BlockModelKey(ResourceLocation model, ResourceLocation texture, Direction facing) {}

    /** 与 blockModel 使用相同参数生成稳定的共享键，不包含世界位置和实例光照。 */
    public static Object blockModelKey(ResourceLocation modelId, ResourceLocation texture, Direction facing) {
        return new BlockModelKey(Objects.requireNonNull(modelId), Objects.requireNonNull(texture), Objects.requireNonNull(facing));
    }

    /** 便捷捕获 SBM 方块树模型：绑定姿势、局部平移 (0.5,0,0.5)、应用朝向，自动收集两种拓扑。 */
    public boolean blockModel(ResourceLocation modelId, ResourceLocation texture, Direction facing) {
        var model = BedrockModelResources.getInstance().getTreeModel(modelId);
        if (model == null) return false;
        PoseStack pose = new PoseStack();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        var instance = model.createInstance();
        instance.resetPose();
        model.renderBoneTree(instance, pose, buffer(RenderType.entityCutout(texture), VertexFormat.Mode.QUADS),
                0, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1, true);
        model.renderBoneTree(instance, pose, buffer(BedrockModelRenderTypes.polyMeshCutout(texture), VertexFormat.Mode.TRIANGLES),
                0, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1, false);
        return true;
    }

    Map<Pass, MeshSink> snapshot() {
        Map<Pass, MeshSink> result = new LinkedHashMap<>();
        this.meshes.forEach((pass, mesh) -> { if (!mesh.isEmpty()) result.put(pass, mesh); });
        return result;
    }
    void clear() { this.meshes.clear(); }
}
