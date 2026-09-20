package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.CompletableFuture;

/**
 * 库发出的缓冲句柄。实现方只需 retain / release，实际的上传、池化与回收由
 * {@link StaticWorldRenderer} 负责；释放必须经由本句柄，这就是"过期策略归实现方、释放动作必经库 API"的实现方式。
 */
@OnlyIn(Dist.CLIENT)
public final class ShardHandle {
    private final String ownerId;
    private final VertexBuffer buffer;
    private final VertexFormat format;
    private final ShardMeta meta;
    private final double submitDistanceSq;
    private final CompletableFuture<Void> upload;
    private final int vertexCount;
    private final int[] lightRunStarts;
    private final int[] lightRunLengths;
    private final int[] lightRunValues;

    private int packedLight;
    private int references = 1;
    private boolean retired;
    private boolean dead;
    private boolean recycled;

    ShardHandle(String ownerId, VertexBuffer buffer, VertexFormat format, int vertexCount, ShardMeta meta,
                double submitDistanceSq, CompletableFuture<Void> upload,
                int[] lightRunStarts, int[] lightRunLengths, int[] lightRunValues) {
        this.ownerId = ownerId;
        this.buffer = buffer;
        this.format = format;
        this.vertexCount = vertexCount;
        this.meta = meta;
        this.submitDistanceSq = submitDistanceSq;
        this.upload = upload;
        this.lightRunStarts = lightRunStarts;
        this.lightRunLengths = lightRunLengths;
        this.lightRunValues = lightRunValues;
        this.packedLight = meta.packedLight();
    }

    public ShardMeta meta() {
        return this.meta;
    }

    public double submitDistanceSq() {
        return this.submitDistanceSq;
    }

    /** 当前烘焙进顶点的光照（原地更新后会变，所以不能用 {@code meta().packedLight()}）。 */
    public int packedLight() {
        return this.packedLight;
    }

    /** 上传 future 完成即"已进入显存"，可用于 ready gate。 */
    public boolean isUploaded() {
        return this.upload.isDone();
    }

    /** 是否仍可用：既没有硬失效，也没有因引用归零被回收。 */
    public boolean isAlive() {
        return !this.dead && !this.retired;
    }

    public int references() {
        return this.references;
    }

    /** 同一个 mesh 被多个实例复用时调用。 */
    public void retain() {
        if (this.dead || this.retired) {
            throw new IllegalStateException("Cannot retain a released shard");
        }
        this.references++;
    }

    /** 声明不再使用；引用归零后库会在上传完成后回收缓冲。 */
    public void release() {
        StaticWorldRenderer.release(this);
    }

    String ownerId() {
        return this.ownerId;
    }

    VertexBuffer buffer() {
        return this.buffer;
    }

    VertexFormat format() {
        return this.format;
    }

    CompletableFuture<Void> upload() {
        return this.upload;
    }

    int vertexCount() {
        return this.vertexCount;
    }

    int lightRunCount() {
        return this.lightRunStarts.length;
    }

    int lightRunStart(int index) {
        return this.lightRunStarts[index];
    }

    int lightRunLength(int index) {
        return this.lightRunLengths[index];
    }

    int lightRunValue(int index) {
        return this.lightRunValues[index];
    }

    /** 原地改写成功后同步账本：把该光照值的运行段改成新值。 */
    void applyLightRewrite(int oldLight, int newLight) {
        for (int i = 0; i < this.lightRunValues.length; i++) {
            if (this.lightRunValues[i] == oldLight) {
                this.lightRunValues[i] = newLight;
            }
        }
        this.packedLight = newLight;
    }

    int releaseCount() {
        if (this.dead || this.retired) {
            return -1;
        }
        return --this.references;
    }

    void retire() {
        this.retired = true;
    }

    boolean isRetired() {
        return this.retired;
    }

    boolean markRecycled() {
        if (this.recycled) {
            return false;
        }
        this.recycled = true;
        return true;
    }

    void kill() {
        this.dead = true;
        this.retired = true;
        this.references = 0;
    }
}
