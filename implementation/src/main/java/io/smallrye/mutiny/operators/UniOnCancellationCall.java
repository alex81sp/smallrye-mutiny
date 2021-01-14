package io.smallrye.mutiny.operators;

import static io.smallrye.mutiny.helpers.ParameterValidation.nonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

public class UniOnCancellationCall<I> extends UniOperator<I, I> {

    private final Supplier<Uni<?>> supplier;

    public UniOnCancellationCall(Uni<? extends I> upstream, Supplier<Uni<?>> supplier) {
        super(nonNull(upstream, "upstream"));
        this.supplier = nonNull(supplier, "supplier");
    }

    @Override
    protected void subscribing(UniSubscriber<? super I> subscriber) {
        upstream().subscribe().withSubscriber(new UniDelegatingSubscriber<I, I>(subscriber) {

            @Override
            public void onSubscribe(UniSubscription subscription) {
                subscriber.onSubscribe(new UniSubscription() {

                    private final AtomicBoolean called = new AtomicBoolean();

                    @Override
                    public void cancel() {
                        if (called.compareAndSet(false, true)) {
                            execute().subscribe().with(
                                    ignoredItem -> subscription.cancel(),
                                    ignoredException -> {
                                        Infrastructure.handleDroppedException(ignoredException);
                                        subscription.cancel();
                                    });
                        }
                    }

                    private Uni<?> execute() {
                        try {
                            return nonNull(supplier.get(), "uni");
                        } catch (Throwable err) {
                            return Uni.createFrom().failure(err);
                        }
                    }
                });
            }
        });
    }
}
