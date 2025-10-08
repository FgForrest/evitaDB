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

package io.evitadb.store.catalog.model;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Generic read-write catalog header that contains all key information for loading / persisting Evita records to disk.
 * Class is used only for implementation agnostic use-cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class MutableCatalogEntityHeader implements KeyCompressor {
	@Serial private static final long serialVersionUID = 1275193885835671311L;

	/**
	 * Type of the entity - {@link EntitySchema#getName()}.
	 */
	@Getter private final String entityType;
	/**
	 * Contains key index extracted from {@link KeyCompressor} that is necessary for
	 * bootstraping {@link KeyCompressor} used for OffsetIndex deserialization.
	 */
	@Getter private final Map<Integer, Object> idToKeyIndex;
	/**
	 * Reverse lookup index to {@link #idToKeyIndex}
	 */
	private final Map<Object, Integer> keyToIdIndex;
	/**
	 * Sequence used for generating new monotonic ids for registered keys.
	 */
	private final AtomicInteger keySequence;
	/**
	 * Contains information about the number of entities in the collection. Servers for informational purposes.
	 */
	@Getter @Setter private int recordCount;

	public MutableCatalogEntityHeader(String entityType, int recordCount, Map<Integer, Object> keys) {
		this.entityType = entityType;
		this.recordCount = recordCount;
		int peek = 0;
		this.idToKeyIndex = createHashMap(keys.size());
		this.keyToIdIndex = createHashMap(keys.size());
		for (Entry<Integer, Object> entry : keys.entrySet()) {
			this.idToKeyIndex.put(entry.getKey(), entry.getValue());
			this.keyToIdIndex.put(entry.getValue(), entry.getKey());
			if (entry.getKey() > peek) {
				peek = entry.getKey();
			}
		}
		this.keySequence = new AtomicInteger(peek);
	}

	@Nonnull
	@Override
	public Map<Integer, Object> getKeys() {
		return this.idToKeyIndex;
	}

	@Override
	public <T extends Comparable<T>> int getId(@Nonnull T key) {
		return this.keyToIdIndex.computeIfAbsent(key, o -> {
			final int id = this.keySequence.incrementAndGet();
			this.idToKeyIndex.put(id, o);
			return id;
		});
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key) {
		return Optional.ofNullable(this.keyToIdIndex.get(key))
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> T getKeyForId(int id) {
		final Object key = this.idToKeyIndex.get(id);
		Assert.notNull(key, "There is no key for id " + id + "!");
		//noinspection unchecked
		return (T) key;
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
		final Object key = this.idToKeyIndex.get(id);
		//noinspection unchecked
		return (T) key;
	}

	@Override
	public int hashCode() {
		int result = this.entityType.hashCode();
		result = 31 * result + this.idToKeyIndex.hashCode();
		result = 31 * result + this.keyToIdIndex.hashCode();
		result = 31 * result + Integer.hashCode(this.keySequence.get());
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		MutableCatalogEntityHeader that = (MutableCatalogEntityHeader) o;

		if (!this.entityType.equals(that.entityType)) return false;
		if (!this.idToKeyIndex.equals(that.idToKeyIndex)) return false;
		if (!this.keyToIdIndex.equals(that.keyToIdIndex)) return false;
		return this.keySequence.get() == that.keySequence.get();
	}
}
