package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 一次提交的元数据：材质、烘焙包围盒、烘焙原点与烘焙进顶点的光照。
 *
 * <p>{@code origin} 是<b>烘焙时刻</b>的平移基准，顶点数据以它为局部零点。去重后同一个缓冲会被多个实例复用，
 * 所以实例的绘制原点与世界包围盒必须由 {@link Source#forEachShard} 逐实例提供，不能用这里的值。</p>
 */
@OnlyIn(Dist.CLIENT)
public record ShardMeta(RenderType material, AABB bounds, Vec3 origin, int packedLight) {
}
