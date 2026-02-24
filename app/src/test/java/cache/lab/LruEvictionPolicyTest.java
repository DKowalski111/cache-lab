package cache.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.Test;

import cache.lab.contract.EvictionPolicy;
import cache.lab.impl.DefaultCache;
import cache.lab.impl.DefaultTicker;
import cache.lab.impl.LruEvictionPolicy;

import static org.junit.jupiter.api.Assertions.*;

public class LruEvictionPolicyTest
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

		EvictionPolicy<Integer> evictionPolicy = new LruEvictionPolicy<>();
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
	public void policyShouldEvictLeastRecentlyUsedKey()
	{
		LruEvictionPolicy<Integer> p = new LruEvictionPolicy<>();

		p.onPut(1);
		p.onPut(2);
		p.onPut(3);

		p.onGet(1);

		assertEquals(2, p.evict(-1));
		p.onRemove(2);

		assertEquals(3, p.evict(-1));
		p.onRemove(3);

		assertEquals(1, p.evict(-1));
		p.onRemove(1);

		assertNull(p.evict(-1));
	}

	@Test
	public void policyShouldTreatGetAsRecentUse()
	{
		LruEvictionPolicy<Integer> p = new LruEvictionPolicy<>();

		p.onPut(1);
		p.onPut(2);

		p.onGet(1);

		assertEquals(2, p.evict(-1));
	}

	@Test
	public void policyShouldNotThrowWhenGettingMissingKey()
	{
		LruEvictionPolicy<Integer> p = new LruEvictionPolicy<>();

		assertDoesNotThrow(() -> p.onGet(123));
		assertNull(p.evict(-1));
	}

	@Test
	public void policyShouldHandleRemoveBeforeEvict()
	{
		LruEvictionPolicy<Integer> p = new LruEvictionPolicy<>();

		p.onPut(1);
		p.onPut(2);
		p.onPut(3);

		p.onRemove(2);

		p.onGet(1);

		assertEquals(3, p.evict(-1));
	}
}
