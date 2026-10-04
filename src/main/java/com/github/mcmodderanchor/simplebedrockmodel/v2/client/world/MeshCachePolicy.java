package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import java.time.Duration;
import java.util.Objects;

/** Retention and LRU limits for unreferenced geometry. Referenced meshes are never evicted. */
public record MeshCachePolicy(Duration idleRetention, int maxIdleEntries, long maxIdleBytes) {
    public static final MeshCachePolicy DEFAULT = new MeshCachePolicy(Duration.ofSeconds(30), 128,
            256L * 1024L * 1024L);

    public MeshCachePolicy {
        Objects.requireNonNull(idleRetention, "idleRetention");
        if (idleRetention.isNegative() || maxIdleEntries < 0 || maxIdleBytes < 0)
            throw new IllegalArgumentException("Negative mesh cache limit");
        idleRetention.toNanos(); // Reject durations that cannot be represented by the monotonic clock.
    }
}
