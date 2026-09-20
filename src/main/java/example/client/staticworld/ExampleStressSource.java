package example.client.staticworld;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.ShardHandle;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.Source;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.StaticWorldRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.TreeModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.tree.TreeBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.resource.BedrockModelResources;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.List;

/**
 * 压测挂具：在玩家前方生成一批"虚拟实例"（不依赖世界方块），用同一份数据在静态路径与动态路径之间切换。
 *
 * <p>四个模式用来分离变量：</p>
 * <ul>
 *   <li>{@code same}：同一模型 + 同一贴图 → 全部实例共享一个 VBO、一个材质组。</li>
 *   <li>{@code models}：不同模型 + 同一贴图 → 每实例一个 VBO，但仍是一个材质组。</li>
 *   <li>{@code all}：不同模型 + 不同贴图 → 每实例一个 VBO，且每个 RenderType 都要单独 setup/clearState。</li>
 *   <li>{@code dynamic}：同 {@code all}，但走原版/AR 的动态路径（每帧骨骼遍历 + MultiBufferSource）。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ExampleStressSource implements Source {
    public static final String ID = "example:stress";

    public enum Mode {
        SAME, MODELS, ALL, DYNAMIC
    }

    private static final ExampleStressSource INSTANCE = new ExampleStressSource();
    private static final StaticMeshCache MESHES = new StaticMeshCache();
    private static final List<Instance> INSTANCES = new ArrayList<>();

    private static boolean registered;
    private static boolean dynamicListening;
    private static Mode mode = Mode.SAME;
    private static int spawnFailures;

    private static final class Instance {
        TreeBedrockModel model;
        TreeModelInstance pose;
        ResourceLocation texture;
        List<StaticMeshCache.MeshKey> keys = List.of();
        Vec3 origin;
        AABB bounds;
        int light;
    }

    private ExampleStressSource() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        StaticWorldRenderer.register(INSTANCE);
    }

    public static Mode mode() {
        return mode;
    }

    public static int instanceCount() {
        return INSTANCES.size();
    }

    public static int spawnFailures() {
        return spawnFailures;
    }

    public static String stats() {
        return "stress{mode=" + mode + ", instances=" + INSTANCES.size() + ", meshes=" + MESHES.size()
                + ", bakes=" + MESHES.bakes() + ", failures=" + MESHES.failures()
                + ", spawnFailures=" + spawnFailures + "}";
    }

    /** 清空实例并释放缓存（缓冲会在上传完成后回到池里）。 */
    public static void clear() {
        for (Instance instance : INSTANCES) {
            if (!instance.keys.isEmpty()) {
                MESHES.releaseAll(instance.keys);
            }
        }
        INSTANCES.clear();
        MESHES.clear();
    }

    public static int spawn(Mode newMode, int count, Player player) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || player == null) {
            return 0;
        }
        clear();
        mode = newMode;
        spawnFailures = 0;
        if (newMode == Mode.DYNAMIC) {
            ensureDynamicListener();
        }

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        forward = forward.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        int columns = Mth.ceil(Math.sqrt(count));
        double spacing = 1.6;
        double distance = 7.0;
        Vec3 center = eye.add(forward.scale(distance));
        double baseY = Math.floor(eye.y) - 1.0;

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            int column = i % columns;
            int row = i / columns;
            double offset = (column - (columns - 1) / 2.0) * spacing;
            Vec3 origin = new Vec3(center.x + right.x * offset, baseY + row * spacing, center.z + right.z * offset);

            int modelIndex = newMode == Mode.SAME ? 0 : i % ExampleStressResources.COUNT;
            int textureIndex = newMode == Mode.ALL || newMode == Mode.DYNAMIC ? i % ExampleStressResources.COUNT : 0;
            ResourceLocation modelId = ExampleStressResources.model(modelIndex);
            ResourceLocation texture = ExampleStressResources.texture(textureIndex);
            TreeBedrockModel model = BedrockModelResources.getInstance().getTreeModel(modelId);
            if (model == null) {
                spawnFailures++;
                continue;
            }

            Instance instance = new Instance();
            instance.model = model;
            instance.texture = texture;
            instance.origin = origin;
            instance.light = LevelRenderer.getLightColor(level,
                    BlockPos.containing(origin.x, origin.y + 0.5, origin.z));

            if (newMode == Mode.DYNAMIC) {
                instance.pose = model.createInstance();
                instance.bounds = new AABB(origin.x - 1.0, origin.y - 1.0, origin.z - 1.0,
                        origin.x + 1.0, origin.y + 1.0, origin.z + 1.0);
            } else {
                instance.keys = MESHES.ensure(INSTANCE, model, modelId, texture, Direction.NORTH, instance.light);
                if (instance.keys.isEmpty()) {
                    spawnFailures++;
                    continue;
                }
                MESHES.retainAll(instance.keys);
                instance.bounds = MESHES.instanceBounds(instance.keys, origin);
            }
            INSTANCES.add(instance);
            spawned++;
        }
        return spawned;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int stageOrder() {
        return 10;
    }

    @Override
    public void forEachShard(ShardSink out) {
        if (mode == Mode.DYNAMIC || INSTANCES.isEmpty() || !StaticWorldRenderer.isEnabled()) {
            return;
        }
        for (Instance instance : INSTANCES) {
            for (StaticMeshCache.MeshKey key : instance.keys) {
                ShardHandle handle = MESHES.handle(key);
                if (handle != null) {
                    out.accept(handle, instance.origin, instance.bounds);
                }
            }
        }
    }

    @Override
    public void onInvalidate(Kind kind) {
        clear();
    }

    private static void ensureDynamicListener() {
        if (dynamicListening) {
            return;
        }
        dynamicListening = true;
        MinecraftForge.EVENT_BUS.addListener(ExampleStressSource::onRenderStage);
    }

    /** 动态对照组：同一批实例走原版路径（装了 AR 时就是 AR 路径），姿势与静态路径逐字对齐。 */
    private static void onRenderStage(RenderLevelStageEvent event) {
        if (mode != Mode.DYNAMIC || INSTANCES.isEmpty()) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();
        // 必须从事件里的 pose stack 起手：它含相机视图旋转，方块实体路径就是这么给顶点的
        // （BufferUploader 用的 ModelViewMat 是世界渲染期的单位阵）。少了这一步，顶点只有相机相对位移、
        // 没有视图旋转，几何会投到错误的屏幕位置。
        poseStack.mulPoseMatrix(event.getPoseStack().last().pose());
        for (Instance instance : INSTANCES) {
            poseStack.pushPose();
            poseStack.translate(instance.origin.x - camera.x + 0.5, instance.origin.y - camera.y,
                    instance.origin.z - camera.z + 0.5);
            instance.pose.resetPose();
            instance.model.renderToBuffer(instance.pose, poseStack, buffers,
                    RenderType.entityCutout(instance.texture),
                    BedrockModelRenderTypes.polyMeshCutout(instance.texture),
                    instance.light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        buffers.endBatch();
    }
}
