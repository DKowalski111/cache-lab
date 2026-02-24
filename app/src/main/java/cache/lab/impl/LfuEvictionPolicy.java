package cache.lab.impl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReentrantLock;

import cache.lab.contract.EvictionPolicy;

public class LfuEvictionPolicy<K> implements EvictionPolicy<K>
{
	private int minFreq = 0;
	private final HashMap<Integer, LinkedHashMap<K, Boolean>> freqToKeys = new HashMap<>();
	private final HashMap<K, Integer> keyToFreq = new HashMap<>();
	private final ReentrantLock lock = new ReentrantLock();

	@Override
	public void onGet(final K key)
	{
		lock.lock();
		try
		{
			Integer freqObj = keyToFreq.get(key);
			if (freqObj == null)
			{
				return;
			}

			int freq = freqObj;
			int newFreq = freq + 1;

			keyToFreq.put(key, newFreq);

			LinkedHashMap<K, Boolean> freqMap = freqToKeys.get(freq);
			if (freqMap != null)
			{
				freqMap.remove(key);
				if (freqMap.isEmpty())
				{
					freqToKeys.remove(freq);
					if (minFreq == freq)
					{
						minFreq = newFreq;
					}
				}
			}

			LinkedHashMap<K, Boolean> newFreqMap = freqToKeys.get(newFreq);
			if (newFreqMap == null)
			{
				newFreqMap = new LinkedHashMap<>(16, 0.75f, false);
				freqToKeys.put(newFreq, newFreqMap);
			}
			newFreqMap.put(key, Boolean.TRUE);
		}
		finally
		{
			lock.unlock();
		}
	}

	@Override
	public void onPut(final K key)
	{
		lock.lock();
		try
		{
			final int startFreq = 1;

			Integer existing = keyToFreq.get(key);
			if (existing != null)
			{
				onGet(key);
				return;
			}

			minFreq = startFreq;
			keyToFreq.put(key, startFreq);

			LinkedHashMap<K, Boolean> startMap = freqToKeys.computeIfAbsent(startFreq,
					k -> new LinkedHashMap<>(16, 0.75f, false));
			startMap.put(key, Boolean.TRUE);
		}
		finally
		{
			lock.unlock();
		}
	}

	@Override
	public void onRemove(final K key)
	{
		lock.lock();
		try
		{
			Integer freqObj = keyToFreq.remove(key);
			if (freqObj == null)
			{
				return;
			}

			int freq = freqObj;

			LinkedHashMap<K, Boolean> freqMap = freqToKeys.get(freq);
			if (freqMap != null)
			{
				freqMap.remove(key);
				if (freqMap.isEmpty())
				{
					freqToKeys.remove(freq);
				}
			}

			if (keyToFreq.isEmpty())
			{
				minFreq = 0;
				return;
			}

			if (minFreq == freq)
			{
				while (freqToKeys.get(minFreq) == null || freqToKeys.get(minFreq).isEmpty())
				{
					minFreq++;
				}
			}
		}
		finally
		{
			lock.unlock();
		}
	}

	@Override
	public K evict(final K key)
	{
		lock.lock();
		try
		{
			LinkedHashMap<K, Boolean> minFreqMap = freqToKeys.get(minFreq);
			if (minFreqMap == null || minFreqMap.isEmpty())
			{
				return null;
			}

			K victim = minFreqMap.keySet().iterator().next();

			minFreqMap.remove(victim);
			if (minFreqMap.isEmpty())
			{
				freqToKeys.remove(minFreq);
			}

			keyToFreq.remove(victim);

			if (keyToFreq.isEmpty())
			{
				minFreq = 0;
			}
			else
			{
				while (freqToKeys.get(minFreq) == null || freqToKeys.get(minFreq).isEmpty())
				{
					minFreq++;
				}
			}

			return victim;
		}
		finally
		{
			lock.unlock();
		}
	}
}
