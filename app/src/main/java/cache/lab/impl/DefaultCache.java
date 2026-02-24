package cache.lab.impl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import cache.lab.CacheConfig;
import cache.lab.CacheEntry;
import cache.lab.contract.Cache;
import cache.lab.contract.EvictionPolicy;

public class DefaultCache<K, V> implements Cache<K, V>
{
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> cache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<K, CompletableFuture<CacheEntry<K, V>>> refreshes = new ConcurrentHashMap<>();
	private final EvictionPolicy<K> evictionPolicy;
	private final CacheConfig cacheConfig;

	public DefaultCache()
	{
		this.cacheConfig = new CacheConfig();
		this.evictionPolicy = new FifoEvictionPolicy<K>();
	}

	public DefaultCache(final CacheConfig cacheConfig)
	{
		this.cacheConfig = cacheConfig;
		this.evictionPolicy = new FifoEvictionPolicy<K>();
	}

	public DefaultCache(final EvictionPolicy<K> evictionPolicy)
	{
		this.cacheConfig = new CacheConfig();
		this.evictionPolicy = evictionPolicy;
	}

	public DefaultCache(final CacheConfig cacheConfig, final EvictionPolicy<K> evictionPolicy)
	{
		this.cacheConfig = cacheConfig;
		this.evictionPolicy = evictionPolicy;
	}

	@Override
	public V get(final K key, final Function<K, V> loader)
	{
		final long now = cacheConfig.getTicker().read();

		final CompletableFuture<CacheEntry<K, V>> initialFuture = new CompletableFuture<>();
		final CompletableFuture<CacheEntry<K, V>> existingFuture = cache.putIfAbsent(key, initialFuture);

		if (existingFuture == null)
		{
			evictionPolicy.onPut(key);
			CacheEntry<K, V> entry = loadIntoFuture(key, loader, initialFuture, now);
			evictIfNeeded(key);
			return entry.getValue();
		}

		final CacheEntry<K, V> existingEntry = existingFuture.join();
		final long updated = existingEntry.getUpdatedNanoTime();

		if (updated + cacheConfig.getTtl().toNanos() > now)
		{
			evictionPolicy.onGet(key);
			return existingEntry.getValue();
		}

		if (updated + cacheConfig.getMaxStale().toNanos() > now)
		{
			triggerBackgroundRefreshIfNeeded(key, loader, existingFuture);
			evictionPolicy.onGet(key);
			return existingEntry.getValue();
		}

		final CompletableFuture<CacheEntry<K, V>> refreshedFuture = triggerRefreshIfNeeded(key, loader, existingFuture);
		V ret = refreshedFuture.join().getValue();
		evictionPolicy.onGet(key);
		return ret;
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
					evictionPolicy.onRemove(key);
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
			evictionPolicy.onRemove(key);
			targetFuture.completeExceptionally(e);
			throw e;
		}
	}

	private void evictIfNeeded(final K key)
	{
		while (cacheConfig.getMaxSize() < cache.size())
		{
			int attemptsLeft = cache.size() + 16;

			while (attemptsLeft-- > 0)
			{
				final K victim = evictionPolicy.evict(key);
				if (victim == null)
				{
					return;
				}
				if (invalidate(victim))
				{
					break;
				}
			}

			if (attemptsLeft <= 0)
			{
				return;
			}
		}
	}

	@Override
	public boolean invalidate(final K key)
	{
		boolean removed = false;
		if (cache.remove(key) != null)
		{
			removed = true;
			evictionPolicy.onRemove(key);
		}
		refreshes.remove(key);
		return removed;
	}

	@Override
	public void invalidateAll()
	{
		for (K key : cache.keySet())
		{
			evictionPolicy.onRemove(key);
		}
		cache.clear();
		refreshes.clear();
	}

	public long size()
	{
		return cache.size();
	}

	public List<V> values()
	{
		return cache.values().stream()
					.filter(CompletableFuture::isDone)
					.map(f -> {
						try
						{
							return f.getNow(null);
						}
						catch (Exception e)
						{
							return null;
						}
					})
					.filter(Objects::nonNull)
					.map(CacheEntry::getValue)
					.toList();
	}
}
