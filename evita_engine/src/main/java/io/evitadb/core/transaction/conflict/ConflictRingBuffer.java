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

package io.evitadb.core.transaction.conflict;


import io.evitadb.core.buffer.RingBuffer;
import io.evitadb.core.transaction.conflict.ConflictRingBuffer.CatalogVersionIndex;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A ring buffer implementation for storing and retrieving {@link VersionedConflictKey} objects.
 * This buffer maintains a fixed-size circular array of versioned conflict keys, automatically
 * discarding the oldest entries when the buffer becomes full.
 *
 * The buffer tracks the effective start and end versions of the conflict keys it contains,
 * allowing clients to query for keys starting from a specific catalog version and index position.
 * When the buffer is full and new items are added, the oldest items are removed, and the
 * effective start version and index are updated accordingly.
 *
 * This implementation uses a {@link ReentrantLock} to ensure thread safety for reading operations.
 * However, writing operations must always be performed from the same thread to maintain consistency.
 *
 * Class is thread safe for reading, but not thread safe for writing (writing must be always done from the same thread).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public class ConflictRingBuffer extends RingBuffer<VersionedConflictKey, CatalogVersionIndex> {

	/**
	 * Constructs a new ring buffer with the specified initial state and size.
	 *
	 * @param effectiveStartCatalogVersion the catalog version of the oldest conflict key that will be stored in the buffer
	 * @param effectiveLastCatalogVersion the latest catalog version that is visible in indexes
	 * @param bufferSize the size of the ring buffer (maximum number of keys that can be stored)
	 */
	public ConflictRingBuffer(
		@Nonnull final String catalogName,
		final long effectiveStartCatalogVersion,
		long effectiveLastCatalogVersion,
		final int bufferSize
	) {
		super(
			catalogName,
			new CatalogVersionIndex(effectiveStartCatalogVersion, 0),
			new CatalogVersionIndex(effectiveLastCatalogVersion, Integer.MAX_VALUE),
			bufferSize,
			VersionedConflictKey.class,
			vck -> new CatalogVersionIndex(vck.version(), vck.index()),
			CatalogVersionIndex::compareTo,
			(vck, cvi) -> {
				int versionComparison = Long.compare(vck.version(), cvi.catalogVersion());
				if (versionComparison != 0) {
					return versionComparison;
				}
				return Integer.compare(vck.index(), cvi.index());
			}
		);
	}

	/**
	 * Sets the effective start position of the buffer to the specified catalog version
	 *
	 * @param catalogVersion the catalog version to set as the effective end
	 */
	public void setEffectiveLastCatalogVersion(long catalogVersion) {
		setEffectiveEnd(new CatalogVersionIndex(catalogVersion, Integer.MAX_VALUE));
	}

	/**
	 * Clears all entries in the conflict ring buffer up to (but not including) the specified catalog version.
	 * Entries are cleared by transforming the catalog version into a {@link CatalogVersionIndex}
	 * and invoking the parent class method to handle buffer clearing.
	 *
	 * @param catalogVersion the catalog version up to which entries in the buffer should be cleared
	 */
	public void clearAllUntil(long catalogVersion) {
		this.clearAllUntil(new CatalogVersionIndex(catalogVersion, 0));
	}

	/**
	 * Clears all entries in the conflict ring buffer that were created after the specified catalog version.
	 * Entries are logically cleared by transforming the catalog version into a {@link CatalogVersionIndex}
	 * and invoking the method in the parent class.
	 *
	 * @param catalogVersion the catalog version after which all entries in the buffer should be cleared
	 */
	public void clearAllAfter(long catalogVersion) {
		this.clearAllAfter(new CatalogVersionIndex(catalogVersion, 0));
	}

	/**
	 * Iterates over all conflict keys in the buffer that were created since the specified catalog version.
	 * The catalog version is transformed into a {@link CatalogVersionIndex} and the parent class method is invoked
	 * to perform the iteration.
	 *
	 * @param catalogVersion the catalog version since which conflict keys should be processed
	 * @param conflictKeyConsumer the consumer that will process each conflict key
	 */
	public void forEachSince(
		long catalogVersion,
		@Nonnull Consumer<VersionedConflictKey> conflictKeyConsumer
	) throws OutsideScopeException {
		this.forEachSince(
			new CatalogVersionIndex(catalogVersion, 0),
			conflictKeyConsumer
		);
	}

	@Nonnull
	@Override
	protected String getRingBufferType() {
		return "Conflict";
	}

	public record CatalogVersionIndex(
		long catalogVersion,
		int index
	) implements Comparable<CatalogVersionIndex> {

		@Override
		public int compareTo(@Nonnull CatalogVersionIndex o) {
			int versionComparison = Long.compare(this.catalogVersion, o.catalogVersion);
			if (versionComparison != 0) {
				return versionComparison;
			}
			return Integer.compare(this.index, o.index);
		}
	}

}
