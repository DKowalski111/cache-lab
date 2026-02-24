package cache.lab.contract;

public interface EvictionPolicy<K>
{
	void onGet(final K key);
	void onPut(final K key);
	void onRemove(final K key);
	K evict(final K key);
}
