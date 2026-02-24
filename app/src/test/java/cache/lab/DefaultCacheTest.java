package cache.lab;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.Test;

import cache.lab.impl.DefaultCache;
import cache.lab.impl.DefaultTicker;

public class DefaultCacheTest
{
	private final AtomicInteger loaderCounter = new AtomicInteger();
	private final CacheConfig cacheConfigImmediateStale = new CacheConfig(Duration.ofNanos(0), Duration.ofSeconds(30), 20,
			DefaultTicker.INSTANCE, Executors.newSingleThreadExecutor(), 8);
	private final CacheConfig cacheConfigImmediateHardExpired = new CacheConfig(Duration.ofNanos(0), Duration.ofNanos(0), 20,
			DefaultTicker.INSTANCE, Executors.newSingleThreadExecutor(), 8);

	@Test
	public void shouldNotExecuteLoaderWhenValueFresh()
	{
		// setup
		loaderCounter.set(0);
		final int valueToBeReturned = 10;
		int key = 0;

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>();

		// when
		for (int i = 0; i < 3; i++)
		{
			int v = cache.get(key, k -> {
				loaderCounter.incrementAndGet();
				return valueToBeReturned;
			});
			// then
			assert v == valueToBeReturned;
		}

		// then
		assert loaderCounter.get() == 1;
	}

	@Test
	public void shouldExecuteLoaderWhenValueStale() throws InterruptedException
	{
		// setup
		loaderCounter.set(0);
		final int iterations = 2;
		final int valueToBeReturned = 10;
		int key = 0;
		CountDownLatch latch = new CountDownLatch(iterations);

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateStale);

		// when
		for (int i = 0; i < iterations; i++)
		{
			int v = cache.get(key, k -> {
				latch.countDown();
				loaderCounter.incrementAndGet();
				return valueToBeReturned;
			});
			// then
			assert v == valueToBeReturned;
		}

		// then
		assert latch.await(5, TimeUnit.SECONDS);
		assert loaderCounter.get() == iterations;
	}

	@Test
	public void shouldRefreshAndWaitForResultWhenHardExpired() throws InterruptedException
	{
		// setup
		loaderCounter.set(0);
		final int iterations = 2;
		CountDownLatch latch = new CountDownLatch(iterations);
		final int valueToBeReturned = 10;
		int key = 0;

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateHardExpired);

		// when
		for (int i = 0; i < iterations; i++)
		{
			int v = cache.get(key, k -> {
				latch.countDown();
				loaderCounter.incrementAndGet();
				return valueToBeReturned;
			});
			// then
			assert v == valueToBeReturned;
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		assert loaderCounter.get() == iterations;
	}

	@Test
	public void shouldReloadValueWhenEntryInvalidated()
	{
		// setup
		loaderCounter.set(0);
		int key = 0;
		final int valueToBeReturned = 10;
		final int timesLoaderShouldBeRun = 2;

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>();

		// when
		simpleCacheGet(cache, key, valueToBeReturned);

		// then
		assert cache.size() == 1;

		// when
		cache.invalidate(key);

		// then
		assert cache.size() == 0;

		simpleCacheGet(cache, key, valueToBeReturned);

		// then
		assert cache.size() == 1;

		// then
		assert loaderCounter.get() == timesLoaderShouldBeRun;
	}

	@Test
	public void shouldExecuteLoaderOnceForFreshSingleKeyInMultithreadedEnvironment()
			throws InterruptedException, ExecutionException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 20;
		CountDownLatch latch = new CountDownLatch(iterations);
		final int valueToBeReturned = 10;
		int key = 0;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>();

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 return valueToBeReturned;
															 })).toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			for (Future<Integer> future : futures)
			{
				// then
				assert future.get().equals(valueToBeReturned);
				latch.countDown();
			}
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		assert loaderCounter.get() == 1;
	}

	@Test
	public void shouldExecuteLoaderOnceForStaleSingleKeyInMultithreadedEnvironment()
			throws InterruptedException, ExecutionException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 10;
		CountDownLatch latch = new CountDownLatch(1);
		final int originalValue = 10;
		final int updatedValue = 20;
		int key = 0;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateStale);

		Integer v = cache.get(key, k -> originalValue);
		assert v.equals(originalValue);

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(500);
																	 latch.countDown();
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
																 return updatedValue;
															 })).toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			for (Future<Integer> future : futures)
			{
				// then
				assert future.get().equals(originalValue);
			}
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		assert loaderCounter.get() == 1;
		assert cache.get(key, k -> originalValue) == updatedValue;
	}

	@Test
	public void shouldExecuteLoaderOnceForHardExpiredSingleKeyInMultithreadedEnvironment()
			throws InterruptedException, ExecutionException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 5;
		CountDownLatch latch = new CountDownLatch(1);
		final int originalValue = 10;
		final int updatedValue = 20;
		int key = 0;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateHardExpired);

		Integer v = cache.get(key, k -> originalValue);
		assert v.equals(originalValue);

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(1000);
																	 latch.countDown();
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
																 return updatedValue;
															 })).toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			for (Future<Integer> future : futures)
			{
				// then
				assert future.get().equals(updatedValue);
			}
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		assert loaderCounter.get() == 1;
	}

	@Test
	public void shouldNotSaveToCacheAndContinueWhenLoaderThrowsException() throws InterruptedException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 5;
		CountDownLatch latch = new CountDownLatch(1);
		int key = 0;
		final int originalValue = 10;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>();

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(500);
																	 latch.countDown();
																	 throw new RuntimeException();
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
															 })).toList();

			executor.invokeAll(tasks);
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		assert loaderCounter.get() == 1;
		assert cache.size() == 0;

		// when
		Integer i = cache.get(key, k -> {
			loaderCounter.incrementAndGet();
			return originalValue;
		});

		assert i == originalValue;
		assert loaderCounter.get() == 2;
		assert cache.size() == 1;
	}

	@Test
	public void shouldRemoveEntryWhenStaleWhenLoaderThrowsException() throws InterruptedException, ExecutionException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 5;
		CountDownLatch latch = new CountDownLatch(1);
		int key = 0;
		final int originalValue = 10;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateStale);

		// when
		cache.get(key, k -> originalValue);

		// and
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(100);
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
																 latch.countDown();
																 throw new RuntimeException();
															 })).toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			for (Future<Integer> future : futures)
			{
				assert future.get().equals(originalValue);
			}
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		Thread.sleep(500);
		assert loaderCounter.get() == 1;
		assert cache.size() == 0;

		// when
		CountDownLatch finishLatch = new CountDownLatch(1);

		Integer i = cache.get(key, k -> {
			loaderCounter.incrementAndGet();
			finishLatch.countDown();
			return originalValue;
		});

		assert finishLatch.await(4, TimeUnit.SECONDS);
		assert i == originalValue;
		assert loaderCounter.get() == 2;
		assert cache.size() == 1;
	}

	@Test
	public void shouldRemoveEntryWhenHardExpiredWhenLoaderThrowsException() throws InterruptedException, ExecutionException
	{
		// setup
		loaderCounter.set(0);

		// given
		final int iterations = 5;
		CountDownLatch latch = new CountDownLatch(1);
		int key = 0;
		final int originalValue = 10;
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateHardExpired);

		// when
		cache.get(key, k -> originalValue);

		// and
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key, k -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(100);
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
																 latch.countDown();
																 throw new RuntimeException();
															 })).toList();

			executor.invokeAll(tasks);
		}

		// then
		assert latch.await(4, TimeUnit.SECONDS);
		Thread.sleep(500);
		assert loaderCounter.get() == 1;
		assert cache.size() == 0;

		// when
		CountDownLatch finishLatch = new CountDownLatch(1);

		Integer i = cache.get(key, k -> {
			loaderCounter.incrementAndGet();
			finishLatch.countDown();
			return originalValue;
		});

		assert finishLatch.await(4, TimeUnit.SECONDS);
		assert i == originalValue;
		assert loaderCounter.get() == 2;
		assert cache.size() == 1;
	}

	@Test
	public void shouldNotThrowNPE_WhenInvalidatedWhileRefreshing() throws InterruptedException
	{
		// setup
		loaderCounter.set(0);
		final int iterations = 1;
		final int valueToBeReturned = 10;
		final int updatedValueToBeReturned = 20;
		int key = 0;
		CountDownLatch latch = new CountDownLatch(iterations);

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateStale);

		// and
		cache.get(key, v -> valueToBeReturned);

		// when
		cache.get(key, v -> {
			loaderCounter.incrementAndGet();
			try
			{
				Thread.sleep(500);
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			latch.countDown();
			return updatedValueToBeReturned;
		});

		// and
		cache.invalidate(key);

		// then
		assert latch.await(5, TimeUnit.SECONDS);
		assert cache.size() == 0;
		assert loaderCounter.get() == iterations;
	}

	@Test
	public void shouldNotThrowNPE_WhenInvalidatedAllWhileRefreshing() throws InterruptedException
	{
		// setup
		loaderCounter.set(0);
		final int iterations = 1;
		final int valueToBeReturned = 10;
		final int updatedValueToBeReturned = 20;
		int key = 0;
		CountDownLatch latch = new CountDownLatch(iterations);

		// given
		DefaultCache<Integer, Integer> cache = new DefaultCache<>(cacheConfigImmediateStale);

		// and
		cache.get(key, v -> valueToBeReturned);

		// when
		cache.get(key, v -> {
			loaderCounter.incrementAndGet();
			try
			{
				Thread.sleep(500);
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			latch.countDown();
			return updatedValueToBeReturned;
		});

		// and
		cache.invalidateAll();

		// then
		assert latch.await(5, TimeUnit.SECONDS);
		assert cache.size() == 0;
		assert loaderCounter.get() == iterations;
	}

	@Test
	public void shouldExecuteManyLoadersWithoutBlockingOthersInMultithreadedEnvironment() throws InterruptedException
	{
		// set
		loaderCounter.set(0);

		// given
		final int iterations = 20;
		final int baseReturnedValue = 10;
		int key = 2;
		CountDownLatch latch = new CountDownLatch(iterations);
		DefaultCache<Integer, Integer> cache = new DefaultCache<>();

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			List<Callable<Integer>> tasks = IntStream.range(0, iterations)
													 .mapToObj(i -> (Callable<Integer>) () ->
															 cache.get(key * i, v -> {
																 loaderCounter.incrementAndGet();
																 try
																 {
																	 Thread.sleep(1000);
																 }
																 catch (InterruptedException e)
																 {
																	 throw new RuntimeException(e);
																 }
																 latch.countDown();
																 return baseReturnedValue * i;

															 })).toList();

			List<Future<Integer>> futures = executor.invokeAll(tasks);

			List<Integer> sortedResults = futures.stream()
												 .map(future -> {
													 try
													 {
														 return future.get();
													 }
													 catch (Exception e)
													 {
														 throw new RuntimeException(e);
													 }
												 })
												 .sorted()
												 .toList();

			// then
			for (int i = 0; i < iterations; i++)
			{
				assert sortedResults.get(i) == i * baseReturnedValue;
			}

		}

		// and
		assert latch.await(5, TimeUnit.SECONDS);
		assert cache.size() == iterations;
	}

	private void simpleCacheGet(DefaultCache<Integer, Integer> cache, int key, int valueToBeReturned)
	{
		// when
		int v = cache.get(key, k -> {
			loaderCounter.incrementAndGet();
			return valueToBeReturned;
		});

		// then
		assert v == valueToBeReturned;
	}
}
