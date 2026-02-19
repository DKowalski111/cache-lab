package cache.lab;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Data;

@Data
public class CacheEntry<K, V>
{
	V value;
	long updatedNanoTime;
	AtomicReference<CompletableFuture<CacheEntry<K, V>>> isBeingReloaded = new AtomicReference<>(null);

	@Override
	public String toString()
	{
		return "value: " + value + ", updatedNanoTime: " + updatedNanoTime + ", isBeingReloaded: " + isBeingReloaded.get();
	}
}
