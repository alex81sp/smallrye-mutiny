package io.smallrye.mutiny.operators.uni;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.ParameterValidation;
import io.smallrye.mutiny.helpers.Subscriptions;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.operators.UniOperator;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

public class UniFailOnTimeout<I> extends UniOperator<I, I> {
    private final Duration timeout;
    private final Supplier<? extends Throwable> supplier;
    private final ScheduledExecutorService executor;

    public UniFailOnTimeout(Uni<I> upstream, Duration timeout, Supplier<? extends Throwable> supplier,
            ScheduledExecutorService executor) {
        super(upstream);
        this.timeout = timeout;
        this.supplier = supplier;
        this.executor = executor == null ? Infrastructure.getDefaultWorkerPool() : executor;
    }

    @Override
    public void subscribe(UniSubscriber<? super I> subscriber) {
        AtomicBoolean doneOrCancelled = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

        AbstractUni.subscribe(upstream(), new UniSubscriber<I>() {
            @Override
            public void onSubscribe(UniSubscription subscription) {
                // Configure the watch dog at subscription time.
                try {
                    task.set(executor.schedule(() -> {
                        if (doneOrCancelled.compareAndSet(false, true)) {
                            sendTimeout(subscriber, subscription);
                        }
                    }, timeout.toMillis(), TimeUnit.MILLISECONDS));
                } catch (RejectedExecutionException e) {
                    // Executor out of service.
                    subscriber.onSubscribe(Subscriptions.CANCELLED);
                    subscriber.onFailure(e);
                    return;
                }

                subscriber.onSubscribe(() -> {
                    if (doneOrCancelled.compareAndSet(false, true)) {
                        // cancelling
                        subscription.cancel();
                        ScheduledFuture<?> future = task.getAndSet(null);
                        if (future != null) {
                            future.cancel(false);
                        }
                    }
                });
            }

            @Override
            public void onItem(I item) {
                if (doneOrCancelled.compareAndSet(false, true)) {
                    ScheduledFuture<?> future = task.getAndSet(null);
                    if (future != null) {
                        future.cancel(false);
                    }
                    subscriber.onItem(item);
                }
            }

            @Override
            public void onFailure(Throwable failure) {
                if (doneOrCancelled.compareAndSet(false, true)) {
                    ScheduledFuture<?> future = task.getAndSet(null);
                    if (future != null) {
                        future.cancel(false);
                    }
                    subscriber.onFailure(failure);
                } else {
                    Infrastructure.handleDroppedException(failure);
                }
            }
        });
    }

    private void sendTimeout(UniSubscriber<? super I> subscriber, UniSubscription subscription) {

        // Cancel the upstream subscription
        subscription.cancel();

        Throwable throwable;
        try {
            throwable = supplier.get();
        } catch (Throwable e) {
            subscriber.onFailure(e);
            return;
        }
        if (throwable == null) {
            subscriber.onFailure(new NullPointerException(ParameterValidation.SUPPLIER_PRODUCED_NULL));
        } else {
            subscriber.onFailure(throwable);
        }
    }
}
