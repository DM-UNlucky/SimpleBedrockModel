package example.client.staticworld;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.ShardHandle;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.Source;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.StaticWorldRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.tree.TreeBedrockModel;
import example.block.blockentity.TestBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 原型用 Source：把示例方块实体（TEST / POLY_MESH_TEST）烘成静态几何。
 *
 * <p>这里刻意把「库不管的部分」都放在本类：实例收集、过期淘汰（128 格）、方块实体存在性校验、
 * 朝向与纹理选择、以及<b>光照变化时的换挡策略</b>；网格生产与去重缓存交给 {@link StaticMeshCache}。</p>
 *
 * <p>光照换挡：新光照烘出来之后先挂在 {@code pending}，等它上传完成（下一帧）才提升为 {@code active} 并释放旧的。
 * 这样光照变化的瞬间不会出现"方块消失一帧"，代价是最多同时持有两版 mesh。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ExampleStaticSource implements Source {
    public static final String ID = "example:test_block";

    /** 过期策略：超出这个距离的实例会被淘汰（实现方决定）。 */
    private static final double RESIDENT_RANGE = 128.0;
    private static final double RESIDENT_RANGE_SQR = RESIDENT_RANGE * RESIDENT_RANGE;

    private static final ExampleStaticSource INSTANCE = new ExampleStaticSource();
    private static final StaticMeshCache MESHES = new StaticMeshCache();
    private static final Map<BlockPos, Resident> RESIDENTS = new HashMap<>();

    private static boolean registered;
    private static int expired;

    /** 实例级状态：正在画的（active）与正在等上传的（pending），各自带世界包围盒。 */
    private static final class Resident {
        List<StaticMeshCache.MeshKey> active;
        AABB activeBounds;
        List<StaticMeshCache.MeshKey> pending;
        AABB pendingBounds;

        List<StaticMeshCache.MeshKey> current() {
            return this.pending != null ? this.pending : this.active;
        }

        void replaceCurrent(List<StaticMeshCache.MeshKey> keys) {
            if (this.pending != null) {
                this.pending = keys;
            } else {
                this.active = keys;
            }
        }
    }

    private ExampleStaticSource() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        StaticWorldRenderer.register(INSTANCE);
    }

    public static boolean active() {
        return registered && StaticWorldRenderer.isEnabled() && Minecraft.getInstance().level != null;
    }

    public static int residentCount() {
        return RESIDENTS.size();
    }

    public static int meshCount() {
        return MESHES.size();
    }

    public static int bakes() {
        return MESHES.bakes();
    }

    public static int bakeFailures() {
        return MESHES.failures();
    }

    public static int expired() {
        return expired;
    }

    public static int evictions() {
        return MESHES.evictions();
    }

    public static int overflows() {
        return MESHES.overflows();
    }

    /** 关掉"原地只改光照"（用于隔离渲染异常）。 */
    public static void setInPlaceLightUpdate(boolean value) {
        MESHES.setInPlaceLightUpdate(value);
    }

    public static boolean inPlaceLightUpdate() {
        return MESHES.inPlaceLightUpdate();
    }

    public static String stats() {
        return "example{residents=" + RESIDENTS.size() + ", meshes=" + MESHES.size()
                + ", bakes=" + MESHES.bakes() + ", failures=" + MESHES.failures()
                + ", expired=" + expired + ", evictions=" + MESHES.evictions()
                + ", overflows=" + MESHES.overflows()
                + ", relight=" + (MESHES.inPlaceLightUpdate() ? "on" : "off") + "}";
    }

    /**
     * 由示例方块实体渲染器调用。返回 true 表示静态路径已接管该实例，调用方不应再走动态渲染。
     * 必须在渲染线程调用（烘焙会捕获顶点数据）。
     */
    public static boolean tryEnqueue(TestBlockEntity blockEntity, int packedLight, TreeBedrockModel model,
                                     ResourceLocation modelId, ResourceLocation texture) {
        if (!active() || model == null || blockEntity.getLevel() == null) {
            return false;
        }
        Direction facing = facingOf(blockEntity.getBlockState());

        BlockPos pos = blockEntity.getBlockPos().immutable();
        Resident resident = RESIDENTS.get(pos);
        // 光照变化优先走"原地只改光照"：句柄不变、几何不重烘、当帧就能用，连换挡都不需要。
        if (resident != null) {
            List<StaticMeshCache.MeshKey> current = resident.current();
            if (current != null && !current.isEmpty() && current.get(0).packedLight() != packedLight) {
                List<StaticMeshCache.MeshKey> relit = MESHES.relight(current, packedLight);
                if (relit != null) {
                    resident.replaceCurrent(relit);
                    return true;
                }
            }
        }

        List<StaticMeshCache.MeshKey> keys = MESHES.ensure(INSTANCE, model, modelId, texture, facing, packedLight);
        if (keys.isEmpty()) {
            return false;
        }

        if (resident != null && keys.equals(resident.current())) {
            return true;
        }
        if (resident == null) {
            resident = new Resident();
            RESIDENTS.put(pos, resident);
        }
        // 先 retain 新的；旧的那一版在提升或替换时释放。
        MESHES.retainAll(keys);
        if (resident.pending != null) {
            MESHES.releaseAll(resident.pending);
        }
        resident.pending = keys;
        resident.pendingBounds = MESHES.instanceBounds(keys, Vec3.atLowerCornerOf(pos));
        return true;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int stageOrder() {
        return 0;
    }

    @Override
    public void forEachShard(ShardSink out) {
        if (!StaticWorldRenderer.isEnabled() || RESIDENTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) {
            return;
        }
        Vec3 eye = minecraft.player.getEyePosition();

        Iterator<Map.Entry<BlockPos, Resident>> iterator = RESIDENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Resident> entry = iterator.next();
            BlockPos pos = entry.getKey();
            Resident resident = entry.getValue();
            // getBlockEntity 在区块未加载时返回 null，因此不需要额外的 hasChunkAt 检查。
            if (pos.distToCenterSqr(eye) > RESIDENT_RANGE_SQR
                    || !(world.getBlockEntity(pos) instanceof TestBlockEntity)) {
                releaseResident(resident);
                iterator.remove();
                expired++;
                continue;
            }

            // 新版上传完成后才换挡：期间继续画旧版，避免光照变化时掉帧级闪烁。
            if (resident.pending != null && MESHES.uploaded(resident.pending)) {
                if (resident.active != null) {
                    MESHES.releaseAll(resident.active);
                }
                resident.active = resident.pending;
                resident.activeBounds = resident.pendingBounds;
                resident.pending = null;
                resident.pendingBounds = null;
            }
            List<StaticMeshCache.MeshKey> keys = resident.current();
            AABB bounds = resident.active != null ? resident.activeBounds : resident.pendingBounds;
            if (keys == null || bounds == null) {
                continue;
            }
            Vec3 origin = Vec3.atLowerCornerOf(pos);
            for (StaticMeshCache.MeshKey key : keys) {
                ShardHandle handle = MESHES.handle(key);
                if (handle != null) {
                    out.accept(handle, origin, bounds);
                }
            }
        }
    }

    @Override
    public void onInvalidate(Kind kind) {
        // 硬失效时库已经丢弃并待回收这些句柄；缓存里的引用会由 clear() 归还。
        RESIDENTS.clear();
        MESHES.clear();
    }

    private static void releaseResident(Resident resident) {
        if (resident.pending != null) {
            MESHES.releaseAll(resident.pending);
            resident.pending = null;
        }
        if (resident.active != null) {
            MESHES.releaseAll(resident.active);
            resident.active = null;
        }
    }

    private static Direction facingOf(BlockState state) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
    }
}
