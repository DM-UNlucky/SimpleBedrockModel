package com.github.mcmodderanchor.simplebedrockmodel.v2.client.world;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Keeps the active ownership until every part of a replacement is uploaded. */
public class MeshSwap<T> {
    private final Predicate<T> ready;
    private final Consumer<T> release;
    private T active;
    private T pending;

    public MeshSwap(Predicate<T> ready, Consumer<T> release) {
        this.ready = ready;
        this.release = release;
    }

    public T active() { return active; }
    public T pending() { return pending; }

    public void request(T next) {
        cancelPending();
        pending = next;
    }

    public void cancelPending() {
        if (pending != null) release.accept(pending);
        pending = null;
    }

    public boolean promote() {
        if (pending == null || !ready.test(pending)) return false;
        if (active != null) release.accept(active);
        active = pending;
        pending = null;
        return true;
    }

    public void clear() {
        cancelPending();
        if (active != null) release.accept(active);
        active = null;
    }
}
