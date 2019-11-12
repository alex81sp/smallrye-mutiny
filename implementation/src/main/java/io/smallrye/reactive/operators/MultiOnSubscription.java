package io.smallrye.reactive.operators;

import io.smallrye.reactive.Multi;
import io.smallrye.reactive.operators.multi.MultiSignalConsumerOp;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import java.util.function.Consumer;

import static io.smallrye.reactive.helpers.ParameterValidation.nonNull;

public class MultiOnSubscription<T> extends MultiOperator<T, T> {
    private final Consumer<? super Subscription> consumer;

    public MultiOnSubscription(Multi<T> upstream, Consumer<? super Subscription> consumer) {
        super(nonNull(upstream, "upstream"));
        this.consumer = nonNull(consumer, "consumer");
    }

    @Override
    protected Publisher<T> publisher() {
        return new MultiSignalConsumerOp<>(
                upstream(),
                consumer,
                null,
                null,
                null,
                null,
                null
        );
    }
}
