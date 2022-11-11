package me.geales.spring.trace.logging;

import java.util.function.BiFunction;

import org.reactivestreams.Subscription;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.util.context.Context;

public class ObservationContextSnapshotLifter<T> implements CoreSubscriber<T> {
    public static <T> BiFunction<Scannable, CoreSubscriber<? super T>, CoreSubscriber<? super T>> lifter() {
        return (scannable, coreSubscriber) -> new ObservationContextSnapshotLifter<>(coreSubscriber);
    }

    private final CoreSubscriber<? super T> delegate;

    private ObservationContextSnapshotLifter(CoreSubscriber<? super T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Context currentContext() {
        return delegate.currentContext();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(T t) {
        if (isObservationThreadLocalAvailableAndUnset()) {
            try (ContextSnapshot.Scope scope = ContextSnapshot.setThreadLocalsFrom(currentContext(), ObservationThreadLocalAccessor.KEY)) {
                delegate.onNext(t);
            }
        } else {
            delegate.onNext(t);
        }
    }

    @Override
    public void onError(Throwable t) {
        delegate.onError(t);
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }

    private boolean isObservationThreadLocalAvailableAndUnset() {
        for (ThreadLocalAccessor<?> threadLocalAccessor : ContextRegistry.getInstance().getThreadLocalAccessors()) {
            if (ObservationThreadLocalAccessor.KEY.equals(threadLocalAccessor.key()) && threadLocalAccessor.getValue() == null) {
                return true;
            }
        }
        return false;
    }
}


