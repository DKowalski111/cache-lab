package cache.lab;

import lombok.Value;

@Value
public class CacheEntry<K, V>
{
	V value;
	long updatedNanoTime;
}
