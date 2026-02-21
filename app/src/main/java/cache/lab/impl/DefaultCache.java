package cache.lab.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import cache.lab.CacheConfig;
import cache.lab.CacheEntry;
import cache.lab.contract.Cache;

public class DefaultCache<K, V> implements Cache<K, V>
{
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> cache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> refreshes = new ConcurrentHashMap<>();
	private final CacheConfig cacheConfig;

	public DefaultCache()
	{
		this.cacheConfig = new CacheConfig();
	}

	public DefaultCache(final CacheConfig cacheConfig)
	{
		this.cacheConfig = cacheConfig;
	}

	@Override
	public V get(final K key, final Function<K, V> loader)
	{
		final long now = cacheConfig.getTicker().read();

		final CompletableFuture<CacheEntry<K, V>> initialFuture = new CompletableFuture<>();
		final CompletableFuture<CacheEntry<K, V>> existingFuture = cache.putIfAbsent(key, initialFuture);

		if (existingFuture == null)
		{
			return loadIntoFuture(key, loader, initialFuture, now).getValue();
		}

		final CacheEntry<K, V> existingEntry = existingFuture.join();
		final long updated = existingEntry.getUpdatedNanoTime();

		if (updated + cacheConfig.getTtl().toNanos() > now)
		{
			return existingEntry.getValue();
		}

		if (updated + cacheConfig.getMaxStale().toNanos() > now)
		{
			triggerBackgroundRefreshIfNeeded(key, loader, existingFuture);
			return existingEntry.getValue();
		}

		final CompletableFuture<CacheEntry<K, V>> refreshedFuture = triggerRefreshIfNeeded(key, loader, existingFuture);
		return refreshedFuture.join().getValue();
	}

	private void triggerBackgroundRefreshIfNeeded(K key, Function<K,V> loader, CompletableFuture<CacheEntry<K,V>> currentFuture) {
		refreshes.computeIfAbsent(key, k -> {
			CompletableFuture<CacheEntry<K, V>> refreshFuture = new CompletableFuture<>();
			cacheConfig.getExecutor().submit(() -> {
				long now = cacheConfig.getTicker().read();
				try {
					V value = loader.apply(key);
					CacheEntry<K,V> entry = new CacheEntry<>(value, now);
					refreshFuture.complete(entry);

					cache.replace(key, currentFuture, CompletableFuture.completedFuture(entry));
				} catch (Exception e) {
					cache.remove(key, currentFuture);
					refreshFuture.completeExceptionally(e);
				} finally {
					refreshes.remove(key, refreshFuture);
				}
			});
			return refreshFuture;
		});
	}

	private CompletableFuture<CacheEntry<K, V>> triggerRefreshIfNeeded(
			final K key,
			final Function<K, V> loader,
			final CompletableFuture<CacheEntry<K, V>> currentFuture)
	{
		final CompletableFuture<CacheEntry<K, V>> refreshFuture = new CompletableFuture<>();

		final boolean won = cache.replace(key, currentFuture, refreshFuture);
		if (won)
		{
			cacheConfig.getExecutor().submit(() -> {
				final long now = cacheConfig.getTicker().read();
				loadIntoFuture(key, loader, refreshFuture, now);
			});
			return refreshFuture;
		}

		return cache.get(key);
	}

	private CacheEntry<K, V> loadIntoFuture(
			final K key,
			final Function<K, V> loader,
			final CompletableFuture<CacheEntry<K, V>> targetFuture,
			final long now)
	{
		try
		{
			final V value = loader.apply(key);
			final CacheEntry<K, V> entry = new CacheEntry<>(value, now);
			targetFuture.complete(entry);
			return entry;
		}
		catch (Exception e)
		{
			cache.remove(key, targetFuture);
			targetFuture.completeExceptionally(e);
			throw e;
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
		cache.clear();
	}

	public void shutdown() {
		cacheConfig.getExecutor().shutdown();
	}

	public long size()
	{
		return cache.mappingCount();
	}
}
