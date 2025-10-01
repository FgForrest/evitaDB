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

package io.evitadb.dataType.set;

import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * A lazy-initialized delegate for a {@link HashSet}, allowing deferred creation of the underlying set
 * until it is actually needed. This class implements the {@link Set} interface and forwards method calls
 * to the underlying {@link HashSet}.
 *
 * This implementation is useful when the set may not always be used, avoiding unnecessary instantiation
 * and thus saving memory and processing time. The set is initialized with an expected size to optimize
 * memory allocation.
 *
 * @param <K> the type of elements maintained by this set
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class LazyHashSet<K> implements Set<K> {
	private final int expectedSize;
	private HashSet<K> delegate;

	@Override
	public int size() {
		return this.delegate != null ? this.delegate.size() : 0;
	}

	@Override
	public boolean isEmpty() {
		return this.delegate == null || this.delegate.isEmpty();
	}

	@Override
	public boolean contains(@Nullable Object o) {
		return this.delegate != null && this.delegate.contains(o);
	}

	@Nonnull
	@Override
	public Iterator<K> iterator() {
		return this.delegate != null ? this.delegate.iterator() : Set.<K>of().iterator();
	}

	@Nonnull
	@Override
	public Object[] toArray() {
		return this.delegate != null ? this.delegate.toArray() : ArrayUtils.EMPTY_OBJECT_ARRAY;
	}

	@Nonnull
	@Override
	public <T> T[] toArray(@Nonnull T[] a) {
		return this.delegate != null ? this.delegate.toArray(a) : a;
	}

	@Override
	public boolean add(@Nullable K k) {
		return this.getDelegate().add(k);
	}

	@Override
	public boolean remove(@Nullable Object o) {
		return this.delegate != null && this.delegate.remove(o);
	}

	@Override
	public boolean containsAll(@Nonnull Collection<?> c) {
		return (this.delegate != null && this.delegate.containsAll(c)) || c.isEmpty();
	}

	@Override
	public boolean addAll(@Nonnull Collection<? extends K> c) {
		return this.getDelegate().addAll(c);
	}

	@Override
	public boolean retainAll(@Nonnull Collection<?> c) {
		return this.delegate != null && this.delegate.retainAll(c);
	}

	@Override
	public boolean removeAll(@Nonnull Collection<?> c) {
		return this.delegate != null && this.delegate.removeAll(c);
	}

	@Override
	public void clear() {
		if (this.delegate != null) {
			this.delegate.clear();
		}
	}

	@Nonnull
	private HashSet<K> getDelegate() {
		if (this.delegate == null) {
			this.delegate = CollectionUtils.createHashSet(this.expectedSize);
		}
		return this.delegate;
	}

	@Override
	public final boolean equals(@Nullable Object o) {
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
		return this.delegate == null ? "[]" : this.delegate.toString();
	}
}
