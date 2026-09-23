package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import net.minecraft.world.phys.Vec3;

/** 不修改业务对象时使用的访问回调；可由同一类型的所有对象共享。 */
public interface MeshRenderableAdapter<T> {
    default boolean isValid(T object) {
        return true;
    }

    default boolean needsUpdate(T object) {
        return true;
    }

    default boolean isVisible(T object) {
        return true;
    }

    Vec3 origin(T object);

    int packedLight(T object);

    Object geometryKey(T object);

    boolean collectGeometry(T object, GeometryCollector collector);
}
