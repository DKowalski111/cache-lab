package cache.lab.impl;

import cache.lab.contract.Ticker;

public final class DefaultTicker implements Ticker
{
	public static final Ticker INSTANCE = new DefaultTicker();

	private DefaultTicker()
	{
	}

	@Override
	public long read()
	{
		return System.nanoTime();
	}
}
