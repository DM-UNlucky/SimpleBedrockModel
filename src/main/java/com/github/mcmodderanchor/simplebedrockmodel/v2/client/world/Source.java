package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 静态世界层的消费者（L4 策略实现方）。
 *
 * <p>库只负责 VBO 管理、上传提交与统一绘制；网格生产、脏标记语义、实例收集与过期淘汰都由实现方决定。
 * {@link #forEachShard(ShardSink)} 在绘制阶段被调用，实现方在这里枚举"我有什么"，不要做重活。</p>
 */
@OnlyIn(Dist.CLIENT)
public interface Source {
    enum Kind {
        /** 世界切换或卸载。 */
        WORLD,
        /** 资源重载。 */
        RESOURCES,
        /** 运行时被关闭或消费者注销。 */
        SHUTDOWN
    }

    @FunctionalInterface
    interface ShardSink {
        /**
         * @param handle      已提交的缓冲，可能被多个实例共享（去重）
         * @param origin      本实例的绘制原点（顶点数据以 {@code handle.meta().origin()} 为局部零点）
         * @param worldBounds 本实例的世界空间包围盒，供库做视锥剔除
         */
        void accept(ShardHandle handle, Vec3 origin, AABB worldBounds);
    }

    String id();

    /** 越小越先绘制；相同值按注册顺序。 */
    int stageOrder();

    void forEachShard(ShardSink out);

    void onInvalidate(Kind kind);
}
