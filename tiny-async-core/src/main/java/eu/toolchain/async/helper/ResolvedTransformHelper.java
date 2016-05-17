package eu.toolchain.async.helper;

import eu.toolchain.async.FutureDone;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.Transform;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ResolvedTransformHelper<S, T> implements FutureDone<S> {
    private final Transform<? super S, ? extends T> transform;
    private final ResolvableFuture<T> target;

    @Override
    public void failed(Throwable cause) throws Exception {
        target.fail(cause);
    }

    @Override
    public void resolved(S result) throws Exception {
        final T value;

        try {
            value = transform.transform(result);
        } catch (Exception e) {
            target.fail(e);
            return;
        }

        target.resolve(value);
    }

    @Override
    public void cancelled() throws Exception {
        target.cancel();
    }
}
