package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

/** 逐实例绘制与共享几何换版；消费者无需持有句柄或引用计数。 */
public class InstanceMeshBatches<K> {
    private final GeometryCache cache;
    private int preparationCursor;
    private final Map<K, Resident> residents = new LinkedHashMap<>();
    private static final class Resident {
        final MeshSwap<GeometryCache.Entry> meshes = new MeshSwap<>(GeometryCache.Entry::ready,
                GeometryCache.Entry::release);
        Vec3 origin;
        Matrix4f localTransform;
        int light;
        final Map<ShardHandle, AABB> worldBounds = new IdentityHashMap<>();
    }
    InstanceMeshBatches(GeometryCache cache) { this.cache = cache; }

    void put(K id, Object geometryKey, Vec3 origin, Matrix4f localTransform, int light) {
        Resident resident = this.residents.computeIfAbsent(id, ignored -> new Resident());
        if (!origin.equals(resident.origin) || !localTransform.equals(resident.localTransform))
            resident.worldBounds.clear();
        resident.origin = origin;
        resident.localTransform = localTransform;
        resident.light = light;
        GeometryCache.Entry active = resident.meshes.active();
        if (active != null && active.key.equals(geometryKey)) {
            resident.meshes.cancelPending();
            return;
        }
        GeometryCache.Entry current = resident.meshes.pending();
        if (current != null && current.key.equals(geometryKey)) return;
        GeometryCache.Entry next = this.cache.acquire(geometryKey);
        resident.meshes.request(next);
    }

    void remove(K id) {
        Resident resident = this.residents.remove(id);
        if (resident == null) return;
        resident.meshes.clear();
    }

    void prepare(WorldMeshGroup<?> owner) {
        long start = System.nanoTime();
        Set<GeometryCache.Entry> waiting = new LinkedHashSet<>();
        for (Resident resident : this.residents.values()) {
            GeometryCache.Entry pending = resident.meshes.pending();
            if (pending != null && pending.captured() && !pending.queued()) waiting.add(pending);
        }
        List<GeometryCache.Entry> jobs = new ArrayList<>(waiting);
        if (!jobs.isEmpty()) {
            int cursor = this.preparationCursor % jobs.size();
            int built = 0;
            while (built < jobs.size() && built < 4 && (built == 0 || System.nanoTime() - start < 2_000_000L)) {
                jobs.get(cursor).prepareGpu(owner);
                cursor = (cursor + 1) % jobs.size();
                built++;
            }
            this.preparationCursor = cursor;
        }
        // 就绪提升不受重建预算限制；缺失资源的重试轮转，避免阻塞其它模型。
        for (Resident resident : this.residents.values()) {
            if (resident.meshes.promote()) resident.worldBounds.clear();
        }
    }

    boolean ready(K id, Object geometryKey) {
        Resident resident = this.residents.get(id);
        GeometryCache.Entry active = resident == null ? null : resident.meshes.active();
        return active != null && active.key.equals(geometryKey)
                && active.ready() && !active.handles().isEmpty();
    }

    void collect(WorldMeshGroup.ShardSink out, Predicate<K> eligible) {
        for (Map.Entry<K, Resident> entry : this.residents.entrySet()) {
            if (!eligible.test(entry.getKey())) continue;
            Resident resident = entry.getValue();
            GeometryCache.Entry active = resident.meshes.active();
            if (active == null) continue;
            for (ShardHandle handle : active.handles()) {
                out.accept(handle, resident.origin, resident.localTransform,
                        resident.worldBounds.computeIfAbsent(handle, h -> WorldMeshTransforms.bounds(
                                h.localBounds(), resident.localTransform, resident.origin)), resident.light);
            }
        }
    }

    void clear() {
        for (Resident resident : this.residents.values()) {
            resident.meshes.clear();
        }
        this.residents.clear();
        this.preparationCursor = 0;
    }
}
