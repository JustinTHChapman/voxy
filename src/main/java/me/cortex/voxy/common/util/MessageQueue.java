package me.cortex.voxy.common.util;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class MessageQueue <T> {
    private final Consumer<T> consumer;
    private final ConcurrentLinkedDeque<T> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger count = new AtomicInteger(0);

    public MessageQueue(Consumer<T> consumer) {
        this.consumer = consumer;
    }

    public void push(T obj) {
        this.queue.add(obj);
        this.count.addAndGet(1);
    }

    public int consume() {
        return this.consume(Integer.MAX_VALUE);
    }

    public int consume(int max) {
        int i = 0;
        while (i < max) {
            var entry = this.queue.poll();
            if (entry == null) break;
            i++;
            this.consumer.accept(entry);
        }
        if (i != 0) {
            this.count.addAndGet(-i);
        }
        return i;
    }

    public final void clear(Consumer<T> cleaner) {
        while (!this.queue.isEmpty()) {
            cleaner.accept(this.queue.pop());
        }
    }

    public int count() {
        return this.count.get();
    }
}
