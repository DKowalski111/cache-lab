package cache.lab.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import cache.lab.CacheConfig;
import cache.lab.CacheEntry;
import cache.lab.contract.Cache;

public class DefaultCache<K, V> implements Cache<K, V>
{
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> cache = new ConcurrentHashMap<>();
	private final CacheConfig cacheConfig;
	private final ReentrantLock lock = new ReentrantLock();

	public DefaultCache()
	{
		cacheConfig = new CacheConfig();
	}

	public DefaultCache(final CacheConfig cacheConfig)
	{
		this.cacheConfig = cacheConfig;
	}

	@Override
	public V get(final K key, final Function<K, V> loader)
	{
		long now = System.nanoTime();
		// CHECK WHETHER KEY EXISTS IN THE CACHE MAP
		CompletableFuture<CacheEntry<K, V>> newEntry = new CompletableFuture<>();
		CompletableFuture<CacheEntry<K, V>> existing = cache.putIfAbsent(key, newEntry);
		if (existing == null)
		{
			// ENTRY DOES NOT EXIST
			// IT IS ASSUMED, THAT IF TTL = 0 VALUE IS STILL RETURNED WHEN CALCULATED
			V v = executeLoader(key, loader, newEntry);
			System.out.println("Thread ID: " + Thread.currentThread().getName() + " - New v: " + v);
			return v;
		}

		// CHECK TTL
		CacheEntry<K, V> currentValue = existing.join();
		long updatedNanoTime = currentValue.getUpdatedNanoTime();

		// IF IS FRESH
		if (updatedNanoTime + cacheConfig.getTtl().toNanos() > now)
		{
			return currentValue.getValue();
		}

		// IF IS STALE
		if (updatedNanoTime + cacheConfig.getMaxStale().toNanos() > now)
		{
			CompletableFuture<CacheEntry<K, V>> refreshRacer = new CompletableFuture<>();
			refreshEntry(key, loader, refreshRacer, currentValue);

			return currentValue.getValue();
		}

		// HARD EXPIRED
		CompletableFuture<CacheEntry<K, V>> refreshRacer = new CompletableFuture<>();
		refreshEntry(key, loader, refreshRacer, currentValue);

		return refreshRacer.join().getValue();
	}

	private V executeLoader(K key, Function<K, V> loader, CompletableFuture<CacheEntry<K, V>> future)
	{
		try
		{
			V value = loader.apply(key);
			CacheEntry<K, V> cacheEntry = new CacheEntry<>();
			cacheEntry.setValue(value);
			cacheEntry.setUpdatedNanoTime(cacheConfig.getTicker().read());
			future.complete(cacheEntry);
			return value;
		}
		catch (Exception e)
		{
			cache.remove(key, future);
			future.completeExceptionally(e);
			throw e;
		}
	}

	private void refreshEntry(K key, Function<K, V> loader, CompletableFuture<CacheEntry<K, V>> refreshRacer, CacheEntry<K, V> currentValue)
	{
		boolean raceWon = currentValue.getIsBeingReloaded().compareAndSet(null, refreshRacer);
		if (raceWon)
		{
			System.out.println("Thread won race: " + Thread.currentThread().getName());
			cacheConfig.getExecutor().submit(() -> {
				try
				{
					V v = executeLoader(key, loader, refreshRacer);
					CacheEntry<K, V> newCacheEntry = new CacheEntry<>();
					newCacheEntry.setValue(v);
					newCacheEntry.setUpdatedNanoTime(cacheConfig.getTicker().read());
				}
				finally
				{
					currentValue.getIsBeingReloaded().compareAndSet(refreshRacer, null);
				}
			});
		}
		else
		{
			System.out.println("Thread didn't win race: " + Thread.currentThread().getName());
		}
	}

	@Override
	public void invalidate(final K key)
	{
		cache.remove(key);
	}

	@Override
	public void invalidateAll()
	{
		cache.forEach(cache::remove);
	}
}
