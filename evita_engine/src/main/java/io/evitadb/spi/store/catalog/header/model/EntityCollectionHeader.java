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

package io.evitadb.spi.store.catalog.header.model;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Persistent header snapshot of a single entity collection.
 *
 * The header contains only lightweight metadata that the engine needs to open a collection and
 * reconstruct in-memory structures without scanning entity bodies. Implementations are
 * storage-specific, but the engine depends only on this contract so that the business logic is
 * decoupled from concrete persistence backends.
 *
 * The header typically maintains counters and identifiers that allow the engine to:
 *
 * - generate new primary keys and other internal identifiers
 * - reference storage parts and indexes via compact numeric keys
 * - reconstruct the `KeyCompressor` state through {@link #compressedKeys()}
 *
 * All methods are designed to be fast and allocation-light.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EntityCollectionHeader extends StoragePart, Serializable {

	@Nonnull
	@Override
	Long getStoragePartPK();

	/**
	 * Computes a globally unique persistent identifier for this header and sets it into the instance.
	 *
	 * The implementation may use the provided `KeyCompressor` to register any keys needed to produce
	 * a unique, compact identifier and to populate {@link #compressedKeys()} so that the state can be
	 * restored during the next load.
	 *
	 * @param keyCompressor non-null compressor used to allocate or resolve compact integer keys
	 * @return the assigned unique part id
	 */
	long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor);

	/**
	 * Monotonic version of this header. The version is increased whenever the header metadata changes
	 * and can be used for optimistic concurrency, WAL ordering, or cache invalidation.
	 *
	 * @return non-negative version number
	 */
	long version();

	/**
	 * Mapping of compact integer keys to their original objects required to bootstrap the
	 * {@link KeyCompressor} on load. The exact value types are storage-specific; the map captures the
	 * minimal state necessary to restore deterministic key assignments across restarts without
	 * scanning the storage.
	 *
	 * @return non-null map of key id to original key objects (may be empty)
	 */
	@Nonnull
	Map<Integer, Object> compressedKeys();

	/**
	 * Logical name/type of the entity collection (e.g. {@link EntitySchemaContract#getName()}). The value is stable
	 * within a catalog and is mapped to a compact integer via {@link #entityTypePrimaryKey()} for efficient persistence.
	 *
	 * @return non-null entity type name
	 */
	@Nonnull
	String entityType();

	/**
	 * Internal integer key assigned to {@link #entityType()} in the catalog. This compact surrogate is
	 * used across storage parts to avoid repeated string serialization while remaining unique within
	 * a catalog.
	 *
	 * @return non-negative internal type id
	 */
	int entityTypePrimaryKey();

	/**
	 * Number of entity records currently present in this collection. The exact semantics (e.g. whether
	 * soft-deleted records are included) are defined by the storage implementation, but the value is
	 * intended for fast statistics and sanity checks.
	 *
	 * @return non-negative count of stored entity records
	 */
	int recordCount();

	/**
	 * Highest entity primary key that has been allocated in this collection. The next auto-generated
	 * primary key is typically `lastPrimaryKey() + 1`.
	 *
	 * @return non-negative highest allocated entity primary key
	 */
	int lastPrimaryKey();

	/**
	 * Highest allocated primary key for per-entity index storage parts. Used to generate new index
	 * identifiers without scanning storage.
	 *
	 * @return non-negative highest allocated index primary key
	 */
	int lastEntityIndexPrimaryKey();

	/**
	 * Highest allocated internal price identifier used by the price indexing subsystem. New price ids
	 * are typically generated by incrementing this value.
	 *
	 * @return non-negative highest allocated internal price id
	 */
	int lastInternalPriceId();

	/**
	 * Descriptor of the underlying storage layer needed to read/write the collection. The descriptor is
	 * storage-implementation specific and may be `null` if the collection hasn't been stored yet.
	 *
	 * @return optional storage descriptor
	 */
	@Nullable
	StorageDescriptor storageDescriptor();

	/**
	 * Primary key of the global entity index storage part, if present. The global index aggregates
	 * data for the entire collection (as opposed to reduced indexes).
	 *
	 * @return optional primary key of the global entity index
	 */
	@Nullable
	Integer globalEntityIndexPrimaryKey();

	/**
	 * Primary keys of all currently allocated index storage parts. This allows the engine to
	 * reconstruct index structures without scanning the storage.
	 *
	 * @return non-null list of allocated index primary keys (may be empty)
	 */
	@Nonnull
	List<Integer> usedEntityIndexPrimaryKeys();

	/**
	 * Highest numeric key identifier used by the {@link KeyCompressor} in this header. This allows the
	 * compressor to resume id allocation deterministically after a restart.
	 *
	 * @return non-negative highest allocated key id
	 */
	int lastKeyId();

}
