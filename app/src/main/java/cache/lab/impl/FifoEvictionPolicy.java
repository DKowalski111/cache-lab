package cache.lab.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import cache.lab.contract.EvictionPolicy;

public class FifoEvictionPolicy<K> implements EvictionPolicy<K>
{
	private final ConcurrentLinkedQueue<K> queue = new ConcurrentLinkedQueue<>();
	private final ConcurrentHashMap<K, Boolean> present = new ConcurrentHashMap<>();

	@Override
	public void onGet(final K key)
	{
		// does nothing
	}

	@Override
	public void onPut(final K key)
	{
		if (present.putIfAbsent(key, Boolean.TRUE) == null)
		{
			queue.offer(key);
		}
	}

	@Override
	public void onRemove(final K key)
	{
		present.remove(key);
	}

	@Override
	public K evict(final K key)
	{
		for (;;)
		{
			K victim = queue.poll();
			if (victim == null)
			{
				return null;
			}
			if (present.remove(victim) != null)
			{
				return victim;
			}
		}
	}
}
