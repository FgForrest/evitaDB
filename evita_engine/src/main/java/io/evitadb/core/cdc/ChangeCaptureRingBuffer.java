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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.buffer.RingBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ring buffer implementation for storing and retrieving {@link ChangeCatalogCapture} objects.
 * This buffer maintains a fixed-size circular array of catalog change captures, automatically
 * discarding the oldest entries when the buffer becomes full.
 *
 * The buffer tracks the effective start and end versions of the catalog changes it contains,
 * allowing clients to query for changes starting from a specific version and index position.
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
class ChangeCaptureRingBuffer<T extends ChangeCapture> extends RingBuffer<T, WalPointer> {


	/**
	 * Constructs a new ring buffer with the specified initial state and size.
	 *
	 * @param effectiveStartCatalogVersion the catalog version of the oldest capture that will be stored in the buffer
	 * @param effectiveStartIndex the index of the oldest capture that will be stored in the buffer
	 * @param effectiveLastCatalogVersion the latest catalog version that is visible in indexes
	 * @param bufferSize the size of the ring buffer (maximum number of captures that can be stored)
	 */
	public ChangeCaptureRingBuffer(
		@Nullable final String catalogName,
		final long effectiveStartCatalogVersion,
		final int effectiveStartIndex,
		long effectiveLastCatalogVersion,
		final int bufferSize,
		@Nonnull Class<T> type
	) {
		super(
			catalogName,
			new WalPointer(effectiveStartCatalogVersion, effectiveStartIndex),
			new WalPointer(effectiveLastCatalogVersion, Integer.MAX_VALUE),
			bufferSize,
			type,
			cc -> new WalPointer(cc.version(), cc.index()),
			WalPointer::compareTo,
			(cc, walPointer) -> {
				int versionComparison = Long.compare(cc.version(), walPointer.version());
				if (versionComparison != 0) {
					return versionComparison;
				}
				return Integer.compare(cc.index(), walPointer.index());
			}
		);
	}

	/**
	 * Returns the effective start catalog version of the buffer.
	 *
	 * @return the effective start catalog version
	 */
	public long getEffectiveStartCatalogVersion() {
		return getEffectiveStart().version();
	}

	/**
	 * Returns the effective start index of the buffer.
	 *
	 * @return the effective start index
	 */
	public int getEffectiveStartIndex() {
		return getEffectiveStart().index();
	}

	/**
	 * Sets the effective start position of the buffer to the specified catalog version and index.
	 *
	 * @param catalogVersion the catalog version to set as the effective end
	 */
	public void setEffectiveLastCatalogVersion(long catalogVersion) {
		setEffectiveEnd(new WalPointer(catalogVersion, Integer.MAX_VALUE));
	}

	/**
	 * Clears all entries in the buffer after the specified catalog version.
	 *
	 * @param catalogVersion the catalog version after which all entries should be cleared
	 */
	public void clearAllAfter(long catalogVersion) {
		clearAllAfter(new WalPointer(catalogVersion + 1L, 0));
	}

	/**
	 * Clears all entries in the buffer until the specified catalog version.
	 *
	 * @param catalogVersion the catalog version until which all entries should be cleared
	 */
	public void clearAllUntil(long catalogVersion) {
		clearAllUntil(new WalPointer(catalogVersion, 0));
	}

	/**
	 * Iterates over all data elements in the buffer that were added since the specified watermark (inclusive)
	 * and applies the provided consumer to each of them.
	 *
	 * @param watermark the watermark since which data elements should be processed
	 * @param dataConsumer the consumer that will process each data element
	 * @throws OutsideScopeException if the watermark is outside the scope of data currently in the buffer
	 */
	@Override
	public void forEachSince(@Nonnull WalPointer watermark, @Nonnull java.util.function.Consumer<T> dataConsumer) throws OutsideScopeException {
		super.forEachSince(watermark, dataConsumer);
	}

	@Nonnull
	@Override
	protected String getRingBufferType() {
		return "ChangeCapture";
	}

}
