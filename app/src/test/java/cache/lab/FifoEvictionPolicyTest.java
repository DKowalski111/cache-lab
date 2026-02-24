package cache.lab;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.Test;

import cache.lab.contract.EvictionPolicy;
import cache.lab.impl.DefaultCache;
import cache.lab.impl.DefaultTicker;
import cache.lab.impl.FifoEvictionPolicy;

public class FifoEvictionPolicyTest
{
	final AtomicInteger loaderCounter = new AtomicInteger();
	private final int maxSize = 5;

	private final CacheConfig cacheConfig = new CacheConfig(
			Duration.ofSeconds(30),
			Duration.ofSeconds(30),
			maxSize,
			DefaultTicker.INSTANCE,
			Executors.newSingleThreadExecutor(),
			8
	);

	@Test
	public void cacheShouldNotExceedItsMaximumSize() throws Exception
	{
		loaderCounter.set(0);

		EvictionPolicy<Integer> evictionPolicy = new FifoEvictionPolicy<>();
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfig, evictionPolicy);

		int iterations = 20;

		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () -> cache.get(i, k -> {
														 loaderCounter.incrementAndGet();
														 return i;
													 }))
													 .toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			for (Future<Integer> f : futures)
			{
				assertNotNull(f.get());
			}
		}

		assertEquals(iterations, loaderCounter.get());

		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		long size;
		do
		{
			size = cache.size();
			if (size <= maxSize)
			{
				break;
			}
			Thread.onSpinWait();
		}
		while (System.nanoTime() < deadline);

		assertTrue(size <= maxSize, "cache.size()=" + size);
	}

	@Test
	public void policyShouldEvictInInsertionOrder()
	{
		FifoEvictionPolicy<Integer> p = new FifoEvictionPolicy<>();

		p.onPut(1);
		p.onPut(2);
		p.onPut(3);

		assertEquals(1, p.evict(-1));
		p.onRemove(1);

		assertEquals(2, p.evict(-1));
		p.onRemove(2);

		assertEquals(3, p.evict(-1));
		p.onRemove(3);

		assertNull(p.evict(-1));
	}

	@Test
	public void policyShouldNotDuplicateKeysOnRepeatedPut()
	{
		FifoEvictionPolicy<Integer> p = new FifoEvictionPolicy<>();

		p.onPut(1);
		p.onPut(1);
		p.onPut(1);
		p.onPut(2);

		assertEquals(1, p.evict(-1));
		p.onRemove(1);

		assertEquals(2, p.evict(-1));
		p.onRemove(2);

		assertNull(p.evict(-1));
	}

	@Test
	public void policyShouldSkipKeysThatWereRemovedBeforeEviction()
	{
		FifoEvictionPolicy<Integer> p = new FifoEvictionPolicy<>();

		p.onPut(1);
		p.onPut(2);
		p.onPut(3);

		p.onRemove(2);

		assertEquals(1, p.evict(-1));
		p.onRemove(1);

		assertEquals(3, p.evict(-1));
		p.onRemove(3);

		assertNull(p.evict(-1));
	}
}
