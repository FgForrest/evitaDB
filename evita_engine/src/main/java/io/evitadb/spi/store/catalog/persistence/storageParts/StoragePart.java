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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * A `StoragePart` is the fundamental unit of persistence in evitaDB's file offset index. Each implementation
 * represents a self-contained, serializable data container (e.g. an entity body, an attribute index, a price index)
 * that is read and written as an atomic record in the underlying storage file.
 *
 * Uniqueness within a single persistence file is guaranteed by the combination of the concrete implementation class
 * (which determines the record type discriminator) and the value returned by {@link #getStoragePartPK()}.
 *
 * A part that has never been persisted has a `null` primary key. The key is assigned — and set into the part — by
 * calling {@link #computeUniquePartIdAndSet(KeyCompressor)} during the first write. After that the key is stable and
 * immutable for the lifetime of the part.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface StoragePart extends Serializable {

	/**
	 * Returns the primary key that uniquely identifies this storage part among all parts of the same type within the
	 * same persistence file. Returns `null` when the part has been created in memory but has not yet been assigned
	 * a primary key (i.e., before {@link #computeUniquePartIdAndSet(KeyCompressor)} is called for the first time).
	 */
	@Nullable
	Long getStoragePartPK();

	/**
	 * Retrieves the primary key of the storage part or throws an exception if it is not assigned.
	 *
	 * @return the primary key of the storage part
	 * @throws EvitaInternalError if the storage part primary key is not assigned
	 */
	default long getStoragePartPKOrElseThrowException() {
		final Long storagePartPK = getStoragePartPK();
		Assert.isPremiseValid(
			storagePartPK != null,
			"Storage part is expected to be assigned by now."
		);
		return storagePartPK;
	}

	/**
	 * Returns `true` if this storage part has never been written to persistent storage, i.e. its primary key has not
	 * yet been assigned by {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 */
	default boolean isNew() {
		return getStoragePartPK() == null;
	}

	/**
	 * Computes the unique primary key for this storage part and stores it internally so that subsequent calls to
	 * {@link #getStoragePartPK()} return the computed value. Implementations typically derive the key from fields
	 * that logically identify the part (e.g. entity primary key, attribute key, index key), optionally using the
	 * `keyCompressor` to convert complex key objects into compact integer ids before joining the components into a
	 * single `long` via bit manipulation.
	 *
	 * This method is called exactly once by the persistence layer when the part is first written to storage. Calling
	 * it again with a different result would indicate a logic error — implementations should assert consistency.
	 *
	 * @param keyCompressor the compressor used to translate complex key objects into compact integer ids
	 * @return the computed primary key, identical to the value that will henceforth be returned by
	 *         {@link #getStoragePartPK()}
	 */
	long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor);

}
