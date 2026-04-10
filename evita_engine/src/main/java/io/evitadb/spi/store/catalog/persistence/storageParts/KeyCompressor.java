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

package io.evitadb.spi.store.catalog.persistence.storageParts;

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.spi.store.catalog.exception.CompressionKeyUnknownException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.OptionalInt;

import static io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;

/**
 * A `KeyCompressor` maintains a bidirectional mapping between complex, frequently repeated key objects and compact
 * integer ids. During Kryo serialization the complex key (e.g. an
 * {@link io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey},
 * {@link io.evitadb.api.requestResponse.data.structure.Price.PriceKey}, or
 * {@link io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey}) is replaced with its
 * assigned integer id, drastically reducing the size of serialized entity records when the same keys appear in
 * thousands of entity parts.
 *
 * The integer-to-key mapping is persisted as part of the storage descriptor
 * (see {@link StorageDescriptor#compressedKeys()})
 * so it can be restored when the storage file is reopened. Until the compressor state is flushed to disk any newly
 * assigned ids remain in volatile memory.
 *
 * There are two concrete variants:
 * - {@link io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadOnlyKeyCompressor} — used during
 *   deserialization; throws when an unknown key is requested
 * - {@link io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor} — used during
 *   serialization; allocates new ids for previously unseen keys on demand
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface KeyCompressor extends Serializable {

	/**
	 * Returns a snapshot of the full id → key index that was accumulated in this compressor. The returned map is
	 * used when flushing the compressor state to the {@link StorageDescriptor} so that it can be restored on next
	 * startup. The map is keyed by the integer ids and values are the original key objects.
	 */
	@Nonnull
	Map<Integer, Object> getKeys();

	/**
	 * Returns internal ID that can be used instead of storing complex `key` object during serialization in Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributeKey},
	 * {@link AssociatedDataKey} or {@link PriceKey}
	 */
	<T extends Comparable<T>> int getId(@Nonnull T key) throws CompressionKeyUnknownException;

	/**
	 * Returns internal ID that can be used instead of storing complex `key` object during serialization in Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributeKey},
	 * {@link AssociatedDataKey} or {@link PriceKey}
	 *
	 * Method may return null when no id exists yet and the implementation cannot generate new id.
	 */
	@Nonnull
	<T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key);

	/**
	 * Returns original `key` that is linked to passed integer id that was acquired during deserialization from Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributesContract.AttributeKey},
	 * {@link AssociatedDataKey} or {@link Price.PriceKey}
	 */
	@Nonnull
	<T extends Comparable<T>> T getKeyForId(int id);

	/**
	 * Returns original `key` that is linked to passed integer id that was acquired during deserialization from Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributesContract.AttributeKey},
	 * {@link AssociatedDataKey} or {@link Price.PriceKey}
	 */
	@Nullable
	<T extends Comparable<T>> T getKeyForIdIfExists(int id);

}
