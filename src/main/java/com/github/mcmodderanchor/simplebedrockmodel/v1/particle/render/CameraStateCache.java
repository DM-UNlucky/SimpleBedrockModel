package com.github.mcmodderanchor.simplebedrockmodel.v1.particle.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class CameraStateCache {

    private static float cameraRoll;
    private static float worldFov = 70.0f;
    private static float handFov = 70.0f;

    private CameraStateCache() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        cameraRoll = event.getRoll();
    }

    /**
     * 缓存本帧最终使用的世界/手部投影 FOV。手部模型可使用独立 FOV，第一人称
     * 粒子从手部空间转换到世界空间时必须补偿二者的深度比例。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov()) {
            worldFov = (float) event.getFOV();
        } else {
            handFov = (float) event.getFOV();
        }
    }

    /**
     * 获取当前帧的 camera roll（度）。
     */
    public static float getCameraRollDegrees() {
        return cameraRoll;
    }

    /**
     * 获取当前帧的 camera roll（弧度）。
     */
    public static float getCameraRollRadians() {
        return (float) Math.toRadians(cameraRoll);
    }

    /**
     * 返回从手部投影空间映射到世界投影空间所需的视图 Z 轴缩放。
     */
    public static float getFirstPersonToWorldDepthScale() {
        if (handFov <= 0.0f || worldFov <= 0.0f) {
            return 1.0f;
        }
        double handTan = Math.tan(Math.toRadians(handFov) / 2.0);
        double worldTan = Math.tan(Math.toRadians(worldFov) / 2.0);
        if (Math.abs(worldTan) < 1.0e-6) {
            return 1.0f;
        }
        return (float) (handTan / worldTan);
    }
}
