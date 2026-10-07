package com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.gpu;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.capture.MeshSink;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/** 探测渲染兼容钩子提供的实际 BufferBuilder 顶点布局。 */
public class MeshVertexFormat {
    public MeshVertexFormat() {}
    /** 用最小代价问一次"当前生效的顶点格式"：构造 1 个 quad 再丢弃，不碰 GL。 */
    public static VertexFormat probe() {
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

}
