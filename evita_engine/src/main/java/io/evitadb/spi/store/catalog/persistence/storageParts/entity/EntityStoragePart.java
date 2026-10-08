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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import java.util.OptionalInt;

/**
 * Marks {@link StoragePart} implementations that represent a portion of an {@link Entity}'s persistent state.
 * An entity is decomposed into several independently stored parts so that queries that request only a subset of
 * entity data (e.g. only prices, only attributes in a specific locale) incur minimal I/O — only the relevant parts
 * need to be loaded.
 *
 * When designing the granularity of entity parts the following trade-offs apply:
 * - parts that are always fetched together should be stored as one part to avoid multiple seeks
 * - very large parts should be stored separately so that unrelated queries don't pay the deserialization cost
 * - very many tiny parts increase the per-record overhead of the offset-index file format
 *
 * Current concrete implementations include `EntityBodyStoragePart`, `AttributesStoragePart`,
 * `AssociatedDataStoragePart`, `ReferencesStoragePart`, and `PricesStoragePart`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityStoragePart extends StoragePart {

	/**
	 * Returns `true` if the in-memory state of this part has been modified since it was last loaded from or written
	 * to persistent storage. The persistence layer uses this flag to decide whether the part needs to be re-serialized
	 * and written during the next flush.
	 */
	boolean isDirty();

	/**
	 * Returns `true` if this part contains no meaningful data and can be omitted from (or removed from) persistent
	 * storage. For example, an `AttributesStoragePart` that has had all its attributes removed is considered empty
	 * and should not occupy space in the storage file.
	 */
	boolean isEmpty();

	/**
	 * Returns the size in bytes of the serialized (on-disk) form of this storage part, if known. The value is
	 * populated when the part is loaded from persistent storage (where the serialized length is available from the
	 * file offset index record header). When the part has been created purely in memory and not yet written, the
	 * size is unknown and an empty `OptionalInt` is returned.
	 *
	 * @return the serialized size in bytes, or an empty optional when the part has not yet been persisted
	 */
	@Nonnull
	OptionalInt sizeInBytes();

	/**
	 * Returns a copy of this part carrying every field this part carries, including the bookkeeping that never reaches
	 * the disk (the dirty flag, pending key assignments), whose content no later mutation of THIS instance can reach.
	 * Every array and collection the part's mutators write into in place is duplicated; the immutable elements they
	 * hold (references, attribute values, prices, associated data values) are shared.
	 *
	 * The data store buffer keeps a trapped part by reference and hands that very instance to the executors, which
	 * mutate it in place. A savepoint that only put the instance back into its slot would therefore restore nothing;
	 * it restores this pre-image instead, taken before the instance was handed out (see
	 * `DataStoreChanges#getStoragePart`).
	 *
	 * The copy is `O(size of the part)` — one array or set copy per mutable member, no deserialization.
	 *
	 * @return an independent copy of this part's current content
	 */
	@Nonnull
	EntityStoragePart createPreImage();

}
