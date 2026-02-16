package cache.lab.impl;

import cache.lab.contract.Cache;

public class DefaultCache<K, V> implements Cache<K, V>
{

	@Override
	public V get(final K key, final V loader)
	{
		return null;
	}

	@Override
	public void invalidate(final K key)
	{

	}

	@Override
	public void invalidateAll()
	{

	}
}
