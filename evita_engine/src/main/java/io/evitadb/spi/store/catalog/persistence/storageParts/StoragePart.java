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
import io.evitadb.exception.GenericEvitaInternalError;
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
	 * Tells whether this part is a view narrowed by what one read happened to ask for, rather than the whole of what
	 * it claims to represent.
	 *
	 * Parts that can be read narrowed override this. Writing such a part back would not merely store less: it would
	 * store the narrowed view *as the truth*, silently erasing everything the read skipped. The guard therefore
	 * lives at every ingress into the data store rather than at the final serializer alone, so a partial value is
	 * refused before anything retains it by reference.
	 *
	 * Phrased as "is narrowed" rather than "is persistable" so that FALSE - the answer a plain part gives, and the
	 * default a test double gives for a boolean it was never told about - is the harmless one. A guard whose safe
	 * answer is TRUE rejects everything that merely forgot to answer.
	 *
	 * @return true when this part carries only some of what it represents
	 */
	default boolean isNarrowedView() {
		return false;
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

	/**
	 * Refuses a part whose {@link #isNarrowedView()} is true, naming the ingress that rejected it.
	 *
	 * Called at every entrance into the data store - the memory buffers, the transactional overlay and the trapped
	 * change collector alike - rather than at the serializer alone. The serializer is the last barrier and it does
	 * hold, but by the time it runs the offending object has already been retained by reference and replayed to
	 * other readers, so the failure would surface far from whatever produced it.
	 *
	 * @param part    the part about to enter the data store
	 * @param ingress short name of the entrance, used in the error message
	 */
	static void assertPersistable(@Nonnull StoragePart part, @Nonnull String ingress) {
		if (part.isNarrowedView()) {
			throw new GenericEvitaInternalError(
				"Storage part " + part.getClass().getSimpleName() + " with primary key " + part.getStoragePartPK() +
					" carries only part of what it represents and must not enter the data store via " + ingress + "!"
			);
		}
	}

	/**
	 * Shared tail of the {@link #computeUniquePartIdAndSet(KeyCompressor)} implementation for every part whose identity
	 * maps to a single stable primary key. Verifies that a freshly computed id is consistent with any id already
	 * assigned to the part — the two must never differ — and returns it; the caller then stores the returned value into
	 * its own `storagePartPK`. Storing an identical value on a part that was already assigned is a harmless no-op, so
	 * this deliberately does not need to know how the caller writes the field. Centralising the assign-once /
	 * assert-on-recompute invariant here means every part expresses it identically.
	 *
	 * @param computedUniquePartId the id freshly computed from the part identity
	 * @param currentStoragePartPK the id already assigned to the part, or `null` when not yet assigned
	 * @return the {@code computedUniquePartId}, guaranteed consistent with any previously assigned id
	 */
	static long verifyUniquePartId(long computedUniquePartId, @Nullable Long currentStoragePartPK) {
		Assert.isTrue(
			currentStoragePartPK == null || currentStoragePartPK == computedUniquePartId,
			"Unique part ids must never differ!"
		);
		return computedUniquePartId;
	}

}
