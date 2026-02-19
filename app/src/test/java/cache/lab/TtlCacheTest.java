package cache.lab;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import cache.lab.impl.DefaultCache;
import cache.lab.impl.DefaultTicker;

public class TtlCacheTest
{
	private final AtomicInteger loaderCounter = new AtomicInteger();
	private final CacheConfig cacheConfigImmediateStale = new CacheConfig(Duration.ofNanos(0), Duration.ofSeconds(30), 10,
			DefaultTicker.INSTANCE, Executors.newSingleThreadExecutor(), 8);
	private final CacheConfig cacheConfigImmediateHardExpired = new CacheConfig(Duration.ofNanos(0), Duration.ofNanos(0), 10,
			DefaultTicker.INSTANCE, Executors.newSingleThreadExecutor(), 8);

	@Test
	public void basicShouldTryToExecuteLoaderWhenStale() throws InterruptedException
	{
		// FIRST LOOP ITERATION CREATES ENTRY, SECOND REFRESHES THE ENTRY, THIRD DOES NOT REFRESH THE ENTRY AS IT IS ALREADY BEING REFRESHED BY THREAD 2
		loaderCounter.set(0);
		final var cache = new DefaultCache<>(cacheConfigImmediateStale);
		final int key = 0;
		final int timesThatLoaderShouldBeRun = 2;
		CountDownLatch latch = new CountDownLatch(timesThatLoaderShouldBeRun);

		for (int i = 0; i < 3; i++)
		{
			cache.get(key, k -> {
				try
				{
					return simpleLoaderWithCounter();
				}
				catch (InterruptedException e)
				{
					throw new RuntimeException(e);
				}
				finally
				{
					latch.countDown();
				}
			});
		}

		boolean finished = latch.await(5, TimeUnit.SECONDS);

		assert finished;
		assert loaderCounter.get() == 2;
	}

	@Test
	public void shouldReloadValueWhenHardExpiredAndWaitForTheResult() throws InterruptedException
	{
		// FIRST LOOP ITERATION CREATES ENTRY, SECOND REFRESHES THE ENTRY AND WAITS FOR THE RESULT, THIRD DOES NOT REFRESH THE ENTRY AS IT IS ALREADY BEING REFRESHED BY THREAD 2 AND WAITS FOR THE RESULT
		loaderCounter.set(0);
		final var cache = new DefaultCache<>(cacheConfigImmediateHardExpired);
		final int key = 0;
		final int timesThatLoaderShouldBeRun = 2;
		CountDownLatch latch = new CountDownLatch(timesThatLoaderShouldBeRun);

		for (int i = 0; i < 3; i++)
		{
			cache.get(key, k -> {
				try
				{
					return simpleLoaderWithCounter();
				}
				catch (InterruptedException e)
				{
					throw new RuntimeException(e);
				}
				finally
				{
					latch.countDown();
				}
			});
		}

		boolean finished = latch.await(5, TimeUnit.SECONDS);

		assert finished;
		assert loaderCounter.get() == 2;
	}

	private int simpleLoaderWithCounter() throws InterruptedException
	{
		Thread.sleep(1000);
		return loaderCounter.incrementAndGet();
	}
}
