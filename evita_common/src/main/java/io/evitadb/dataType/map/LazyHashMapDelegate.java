/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A lazy-initialized delegate for a {@link HashMap}, allowing deferred creation of the underlying map
 * until it is actually needed. This class implements the {@link Map} interface and utilizes
 * Lombok's {@link Delegate} annotation to forward method calls to the underlying {@link HashMap}.
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 *           @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class LazyHashMapDelegate<K, V> implements Map<K, V> {
	private final int expectedSize;
	private HashMap<K, V> delegate;

	@Override
	public int size() {
		return this.delegate != null ? this.delegate.size() : 0;
	}

	@Override
	public boolean isEmpty() {
		return this.delegate == null || this.delegate.isEmpty();
	}

	@Override
	public boolean containsKey(Object key) {
		return this.delegate != null && this.delegate.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return this.delegate != null && this.delegate.containsValue(value);
	}

	@Nullable
	@Override
	public V get(Object key) {
		return this.delegate != null ? this.delegate.get(key) : null;
	}

	@Nullable
	@Override
	public V put(K key, V value) {
		return this.getDelegate().put(key, value);
	}

	@Nullable
	@Override
	public V remove(Object key) {
		return this.delegate != null ? this.delegate.remove(key) : null;
	}

	@Override
	public void putAll(@Nonnull Map<? extends K, ? extends V> m) {
		this.getDelegate().putAll(m);
	}

	@Override
	public void clear() {
		if (this.delegate != null) {
			this.delegate.clear();
		}
	}

	@Nonnull
	@Override
	public Set<K> keySet() {
		return this.delegate != null ? this.delegate.keySet() : Set.of();
	}

	@Nonnull
	@Override
	public Collection<V> values() {
		return this.delegate != null ? this.delegate.values() : List.of();
	}

	@Nonnull
	@Override
	public Set<Entry<K, V>> entrySet() {
		return this.delegate != null ? this.delegate.entrySet() : Set.of();
	}

	@Nonnull
	private HashMap<K, V> getDelegate() {
		if (this.delegate == null) {
			this.delegate = CollectionUtils.createHashMap(this.expectedSize);
		}
		return this.delegate;
	}

	@Override
	public final boolean equals(Object o) {
		return Objects.equals(this.delegate, o);
	}

	@Override
	public int hashCode() {
		if (this.delegate != null) {
			return Objects.hashCode(this.delegate);
		} else {
			return this.expectedSize;
		}
	}

	@Override
	public String toString() {
		return this.delegate == null ? "{}" : this.delegate.toString();
	}
}
