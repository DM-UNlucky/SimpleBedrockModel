package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 逐实例绘制与共享几何换版；消费者无需持有句柄或引用计数。 */
final class InstanceMeshBatches<K> {
    private final GeometryCache cache;
    private int preparationCursor;
    private final Map<K, Resident> residents = new LinkedHashMap<>();
    private final class Resident {
        GeometryCache.Entry active;
        GeometryCache.Entry pending;
        Vec3 origin;
        int light;
    }
    InstanceMeshBatches(GeometryCache cache) { this.cache = cache; }

    void put(K id, Object geometryKey, Vec3 origin, int light) {
        Resident resident = this.residents.computeIfAbsent(id, ignored -> new Resident());
        resident.origin = origin;
        resident.light = light;
        GeometryCache.Entry current = resident.pending == null ? resident.active : resident.pending;
        if (current != null && current.key.equals(geometryKey)) return;
        GeometryCache.Entry next = this.cache.acquire(geometryKey);
        if (resident.pending != null) resident.pending.release();
        resident.pending = next;
    }

    void remove(K id) {
        Resident resident = this.residents.remove(id);
        if (resident == null) return;
        if (resident.active != null) resident.active.release();
        if (resident.pending != null) resident.pending.release();
    }

    void prepare(WorldMeshGroup<?> owner) {
        long start = System.nanoTime();
        Set<GeometryCache.Entry> waiting = new LinkedHashSet<>();
        for (Resident resident : this.residents.values()) {
            if (resident.pending != null && resident.pending.captured() && !resident.pending.queued()) waiting.add(resident.pending);
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
            if (resident.pending != null && resident.pending.ready()) {
                if (resident.active != null) resident.active.release();
                resident.active = resident.pending;
                resident.pending = null;
            }
        }
    }

    void collect(WorldMeshGroup.ShardSink out) {
        for (Resident resident : this.residents.values()) {
            if (resident.active == null) continue;
            for (ShardHandle handle : resident.active.handles()) {
                out.accept(handle, resident.origin, handle.localBounds().move(resident.origin), resident.light);
            }
        }
    }

    void clear() {
        for (Resident resident : this.residents.values()) {
            if (resident.active != null) resident.active.release();
            if (resident.pending != null) resident.pending.release();
        }
        this.residents.clear();
        this.preparationCursor = 0;
    }
}
