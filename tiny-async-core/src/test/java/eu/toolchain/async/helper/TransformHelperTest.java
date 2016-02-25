package eu.toolchain.async.helper;

import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.FutureDone;
import eu.toolchain.async.LazyTransform;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.Transform;
import eu.toolchain.async.TransformException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TransformHelperTest {
    private static final Object result = new Object();
    private static final Object transformed = new Object();

    private static final Throwable e = new Exception();

    private static final Throwable cause = new Exception();
    private static final Throwable transformedCause = new Exception();

    private Transform<Object, Object> transform;
    private Transform<Throwable, Object> errorTransform;
    private Transform<Void, Object> cancelledTransform;

    private LazyTransform<Object, Object> lazyTransform;
    private LazyTransform<Throwable, Object> lazyErrorTransform;
    private LazyTransform<Void, Object> lazyCancelledTransform;

    private ResolvableFuture<Object> target;
    private AsyncFuture<Object> f;

    private ResolvedTransformHelper<Object, Object> resolved;
    private FailedTransformHelper<Object> failed;
    private CancelledTransformHelper<Object> cancelled;

    private ResolvedLazyTransformHelper<Object, Object> lazyResolved;
    private FailedLazyTransformHelper<Object> lazyFailed;
    private CancelledLazyTransformHelper<Object> lazyCancelled;

    @SuppressWarnings("unchecked")
    @Before
    public void setup() throws Exception {
        transform = mock(Transform.class);
        errorTransform = mock(Transform.class);
        cancelledTransform = mock(Transform.class);

        lazyTransform = mock(LazyTransform.class);
        lazyErrorTransform = mock(LazyTransform.class);
        lazyCancelledTransform = mock(LazyTransform.class);

        target = mock(ResolvableFuture.class);
        f = mock(AsyncFuture.class);

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                final FutureDone<Object> done = (FutureDone<Object>) invocation.getArguments()[0];

                done.resolved(transformed);
                done.failed(transformedCause);
                done.cancelled();

                return null;
            }
        }).when(f).onDone(any(FutureDone.class));

        when(transform.transform(result)).thenReturn(transformed);
        when(errorTransform.transform(cause)).thenReturn(transformed);
        when(cancelledTransform.transform(null)).thenReturn(transformed);

        when(lazyTransform.transform(result)).thenReturn(f);
        when(lazyErrorTransform.transform(cause)).thenReturn(f);
        when(lazyCancelledTransform.transform(null)).thenReturn(f);

        resolved = new ResolvedTransformHelper<Object, Object>(transform, target);
        failed = new FailedTransformHelper<Object>(errorTransform, target);
        cancelled = new CancelledTransformHelper<Object>(cancelledTransform, target);

        lazyResolved = new ResolvedLazyTransformHelper<Object, Object>(lazyTransform, target);
        lazyFailed = new FailedLazyTransformHelper<Object>(lazyErrorTransform, target);
        lazyCancelled = new CancelledLazyTransformHelper<Object>(lazyCancelledTransform, target);
    }

    private void verifyTransform(int resolved, int failed, int cancelled) throws Exception {
        verify(transform, times(resolved)).transform(result);
        verify(errorTransform, times(failed)).transform(cause);
        verify(cancelledTransform, times(cancelled)).transform(null);
    }

    @Test
    public void testResolved() throws Exception {
        resolved.resolved(result);
        failed.resolved(result);
        cancelled.resolved(result);

        verifyTransform(1, 0, 0);
        verify(target, times(1)).resolve(transformed);
        verify(target, times(2)).resolve(result);
    }

    @Test
    public void testResolvedThrows() throws Exception {
        when(transform.transform(result)).thenThrow(e);
        resolved.resolved(result);

        verifyTransform(1, 0, 0);
        verify(target, times(1)).fail(any(TransformException.class));
    }

    @Test
    public void testFailed() throws Exception {
        resolved.failed(cause);
        failed.failed(cause);
        cancelled.failed(cause);

        verifyTransform(0, 1, 0);
        verify(target, times(1)).resolve(transformed);
        verify(target, times(2)).fail(cause);
    }

    @Test
    public void testFailedThrows() throws Exception {
        when(errorTransform.transform(cause)).thenThrow(e);
        failed.failed(cause);

        verifyTransform(0, 1, 0);
        verify(target, times(1)).fail(any(TransformException.class));
    }

    @Test
    public void testCancelled() throws Exception {
        resolved.cancelled();
        failed.cancelled();
        cancelled.cancelled();

        verifyTransform(0, 0, 1);
        verify(target, times(1)).resolve(transformed);
        verify(target, times(2)).cancel();
    }

    @Test
    public void testCancelledThrows() throws Exception {
        when(cancelledTransform.transform(null)).thenThrow(e);
        cancelled.cancelled();

        verifyTransform(0, 0, 1);
        verify(target, times(1)).fail(any(TransformException.class));
    }

    private void verifyLazyTransform(int resolved, int failed, int cancelled) throws Exception {
        verify(lazyTransform, times(resolved)).transform(result);
        verify(lazyErrorTransform, times(failed)).transform(cause);
        verify(lazyCancelledTransform, times(cancelled)).transform(null);
    }

    @Test
    public void testLazyResolved() throws Exception {
        lazyResolved.resolved(result);
        lazyFailed.resolved(result);
        lazyCancelled.resolved(result);

        verifyLazyTransform(1, 0, 0);

        verify(target, times(1)).resolve(transformed);
        verify(target, times(1)).fail(transformedCause);
        verify(target, times(2)).resolve(result);
        verify(target, times(1)).cancel();
    }

    @Test
    public void testLazyResolvedThrows() throws Exception {
        when(lazyTransform.transform(result)).thenThrow(e);
        lazyResolved.resolved(result);

        verifyLazyTransform(1, 0, 0);
        verify(target, times(1)).fail(any(TransformException.class));
    }

    @Test
    public void testLazyFailed() throws Exception {
        lazyResolved.failed(cause);
        lazyFailed.failed(cause);
        lazyCancelled.failed(cause);

        verifyLazyTransform(0, 1, 0);

        verify(target, times(1)).resolve(transformed);
        verify(target, times(1)).fail(transformedCause);
        verify(target, times(2)).fail(cause);
        verify(target, times(1)).cancel();
    }

    @Test
    public void testLazyFailedThrows() throws Exception {
        when(lazyErrorTransform.transform(cause)).thenThrow(e);
        lazyFailed.failed(cause);

        verifyLazyTransform(0, 1, 0);
        verify(target, times(1)).fail(any(TransformException.class));
    }

    @Test
    public void testLazyCancelled() throws Exception {
        lazyResolved.cancelled();
        lazyFailed.cancelled();
        lazyCancelled.cancelled();

        verifyLazyTransform(0, 0, 1);

        verify(target, times(1)).resolve(transformed);
        verify(target, times(1)).fail(transformedCause);
        verify(target, times(3)).cancel();
    }

    @Test
    public void testLazyCancelledThrows() throws Exception {
        when(lazyCancelledTransform.transform(null)).thenThrow(e);
        lazyCancelled.cancelled();

        verifyLazyTransform(0, 0, 1);
        verify(target, times(1)).fail(any(TransformException.class));
    }
}
