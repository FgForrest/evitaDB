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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A lazy-initialized delegate for a {@link HashSet}, allowing deferred
 * creation of the underlying set until it is actually needed. This class
 * implements the {@link Set} interface and forwards method calls to the
 * underlying {@link HashSet}.
 *
 * This implementation is useful when the set may not always be used,
 * avoiding unnecessary instantiation and thus saving memory and
 * processing time. The set is initialized with an expected size to
 * optimize memory allocation.
 *
 * @param <K> the type of elements maintained by this set
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings({"NonFinalFieldReferenceInEquals", "NonFinalFieldReferencedInHashCode"})
@RequiredArgsConstructor
public class LazyHashSet<K> implements Set<K> {
	private final int expectedSize;
	private HashSet<K> delegate;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int size() {
		return this.delegate != null ? this.delegate.size() : 0;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isEmpty() {
		return this.delegate == null || this.delegate.isEmpty();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean contains(@Nullable Object o) {
		return this.delegate != null && this.delegate.contains(o);
	}

	/**
	 * {@inheritDoc}
	 */
	@Nonnull
	@Override
	public Iterator<K> iterator() {
		return this.delegate != null ?
			this.delegate.iterator() : Collections.emptyIterator();
	}

	/**
	 * {@inheritDoc}
	 */
	@Nonnull
	@Override
	public Object[] toArray() {
		return this.delegate != null ?
			this.delegate.toArray() : ArrayUtils.EMPTY_OBJECT_ARRAY;
	}

	/**
	 * {@inheritDoc}
	 */
	@Nonnull
	@Override
	public <T> T[] toArray(@Nonnull T[] a) {
		if (this.delegate != null) {
			return this.delegate.toArray(a);
		}
		if (a.length > 0) {
			a[0] = null;
		}
		return a;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean add(@Nullable K k) {
		return this.getDelegate().add(k);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean remove(@Nullable Object o) {
		return this.delegate != null && this.delegate.remove(o);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean containsAll(@Nonnull Collection<?> c) {
		if (c.isEmpty()) {
			return true;
		}
		return (this.delegate != null && this.delegate.containsAll(c))
			|| c.isEmpty();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean addAll(@Nonnull Collection<? extends K> c) {
		return this.getDelegate().addAll(c);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean retainAll(@Nonnull Collection<?> c) {
		return this.delegate != null && this.delegate.retainAll(c);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean removeAll(@Nonnull Collection<?> c) {
		return this.delegate != null && this.delegate.removeAll(c);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void clear() {
		if (this.delegate != null) {
			this.delegate.clear();
		}
	}

	/**
	 * Returns the underlying HashSet delegate, creating it lazily
	 * if needed.
	 */
	@Nonnull
	private HashSet<K> getDelegate() {
		if (this.delegate == null) {
			this.delegate = CollectionUtils.createHashSet(
				this.expectedSize
			);
		}
		return this.delegate;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (this.delegate == null) {
			return o instanceof Set<?>
				&& ((Set<?>) o).isEmpty();
		}
		return this.delegate.equals(o);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		if (this.delegate != null) {
			return this.delegate.hashCode();
		} else {
			return 0;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Nonnull
	@Override
	public String toString() {
		return this.delegate == null ?
			"[]" : this.delegate.toString();
	}
}
