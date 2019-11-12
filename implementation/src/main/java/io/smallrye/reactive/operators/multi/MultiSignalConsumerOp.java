package io.smallrye.reactive.operators.multi;

import io.smallrye.reactive.CompositeException;
import io.smallrye.reactive.Multi;
import io.smallrye.reactive.helpers.Subscriptions;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Attach consumers to the various events and signals received by this {@link org.reactivestreams.Publisher}.
 * Consumer methods can be {@code null}
 *
 * @param <T> the value type
 */
public final class MultiSignalConsumerOp<T> extends AbstractMultiWithUpstream<T, T> {

    private final Consumer<? super Subscription> onSubscribe;

    private final Consumer<? super T> onItem;

    private final Consumer<? super Throwable> onFailure;

    private final Runnable onCompletion;

    private final Runnable onTermination;

    private final Runnable onCancellation;

    public MultiSignalConsumerOp(Multi<? extends T> upstream,
            Consumer<? super Subscription> onSubscribe,
            Consumer<? super T> onItem,
            Consumer<? super Throwable> onFailure,
            Runnable onCompletion,
            Runnable onTermination,
            Runnable onCancellation) {
        super(upstream);
        this.onSubscribe = onSubscribe;
        this.onItem = onItem;
        this.onFailure = onFailure;
        this.onCompletion = onCompletion;
        this.onTermination = onTermination;
        this.onCancellation = onCancellation;
    }

    @Override
    public void subscribe(Subscriber<? super T> actual) {
        upstream.subscribe(new SignalSubscriber(actual));
    }

    @SuppressWarnings("SubscriberImplementation")
    private final class SignalSubscriber implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> downstream;
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();

        SignalSubscriber(Subscriber<? super T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            subscription.get().request(n);
        }

        @Override
        public void cancel() {
            if (onCancellation != null) {
                try {
                    onCancellation.run();
                } catch (Throwable e) {
                    onError(e);
                    return;
                }
            }
            subscription.getAndSet(Subscriptions.CANCELLED).cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (subscription.compareAndSet(null, s)) {
                if (onSubscribe != null) {
                    try {
                        onSubscribe.accept(s);
                    } catch (Throwable e) {
                        Subscriptions.fail(downstream, e);
                        subscription.getAndSet(Subscriptions.CANCELLED).cancel();
                        return;
                    }
                }
                downstream.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (subscription.get() != Subscriptions.CANCELLED) {
                if (onItem != null) {
                    try {
                        onItem.accept(t);
                    } catch (Throwable e) {
                        onError(e);
                        return;
                    }
                }
                downstream.onNext(t);
            }

        }

        @Override
        public void onError(Throwable t) {
            if (subscription.getAndSet(Subscriptions.CANCELLED) != Subscriptions.CANCELLED) {
                if (onFailure != null) {
                    try {
                        onFailure.accept(t);
                    } catch (Throwable e) {
                        t = new CompositeException(t, e);
                    }
                }

                downstream.onError(t);

                if (onTermination != null) {
                    try {
                        onTermination.run();
                    } catch (Throwable e) {
                        // Nothing we can do...
                    }
                }
            }
        }

        @Override
        public void onComplete() {
            if (subscription.getAndSet(Subscriptions.CANCELLED) != Subscriptions.CANCELLED) {
                if (onCompletion != null) {
                    try {
                        onCompletion.run();
                    } catch (Throwable e) {
                        downstream.onError(e);
                        return;
                    }
                }

                downstream.onComplete();

                if (onTermination != null) {
                    try {
                        onTermination.run();
                    } catch (Throwable e) {
                        // Nothing we can do.
                    }
                }
            }
        }

    }

}