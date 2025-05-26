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

package io.evitadb.dataType.data;

import io.evitadb.function.TriConsumer;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.io.Serial;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The implementation of {@link DataItem} that allows to capture POJO fields, record fields and maps inside Java objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
public record DataItemMap(
	@Nonnull Map<String, DataItem> childrenIndex
) implements DataItem {
	public static final DataItemMap EMPTY = new DataItemMap(Collections.emptyMap());
	@Serial private static final long serialVersionUID = -4294098994748163813L;

	/**
	 * Method allows to iterate over all items in this array with passed `consumer`. Third argument of the tri-consumer
	 * is a flag `hasNext` - true when the current data item is not the last item in the array.
	 */
	public void forEach(@Nonnull TriConsumer<String, DataItem, Boolean> consumer) {
		final Iterator<Entry<String, DataItem>> it = this.childrenIndex.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<String, DataItem> entry = it.next();
			consumer.accept(entry.getKey(), entry.getValue(), it.hasNext());
		}
	}

	/**
	 * Method allows to iterate over all items in this array with passed `consumer`. Third argument of the tri-consumer
	 * is a flag `hasNext` - true when the current data item is not the last item in the array. The iteration scans
	 * the entries sorted by `String` key.
	 */
	public void forEachSorted(@Nonnull TriConsumer<String, DataItem, Boolean> consumer) {
		final Set<Entry<String, DataItem>> entries = new TreeSet<>(Entry.comparingByKey());
		entries.addAll(this.childrenIndex.entrySet());
		final Iterator<Entry<String, DataItem>> it = entries.iterator();
		while (it.hasNext()) {
			final Entry<String, DataItem> entry = it.next();
			consumer.accept(entry.getKey(), entry.getValue(), it.hasNext());
		}
	}

	@Override
	public void accept(@Nonnull DataItemVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.REFERENCE_SIZE +
			MemoryMeasuringConstants.computeHashMapSize(this.childrenIndex);
	}

	@Override
	public boolean isEmpty() {
		return this.childrenIndex.isEmpty();
	}

	@Nonnull
	public Set<String> getPropertyNames() {
		return this.childrenIndex.keySet();
	}

	@Nullable
	public DataItem getProperty(@Nonnull String propertyName) {
		return this.childrenIndex.get(propertyName);
	}

	/**
	 * Returns count of all properties in this map.
	 */
	public int getPropertyCount() {
		return this.childrenIndex.size();
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.childrenIndex);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		DataItemMap that = (DataItemMap) o;
		return Objects.equals(this.childrenIndex, that.childrenIndex);
	}

	@Nonnull
	@Override
	public String toString() {
		return "{" + this.childrenIndex.entrySet().stream().map(it -> it.getKey() + ": " + it.getValue()).collect(Collectors.joining(", ")) + '}';
	}
}
