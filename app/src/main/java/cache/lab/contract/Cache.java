package cache.lab.contract;

import java.util.function.Function;

public interface Cache<K, V>
{
	V get(K key, Function<K, V> loader);
	void invalidate(K key);
	void invalidateAll();
}
