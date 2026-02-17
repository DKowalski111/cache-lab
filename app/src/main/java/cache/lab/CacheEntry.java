package cache.lab;

import lombok.Data;

@Data
public class CacheEntry<K, V>
{
	V value;
	long updatedNanoTime;
}
