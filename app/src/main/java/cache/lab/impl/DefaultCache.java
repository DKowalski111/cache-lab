package cache.lab.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import cache.lab.CacheConfig;
import cache.lab.CacheEntry;
import cache.lab.contract.Cache;
import cache.lab.contract.Ticker;

public class DefaultCache<K, V> implements Cache<K, V>
{
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> cache = new ConcurrentHashMap<>();
	private final CacheConfig cacheConfig;

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
		// CHECK WHETHER KEY EXISTS IN CACHE MAP
		CompletableFuture<CacheEntry<K, V>> newEntry = new CompletableFuture<>();
		CompletableFuture<CacheEntry<K, V>> existing = cache.putIfAbsent(key, newEntry);
		if (existing == null)
		{
			// ENTRY DOES NOT EXIST
			return executeLoaderForNewEntry(key, loader, newEntry);
		}

		return existing.join().getValue();
	}

	private V executeLoaderForNewEntry(K key, Function<K, V> loader, CompletableFuture<CacheEntry<K, V>> future)
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
