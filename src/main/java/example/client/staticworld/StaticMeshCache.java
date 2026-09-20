package example.client.staticworld;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.ShardHandle;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.ShardMeta;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.Source;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.StaticWorldRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.MeshSink;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.TreeModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.tree.TreeBedrockModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 示例侧共享的静态网格缓存。全部属于 L4（实现方）：库不知道模型是什么、key 怎么定、什么时候淘汰。
 *
 * <p>结构分两层：<b>几何组</b>（模型 + 贴图 + 朝向 + 材质 + mode）下挂若干个<b>光照变体</b>，
 * 每个变体一个 VBO。光照变体按访问顺序做 LRU，超过 {@link #maxLightVariants} 就丢弃最旧的：
 * 优先丢"没有实例在用"的（只被缓存持有），实在都有人用才丢最久未访问的那个。</p>
 *
 * <p>淘汰只是归还<b>缓存自己</b>的那份引用：还有实例引用着的缓冲不会被释放，绘制不受影响；
 * 引用归零后库会在上传完成后把缓冲放回池里复用。所以"弃用最旧"的代价是下次同光照回来时重烘一次，
 * 换来的是光照变体数量有上界。</p>
 *
 * <p>"这一趟没有几何"只与模型和 mode 有关（与光照、贴图无关），因此单独用 {@link GeometryKey} 记，
 * 不随光照变化增长。</p>
 */
@OnlyIn(Dist.CLIENT)
final class StaticMeshCache {
    /** 每个几何组保留的光照变体上限（超出按 LRU 淘汰）。 */
    private static final int DEFAULT_MAX_LIGHT_VARIANTS = 4;

    /** 几何组：只有这些字段相同的 mesh 才能共享同一个缓冲。 */
    record VariantKey(ResourceLocation modelId, ResourceLocation texture, Direction facing,
                      RenderType material, VertexFormat.Mode mode) {
    }

    /** 实例持有的 key：几何组 + 该实例烘焙时的光照。 */
    record MeshKey(VariantKey variant, int packedLight) {
        ResourceLocation modelId() {
            return variant.modelId();
        }

        ResourceLocation texture() {
            return variant.texture();
        }

        Direction facing() {
            return variant.facing();
        }

        RenderType material() {
            return variant.material();
        }

        VertexFormat.Mode mode() {
            return variant.mode();
        }
    }

    /** "这一趟没有几何"只取决于模型与 mode。 */
    private record GeometryKey(ResourceLocation modelId, VertexFormat.Mode mode) {
    }

    private enum BakeResult {
        SUBMITTED,
        EMPTY,
        FAILED
    }

    private final Map<VariantKey, LinkedHashMap<Integer, ShardHandle>> variants = new HashMap<>();
    private final Set<GeometryKey> empty = new HashSet<>();
    /** 每个 key 当前被多少实例持有（不含缓存自己那一份引用）。 */
    private final Map<MeshKey, Integer> users = new HashMap<>();
    private final int maxLightVariants;

    private int bakes;
    private int failures;
    private int evictions;
    private int overflows;
    private boolean inPlaceLightUpdate = true;

    StaticMeshCache() {
        this(DEFAULT_MAX_LIGHT_VARIANTS);
    }

    StaticMeshCache(int maxLightVariants) {
        this.maxLightVariants = Math.max(1, maxLightVariants);
    }

    /** 确保该外观的两趟（quads / triangles）都已烘焙，返回可用的 key 列表。 */
    List<MeshKey> ensure(Source owner, TreeBedrockModel model, ResourceLocation modelId, ResourceLocation texture,
                         Direction facing, int packedLight) {
        List<MeshKey> keys = new ArrayList<>(2);
        for (int pass = 0; pass < 2; pass++) {
            boolean quads = pass == 0;
            VariantKey variant = new VariantKey(modelId, texture, facing,
                    quads ? RenderType.entityCutout(texture) : BedrockModelRenderTypes.polyMeshCutout(texture),
                    quads ? VertexFormat.Mode.QUADS : VertexFormat.Mode.TRIANGLES);
            MeshKey key = new MeshKey(variant, packedLight);
            GeometryKey geometry = new GeometryKey(modelId, variant.mode());
            if (handle(key) == null && !this.empty.contains(geometry)) {
                if (bake(owner, model, key) == BakeResult.EMPTY) {
                    this.empty.add(geometry);
                }
            }
            if (handle(key) != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 缓存里可能残留已被回收的句柄，命中时顺手清掉；命中同时刷新 LRU 顺序。 */
    ShardHandle handle(MeshKey key) {
        LinkedHashMap<Integer, ShardHandle> bucket = this.variants.get(key.variant());
        if (bucket == null) {
            return null;
        }
        ShardHandle handle = bucket.get(key.packedLight());
        if (handle != null && !handle.isAlive()) {
            bucket.remove(key.packedLight());
            return null;
        }
        return handle;
    }

    /** 这一组 mesh 是否都已进入显存（用于"新烘的还没好就先画旧的"）。 */
    boolean uploaded(List<MeshKey> keys) {
        for (MeshKey key : keys) {
            ShardHandle handle = handle(key);
            if (handle == null || !handle.isUploaded()) {
                return false;
            }
        }
        return !keys.isEmpty();
    }

    void retainAll(List<MeshKey> keys) {
        for (MeshKey key : keys) {
            ShardHandle handle = handle(key);
            if (handle != null) {
                handle.retain();
                this.users.merge(key, 1, Integer::sum);
            }
        }
    }

    void releaseAll(List<MeshKey> keys) {
        for (MeshKey key : keys) {
            ShardHandle handle = handle(key);
            if (handle != null) {
                handle.release();
                this.users.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
            }
        }
    }

    int userCount(MeshKey key) {
        return this.users.getOrDefault(key, 0);
    }

    /**
     * 试着用"原地只改光照"代替重烘，成功时返回新的 key 列表（句柄不变，只是换了光照值）。
     *
     * <p>前提：每个 key 的目标光照都还没被缓存，且该变体<b>只有这一个用户</b>——否则改写会影响到别的实例。
     * 任何一条不满足就返回 {@code null}，交给常规的缓存/重烘路径。</p>
     */
    List<MeshKey> relight(List<MeshKey> keys, int newLight) {
        if (!this.inPlaceLightUpdate || keys.isEmpty()) {
            return null;
        }
        for (MeshKey key : keys) {
            if (key.packedLight() == newLight) {
                continue;
            }
            ShardHandle handle = handle(key);
            // 用句柄的真实引用数判断"是否只有我一个用户"：缓存自己占 1 份，所以单用户是 2。
            // 不能用 users 计数——异常路径上它可能少记，从而把别人还在用的 VBO 改写掉。
            if (handle == null || !handle.isAlive() || !handle.isUploaded() || handle.references() > 2) {
                return null;
            }
            if (handle(new MeshKey(key.variant(), newLight)) != null) {
                return null;
            }
        }

        List<MeshKey> result = new ArrayList<>(keys.size());
        for (MeshKey key : keys) {
            if (key.packedLight() == newLight) {
                result.add(key);
                continue;
            }
            ShardHandle handle = handle(key);
            if (!StaticWorldRenderer.rewriteLight(handle, key.packedLight(), newLight)) {
                return null;
            }
            LinkedHashMap<Integer, ShardHandle> bucket = this.variants.get(key.variant());
            if (bucket == null) {
                return null;
            }
            bucket.remove(key.packedLight());
            bucket.put(newLight, handle);
            MeshKey target = new MeshKey(key.variant(), newLight);
            Integer count = this.users.remove(key);
            if (count != null) {
                this.users.put(target, count);
            }
            result.add(target);
        }
        return result;
    }

    /** 把共享 mesh 的烘焙包围盒搬到实例位置，得到实例级包围盒。 */
    AABB instanceBounds(List<MeshKey> keys, Vec3 origin) {
        AABB bounds = null;
        for (MeshKey key : keys) {
            ShardHandle handle = handle(key);
            if (handle == null) {
                continue;
            }
            Vec3 bakeOrigin = handle.meta().origin();
            AABB moved = handle.meta().bounds().move(
                    origin.x - bakeOrigin.x, origin.y - bakeOrigin.y, origin.z - bakeOrigin.z);
            bounds = bounds == null ? moved : bounds.minmax(moved);
        }
        return bounds;
    }

    int size() {
        int count = 0;
        for (LinkedHashMap<Integer, ShardHandle> bucket : this.variants.values()) {
            count += bucket.size();
        }
        return count;
    }

    int bakes() {
        return this.bakes;
    }

    int failures() {
        return this.failures;
    }

    int evictions() {
        return this.evictions;
    }

    /** 关掉"原地只改光照"，用于隔离它是否引入渲染异常。 */
    void setInPlaceLightUpdate(boolean value) {
        this.inPlaceLightUpdate = value;
    }

    boolean inPlaceLightUpdate() {
        return this.inPlaceLightUpdate;
    }

    /** 因为"其余变体都在用"而放弃淘汰的次数；持续增长说明活跃工作集大于 K。 */
    int overflows() {
        return this.overflows;
    }

    /** 释放缓存持有的全部句柄（缓冲会在上传完成后回到池里）。 */
    void clear() {
        for (LinkedHashMap<Integer, ShardHandle> bucket : this.variants.values()) {
            for (ShardHandle handle : bucket.values()) {
                if (handle.isAlive()) {
                    handle.release();
                }
            }
        }
        this.variants.clear();
        this.empty.clear();
        this.users.clear();
    }

    private BakeResult bake(Source owner, TreeBedrockModel model, MeshKey key) {
        boolean quads = key.mode() == VertexFormat.Mode.QUADS;
        PoseStack pose = new PoseStack();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-key.facing().toYRot()));

        TreeModelInstance instance = model.createInstance();
        instance.resetPose();

        MeshSink sink = new MeshSink().begin(key.mode());
        model.renderBoneTree(instance, pose, sink, key.packedLight(), OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F, quads);
        if (sink.isEmpty()) {
            return BakeResult.EMPTY;
        }

        AABB bounds = sink.bounds();
        ShardHandle handle = StaticWorldRenderer.submit(owner, sink,
                new ShardMeta(key.material(), bounds, Vec3.ZERO, key.packedLight()), 0.0);
        if (handle == null) {
            this.failures++;
            return BakeResult.FAILED;
        }

        LinkedHashMap<Integer, ShardHandle> bucket = this.variants.computeIfAbsent(key.variant(),
                ignored -> new LinkedHashMap<>(8, 0.75F, true));
        bucket.put(key.packedLight(), handle);
        this.bakes++;
        trim(key.variant(), bucket, key.packedLight());
        return BakeResult.SUBMITTED;
    }

    /**
     * 光照变体超上限时按 LRU 淘汰，但只淘汰"确实没人用"的（只剩缓存这一份引用）。
     *
     * <p>两条约束：刚烘出来的变体不能被自己挤掉（trim 时它的引用数还是 1，实例的 retain 要等调用方稍后做，
     * 一旦被选中就会"烘完即被回收"，`ensure` 拿到空列表、当帧退回动态路径，于是每帧重烘 + 画面闪烁）；
     * 其余变体都在用时宁可超上限也不淘汰——淘汰在用的变体只是归还缓存那一份引用，缓冲还被实例握着，
     * 内存一点没省，却让下次查询必然 miss 再重烘。上限的职责是清理过期变体，不是和活跃工作集对抗。</p>
     */
    private void trim(VariantKey variant, LinkedHashMap<Integer, ShardHandle> bucket, int keepLight) {
        while (bucket.size() > this.maxLightVariants) {
            Integer victimLight = null;
            ShardHandle victim = null;
            for (Map.Entry<Integer, ShardHandle> entry : bucket.entrySet()) {
                if (entry.getKey() == keepLight) {
                    continue;
                }
                ShardHandle candidate = entry.getValue();
                if (!candidate.isAlive() || candidate.references() <= 1) {
                    victimLight = entry.getKey();
                    victim = candidate;
                    break;
                }
            }
            if (victim == null) {
                // 其余变体都在用：超一点比抖动划算。
                this.overflows++;
                break;
            }
            bucket.remove(victimLight);
            if (victim.isAlive()) {
                victim.release();
            }
            this.evictions++;
        }
        if (bucket.isEmpty()) {
            this.variants.remove(variant);
        }
    }
}
