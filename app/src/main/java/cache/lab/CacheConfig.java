package cache.lab;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cache.lab.contract.Ticker;
import cache.lab.impl.DefaultTicker;
import lombok.Getter;

@Getter
public class CacheConfig
{
	private final Duration ttl;
	private final Duration maxStale;
	private final long maxSize;
	private final Ticker ticker;
	private final ExecutorService executor;
	private final int refreshConcurrency;

	public CacheConfig()
	{
		this.ttl = Duration.ofMinutes(10);
		this.maxStale = Duration.ofSeconds(30);
		this.maxSize = 20;
		this.ticker = DefaultTicker.INSTANCE;
		this.executor = Executors.newFixedThreadPool(8);
		this.refreshConcurrency = 8;
	}

	public CacheConfig(final Duration ttl, final Duration maxStale, final long maxSize, final Ticker ticker,
					   final ExecutorService executor, final int refreshConcurrency)
	{
		this.ttl = ttl;
		this.maxStale = maxStale;
		this.maxSize = maxSize;
		this.ticker = ticker;
		this.executor = executor;
		this.refreshConcurrency = refreshConcurrency;
	}


}
