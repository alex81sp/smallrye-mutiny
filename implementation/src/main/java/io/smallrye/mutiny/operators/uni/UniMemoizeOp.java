package io.smallrye.mutiny.operators.uni;

import static io.smallrye.mutiny.helpers.ParameterValidation.nonNull;
import static java.util.Collections.synchronizedList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.EmptyUniSubscription;
import io.smallrye.mutiny.operators.AbstractUni;
import io.smallrye.mutiny.operators.UniOperator;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

public class UniMemoizeOp<I> extends UniOperator<I, I> implements UniSubscriber<I> {

    private enum State {
        INIT,
        SUBSCRIBING,
        SUBSCRIBED,
        CACHING
    }

    private final BooleanSupplier invalidationRequested;

    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);

    private final AtomicInteger wip = new AtomicInteger();
    private final List<UniSubscriber<? super I>> awaitingSubscription = synchronizedList(new ArrayList<>());
    private final List<UniSubscriber<? super I>> awaitingResult = synchronizedList(new ArrayList<>());

    private volatile UniSubscription upstreamSubscription;
    private volatile I item;
    private volatile Throwable failure;

    public UniMemoizeOp(Uni<? extends I> upstream) {
        this(upstream, () -> false);
    }

    public UniMemoizeOp(Uni<? extends I> upstream, BooleanSupplier invalidationRequested) {
        super(nonNull(upstream, "upstream"));
        this.invalidationRequested = invalidationRequested;
    }

    @Override
    public void subscribe(UniSubscriber<? super I> subscriber) {
        if (invalidationRequested.getAsBoolean() && state.get() != State.SUBSCRIBING) {
            state.set(State.INIT);
            if (upstreamSubscription != null) {
                upstreamSubscription.cancel();
            }
        }

        // Early exit with cached data
        if (state.get() == State.CACHING) {
            subscriber.onSubscribe(EmptyUniSubscription.DONE);
            if (failure != null) {
                subscriber.onFailure(failure);
            } else {
                subscriber.onItem(item);
            }
            return;
        }

        awaitingSubscription.add(subscriber);
        if (state.compareAndSet(State.INIT, State.SUBSCRIBING)) {
            // This thread is performing the upstream subscription
            AbstractUni.subscribe(upstream(), this);
        }
    }

    @Override
    public void onSubscribe(UniSubscription subscription) {
        if (state.compareAndSet(State.SUBSCRIBING, State.SUBSCRIBED)) {
            upstreamSubscription = subscription;
            drain();
        }
    }

    private void drain() {
        // Check if another thread is working
        if (wip.getAndIncrement() != 0) {
            return;
        }

        // Big loop
        int missed = 1;
        for (;;) {

            ArrayList<UniSubscriber<? super I>> subscribers;
            I currentItem;
            Throwable currentFailure;

            if (!awaitingSubscription.isEmpty()) {
                // Make a safe copy of the subscribers awaiting for a subscription
                synchronized (awaitingSubscription) {
                    subscribers = new ArrayList<>(awaitingSubscription);
                }

                // Handle the subscribers that are awaiting a subscription
                for (UniSubscriber<? super I> subscriber : subscribers) {
                    currentItem = item;
                    currentFailure = failure;
                    State state = this.state.get();
                    switch (state) {
                        case INIT:
                        case SUBSCRIBING:
                            break;
                        case SUBSCRIBED:
                            subscriber.onSubscribe(() -> removeFromAwaitingLists(subscriber));
                            awaitingSubscription.remove(subscriber);
                            awaitingResult.add(subscriber);
                            break;
                        case CACHING:
                            subscriber.onSubscribe(() -> removeFromAwaitingLists(subscriber));
                            if (currentFailure != null) {
                                subscriber.onFailure(currentFailure);
                            } else {
                                subscriber.onItem(currentItem);
                            }
                            awaitingSubscription.remove(subscriber);
                            awaitingResult.remove(subscriber);
                            break;
                        default:
                            throw new IllegalStateException("Current state is " + state);
                    }
                }
            }

            if (!awaitingResult.isEmpty()) {
                // Make a safe copy of the subscribers awaiting a result
                synchronized (awaitingResult) {
                    subscribers = new ArrayList<>(awaitingResult);
                }
                // Handle the subscribers that are awaiting a result
                for (UniSubscriber<? super I> subscriber : subscribers) {
                    currentItem = item;
                    currentFailure = failure;
                    if (state.get() == State.CACHING) {
                        if (failure != null) {
                            subscriber.onFailure(currentFailure);
                        } else {
                            subscriber.onItem(currentItem);
                        }
                        awaitingResult.remove(subscriber);
                    }
                }
            }

            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    }

    private void removeFromAwaitingLists(UniSubscriber<? super I> subscriber) {
        awaitingSubscription.remove(subscriber);
        awaitingResult.remove(subscriber);
        drain();
    }

    @Override
    public void onItem(I item) {
        if (state.get() == State.SUBSCRIBED) {
            this.item = item;
            this.failure = null;
            state.set(State.CACHING);
            drain();
        }
    }

    @Override
    public void onFailure(Throwable failure) {
        if (state.get() == State.SUBSCRIBED) {
            this.item = null;
            this.failure = failure;
            state.set(State.CACHING);
            drain();
        }
    }
}
