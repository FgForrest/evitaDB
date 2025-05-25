/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.dataType.map;

import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * A thread-safe map with weak keys. Entries are based on a key's system hash code and keys are considered
 * equal only by reference equality.
 *
 * This class does not implement the {@link java.util.Map} interface because this implementation is incompatible
 * with the map contract. While iterating over a map's entries, any key that has not passed iteration is referenced non-weakly.
 *
 * The class was copied out from the <a href="https://github.com/mockito/mockito">Mockito</a> project. Thanks!
 *
 * @author Mockito
 */
@SuppressWarnings({"SuspiciousMethodCalls"})
public class WeakConcurrentMap<K, V> extends ReferenceQueue<K>
	implements Iterable<Map.Entry<K, V>> {

	public final ConcurrentMap<WeakKey<K>, V> target;

	public WeakConcurrentMap() {
		this.target = CollectionUtils.createConcurrentHashMap(64);
	}

	/**
	 * @param key The key of the entry.
	 * @return The value of the entry or the default value if it did not exist.
	 */
	@Nullable
	@SuppressWarnings("CollectionIncompatibleType")
	public V get(K key) {
		if (key == null) {
			throw new NullPointerException();
		}
		expungeStaleEntries();
		V value = this.target.get(new LatentKey<>(key));
		if (value == null) {
			value = defaultValue(key);
			if (value != null) {
				V previousValue = this.target.putIfAbsent(new WeakKey<>(key, this), value);
				if (previousValue != null) {
					value = previousValue;
				}
			}
		}
		return value;
	}

	/**
	 * @param key The key of the entry.
	 * @return {@code true} if the key already defines a value.
	 */
	@SuppressWarnings("CollectionIncompatibleType")
	public boolean containsKey(K key) {
		if (key == null) {
			throw new NullPointerException();
		}
		expungeStaleEntries();
		return this.target.containsKey(new LatentKey<>(key));
	}

	/**
	 * @param key   The key of the entry.
	 * @param value The value of the entry.
	 * @return The previous entry or {@code null} if it does not exist.
	 */
	@Nullable
	public V put(K key, V value) {
		if (key == null || value == null) {
			throw new NullPointerException();
		}
		expungeStaleEntries();
		return this.target.put(new WeakKey<>(key, this), value);
	}

	/**
	 * @see Map#computeIfAbsent(Object, Function)
	 */
	public V computeIfAbsent(K key, Function<K, V> computer) {
		return this.target.computeIfAbsent(
			new WeakKey<>(key, this),
			kWeakKey -> computer.apply(kWeakKey.get())
		);
	}

	/**
	 * @param key The key of the entry.
	 * @return The removed entry or {@code null} if it does not exist.
	 */
	@SuppressWarnings("CollectionIncompatibleType")
	public V remove(K key) {
		if (key == null) {
			throw new NullPointerException();
		}
		expungeStaleEntries();
		return this.target.remove(new LatentKey<>(key));
	}

	/**
	 * Clears the entire map.
	 */
	public void clear() {
		expungeStaleEntries();
		this.target.clear();
	}

	/**
	 * Creates a default value. There is no guarantee that the requested value will be set as a once it is created
	 * in case that another thread requests a value for a key concurrently.
	 *
	 * @param key The key for which to create a default value.
	 * @return The default value for a key without value or {@code null} for not defining a default value.
	 */
	@Nullable
	protected V defaultValue(K key) {
		return null;
	}

	/**
	 * Cleans all unused references.
	 */
	public void expungeStaleEntries() {
		Reference<?> reference;
		while ((reference = poll()) != null) {
			this.target.remove(reference);
		}
	}

	/**
	 * Returns the approximate size of this map where the returned number is at least as big as the actual number of entries.
	 *
	 * @return The minimum size of this map.
	 */
	public int approximateSize() {
		return this.target.size();
	}

	@Nonnull
	@Override
	public Iterator<Entry<K, V>> iterator() {
		return new EntryIterator(this.target.entrySet().iterator());
	}

	/*
	 * Why this works:
	 * ---------------
	 *
	 * Note that this map only supports reference equality for keys and uses system hash codes. Also, for the
	 * WeakKey instances to function correctly, we are voluntarily breaking the Java API contract for
	 * hashCode/equals of these instances.
	 *
	 *
	 * System hash codes are immutable and can therefore be computed prematurely and are stored explicitly
	 * within the WeakKey instances. This way, we always know the correct hash code of a key and always
	 * end up in the correct bucket of our target map. This remains true even after the weakly referenced
	 * key is collected.
	 *
	 * If we are looking up the value of the current key via WeakConcurrentMap::get or any other public
	 * API method, we know that any value associated with this key must still be in the map as the mere
	 * existence of this key makes it ineligible for garbage collection. Therefore, looking up a value
	 * using another WeakKey wrapper guarantees a correct result.
	 *
	 * If we are looking up the map entry of a WeakKey after polling it from the reference queue, we know
	 * that the actual key was already collected and calling WeakKey::get returns null for both the polled
	 * instance and the instance within the map. Since we explicitly stored the identity hash code for the
	 * referenced value, it is however trivial to identify the correct bucket. From this bucket, the first
	 * weak key with a null reference is removed. Due to hash collision, we do not know if this entry
	 * represents the weak key. However, we do know that the reference queue polls at least as many weak
	 * keys as there are stale map entries within the target map. If no key is ever removed from the map
	 * explicitly, the reference queue eventually polls exactly as many weak keys as there are stale entries.
	 *
	 * Therefore, we can guarantee that there is no memory leak.
	 */

	private static class WeakKey<T> extends WeakReference<T> {

		private final int hashCode;

		WeakKey(T key, ReferenceQueue<? super T> queue) {
			super(key, queue);
			this.hashCode = System.identityHashCode(key);
		}

		@Override
		public int hashCode() {
			return this.hashCode;
		}

		@Override
		public boolean equals(Object other) {
			if (other instanceof LatentKey<?>) {
				return ((LatentKey<?>) other).key == get();
			} else {
				return ((WeakKey<?>) other).get() == get();
			}
		}
	}

	/*
	 * A latent key must only be used for looking up instances within a map. For this to work, it implements an identical contract for
	 * hash code and equals as the WeakKey implementation. At the same time, the latent key implementation does not extend WeakReference
	 * and avoids the overhead that a weak reference implies.
	 */

	private static class LatentKey<T> {

		final T key;

		private final int hashCode;

		LatentKey(T key) {
			this.key = key;
			this.hashCode = System.identityHashCode(key);
		}

		@Override
		public boolean equals(Object other) {
			if (other instanceof LatentKey<?>) {
				return ((LatentKey<?>) other).key == this.key;
			} else {
				return ((WeakKey<?>) other).get() == this.key;
			}
		}

		@Override
		public int hashCode() {
			return this.hashCode;
		}
	}

	private class EntryIterator implements Iterator<Map.Entry<K, V>> {

		private final Iterator<Map.Entry<WeakKey<K>, V>> iterator;

		@Nullable private Map.Entry<WeakKey<K>, V> nextEntry;

		@Nullable private K nextKey;

		private EntryIterator(Iterator<Map.Entry<WeakKey<K>, V>> iterator) {
			this.iterator = iterator;
			findNext();
		}

		private void findNext() {
			while (this.iterator.hasNext()) {
				this.nextEntry = this.iterator.next();
				this.nextKey = this.nextEntry.getKey().get();
				if (this.nextKey != null) {
					return;
				}
			}
			this.nextEntry = null;
			this.nextKey = null;
		}

		@Override
		public boolean hasNext() {
			return this.nextKey != null;
		}

		@Override
		public Map.Entry<K, V> next() {
			if (this.nextKey == null || this.nextEntry == null) {
				throw new NoSuchElementException();
			}
			try {
				return new SimpleEntry(this.nextKey, this.nextEntry);
			} finally {
				findNext();
			}
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private class SimpleEntry implements Map.Entry<K, V> {

		private final K key;

		final Map.Entry<WeakKey<K>, V> entry;

		private SimpleEntry(@Nonnull K key, @Nonnull Map.Entry<WeakKey<K>, V> entry) {
			this.key = key;
			this.entry = entry;
		}

		@Override
		public K getKey() {
			return this.key;
		}

		@Override
		public V getValue() {
			return this.entry.getValue();
		}

		@Override
		public V setValue(V value) {
			if (value == null) {
				throw new NullPointerException();
			}
			return this.entry.setValue(value);
		}
	}
}
