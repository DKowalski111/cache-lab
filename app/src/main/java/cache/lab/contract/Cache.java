package cache.lab.contract;

public interface Cache<K, V>
{
	V get(K key, V loader);
	void invalidate(K key);
	void invalidateAll();
}
