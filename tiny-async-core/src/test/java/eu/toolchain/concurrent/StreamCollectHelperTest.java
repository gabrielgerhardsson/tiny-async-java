package eu.toolchain.concurrent;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

public class StreamCollectHelperTest {
  private Caller caller;
  private StreamCollector<Object, Object> collector;
  private Completable<Object> target;

  private final Object transformed = new Object();
  private final Object result = new Object();
  private final RuntimeException e = new RuntimeException();

  @SuppressWarnings("unchecked")
  @Before
  public void setup() {
    caller = mock(Caller.class);
    collector = mock(StreamCollector.class);
    target = mock(Completable.class);

    doAnswer(invocation -> {
      invocation.getArgumentAt(0, Runnable.class).run();
      return null;
    }).when(caller).execute(any(Runnable.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testZeroSize() {
    new StreamCollectHelper<Object, Object>(caller, 0, collector, target);
  }

  @Test
  public void testOneFailed() throws Exception {
    final StreamCollectHelper<Object, Object> helper =
        new StreamCollectHelper<Object, Object>(caller, 2, collector, target);

    when(collector.end(1, 1, 0)).thenReturn(transformed);

    helper.completed(result);
    verify(caller).execute(any(Runnable.class));
    verify(target, never()).complete(transformed);

    helper.failed(e);
    verify(collector).end(1, 1, 0);
    verify(collector).failed(e);
    verify(target).complete(transformed);
  }

  @Test
  public void testOneCancelled() throws Exception {
    final StreamCollectHelper<Object, Object> helper =
        new StreamCollectHelper<Object, Object>(caller, 2, collector, target);

    when(collector.end(1, 0, 1)).thenReturn(transformed);

    helper.completed(result);
    verify(caller).execute(any(Runnable.class));
    verify(target, never()).complete(transformed);

    helper.cancelled();
    verify(collector).end(1, 0, 1);
    verify(collector).cancelled();
    verify(target).complete(transformed);
  }

  @Test
  public void testAllResolved() throws Exception {
    final StreamCollectHelper<Object, Object> helper =
        new StreamCollectHelper<Object, Object>(caller, 2, collector, target);

    when(collector.end(2, 0, 0)).thenReturn(transformed);

    helper.completed(result);
    verify(caller).execute(any(Runnable.class));
    verify(target, never()).complete(transformed);

    helper.completed(result);
    verify(collector).end(2, 0, 0);
    verify(collector, times(2)).completed(result);
    verify(target).complete(transformed);
  }

  @Test
  public void testEndThrows() throws Exception {
    final StreamCollectHelper<Object, Object> helper =
        new StreamCollectHelper<Object, Object>(caller, 1, collector, target);

    when(collector.end(1, 0, 0)).thenThrow(e);

    helper.completed(result);
    verify(collector).completed(result);
    verify(target).fail(any(Exception.class));
  }
}
