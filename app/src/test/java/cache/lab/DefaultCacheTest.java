package cache.lab;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import cache.lab.impl.DefaultCache;
import cache.lab.impl.DefaultTicker;

public class DefaultCacheTest
{
	private final CacheConfig basicCacheConfig = new CacheConfig(Duration.ofMinutes(1), Duration.ofSeconds(30), 10,
			DefaultTicker.INSTANCE, Executors.newSingleThreadExecutor(), 8);

	@Test
	public void basicCheck() throws InterruptedException
	{
		long startTime = System.nanoTime();
		final int NUMBER_OF_THREADS = 10;
		AtomicInteger counter = new AtomicInteger();

		ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS , r -> {
			Thread t = new Thread(r);
			t.setName("TestThread_" + counter.incrementAndGet());
			return t;
		});

		CountDownLatch latch = new CountDownLatch(NUMBER_OF_THREADS);

		var cache = new DefaultCache<>(basicCacheConfig);

		for(long i = 0; i < NUMBER_OF_THREADS; i++)
		{
			final long valueForLoader = i;
			executorService.submit(() -> {
				try
				{
					long v = (long) cache.get(valueForLoader, key -> {
						try
						{
							return veryHardAndResourcefulCalculation(valueForLoader);
						}
						catch (InterruptedException e)
						{
							throw new RuntimeException(e);
						}
					});
					System.out.println("x calculated by thread: " + Thread.currentThread().getName() + ": " + v);
				}
				finally
				{
					latch.countDown();
				}
			});
		}

		boolean finished = latch.await(5, TimeUnit.SECONDS);

		executorService.shutdown();

		assert finished;

		System.out.println("Time taken: " + Duration.ofNanos(System.nanoTime() - startTime).toMillis());
	}

	@Test
	public void cacheEntryShouldBeAddedOncePerThread() throws InterruptedException
	{
		long startTime = System.nanoTime();
		final int NUMBER_OF_THREADS = 10;
		AtomicInteger counter = new AtomicInteger();

		ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS, r -> {
			Thread t = new Thread(r);
			t.setName("TestThread_" + counter.incrementAndGet());
			return t;
		});

		CountDownLatch latch = new CountDownLatch(NUMBER_OF_THREADS);

		var cache = new DefaultCache<>(basicCacheConfig);

		final long valueForLoader = 10;

		for (long i = 0; i < NUMBER_OF_THREADS; i++)
		{
			executorService.submit(() -> {
				try
				{
					long v = (long) cache.get(valueForLoader, key -> {
						try
						{
							return veryHardAndResourcefulCalculation(valueForLoader);
						}
						catch (InterruptedException e)
						{
							throw new RuntimeException(e);
						}
					});
					System.out.println("valueForLoader calculated by thread: " + Thread.currentThread().getName() + ": " + v);
				}
				finally
				{
					latch.countDown();
				}
			});
		}

		boolean finished = latch.await(5, TimeUnit.SECONDS);

		executorService.shutdown();

		assert finished;

		System.out.println("Time taken: " + Duration.ofNanos(System.nanoTime() - startTime).toMillis());
	}

	private long veryHardAndResourcefulCalculation(long x) throws InterruptedException
	{
		System.out.println("Starting the calculation for thread " + Thread.currentThread().getName());
		Thread.sleep(1000);
		return x * x * x;
	}
}
