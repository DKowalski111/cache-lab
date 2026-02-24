package cache.lab.impl;

import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import cache.lab.contract.EvictionPolicy;

public class LruEvictionPolicy<K> implements EvictionPolicy<K>
{
	private final LinkedHashMap<K, Boolean> lruCache = new LinkedHashMap<>(16, 0.75f, true);
	private final ReentrantLock lock = new ReentrantLock();

	@Override
	public void onGet(final K key)
	{
		lock.lock();
		try
		{
			if (lruCache.containsKey(key))
			{
				lruCache.get(key);
			}
		}
		finally
		{
			lock.unlock();
		}
	}

	@Override
	public void onPut(final K key)
	{
		lock.lock();
		try
		{
			lruCache.put(key, Boolean.TRUE);
		}
		finally
		{
			lock.unlock();
		}	}

	@Override
	public void onRemove(final K key)
	{
		lock.lock();
		try
		{
			lruCache.remove(key);
		}
		finally
		{
			lock.unlock();
		}
	}

	@Override
	public K evict(final K key)
	{
		lock.lock();
		try
		{
			if (lruCache.isEmpty())
			{
				return null;
			}
			return lruCache.keySet().iterator().next();
		}
		finally
		{
			lock.unlock();
		}
	}
}
