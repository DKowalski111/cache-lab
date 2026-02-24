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
import cache.lab.impl.LfuEvictionPolicy;

public class LfuEvictionPolicyTest
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

		EvictionPolicy<Integer> evictionPolicy = new LfuEvictionPolicy<>();
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
	public void shouldEvictLeastFrequentlyUsedKey() throws Exception
	{
		CacheConfig small = new CacheConfig(
				Duration.ofSeconds(30),
				Duration.ofSeconds(30),
				2,
				DefaultTicker.INSTANCE,
				Executors.newSingleThreadExecutor(),
				8
		);

		EvictionPolicy<Integer> evictionPolicy = new LfuEvictionPolicy<>();
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(small, evictionPolicy);

		cache.get(1, k -> 1);
		cache.get(2, k -> 2);

		cache.get(1, k -> 1);
		cache.get(1, k -> 1);

		cache.get(3, k -> 3);

		assertEquals(1, cache.get(1, k -> -1));
		assertEquals(3, cache.get(3, k -> -1));

		assertEquals(2, cache.get(2, k -> 2));
		assertEquals(2, cache.size());
	}

	@Test
	public void tieBreakShouldEvictOneOfOldestWithinSameFrequency_cacheIntegration() throws Exception
	{
		CacheConfig small = new CacheConfig(
				Duration.ofSeconds(30),
				Duration.ofSeconds(30),
				2,
				DefaultTicker.INSTANCE,
				Executors.newSingleThreadExecutor(),
				8
		);

		EvictionPolicy<Integer> evictionPolicy = new LfuEvictionPolicy<>();
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(small, evictionPolicy);

		cache.get(1, k -> 1);
		cache.get(2, k -> 2);
		cache.get(3, k -> 3);

		int v1 = cache.get(1, k -> 1);
		int v2 = cache.get(2, k -> 2);
		assertEquals(1, v1);
		assertEquals(2, v2);

		assertTrue(cache.size() <= 2);
	}

	@Test
	public void shouldContinueEvictingEvenIfVictimWasAlreadyGone() throws Exception
	{
		CacheConfig small = new CacheConfig(
				Duration.ofSeconds(30),
				Duration.ofSeconds(30),
				2,
				DefaultTicker.INSTANCE,
				Executors.newSingleThreadExecutor(),
				8
		);

		EvictionPolicy<Integer> evictionPolicy = new LfuEvictionPolicy<>();
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(small, evictionPolicy);

		cache.get(1, k -> 1);
		cache.get(2, k -> 2);

		cache.invalidate(1);

		cache.get(3, k -> 3);

		assertTrue(cache.size() <= 2);

		int v2 = cache.get(2, k -> 2);
		int v3 = cache.get(3, k -> 3);

		assertTrue((v2 == 2 && v3 == 3));
	}
}
