/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.attribute;

import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;

import static io.evitadb.index.attribute.SortIndex.invert;
import static java.util.Optional.ofNullable;

/**
 * Class contains intermediate computation data structures that speed up access to the {@link SortedRecordsSupplier}
 * implementations and also allow to modify contents of the {@link ChainIndex} data. All data inside this class can be
 * safely thrown out and recreated from {@link ChainIndex} internal data again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ChainIndexChanges implements Serializable {

	@Serial private static final long serialVersionUID = -1108329020855413122L;
	/**
	 * Reference to the {@link ChainIndex} this data structure is linked to.
	 */
	private final ChainIndex chainIndex;
	/**
	 * Cached aggregation of "chained" results in ascending order - computed as plain aggregation or all record ids
	 * in the histogram from left to right.
	 */
	private SortedRecordsSupplier recordIdToPositions;
	/**
	 * Cached aggregation of "chained" results in descending order - computed as plain aggregation or all record ids
	 * in the histogram from right to left.
	 */
	private SortedRecordsSupplier recordIdToPositionsReversed;
	/**
	 * Cached {@link UnorderedLookup} that contains all data related to sort along this index.
	 */
	private UnorderedLookup unorderedLookup;
	/**
	 * Cached {@link Bitmap} that contains all record ids in ascending order.
	 */
	private Bitmap recordIds;

	public ChainIndexChanges(@Nonnull ChainIndex chainIndex) {
		this.chainIndex = chainIndex;
	}

	/**
	 * Resets the internally cached data.
	 */
	public void reset() {
		this.unorderedLookup = null;
		this.recordIds = null;
		this.recordIdToPositions = null;
		this.recordIdToPositionsReversed = null;
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids chained by value in ascending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return ofNullable(this.recordIdToPositions).orElseGet(() -> {
			final UnorderedLookup unorderedLookup = ofNullable(this.unorderedLookup)
				.orElseGet(this.chainIndex::getUnorderedLookup);
			final Bitmap recordIds = ofNullable(this.recordIds)
				.orElseGet(() -> new BaseBitmap(unorderedLookup.getRecordIds()));
			this.recordIdToPositions = new SortedRecordsSupplier(
				this.chainIndex.elementStates.getId(),
				unorderedLookup.getArray(),
				unorderedLookup.getPositions(),
				recordIds
			);
			return this.recordIdToPositions;
		});
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids chained by value in descending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return ofNullable(this.recordIdToPositionsReversed).orElseGet(() -> {
			final UnorderedLookup unorderedLookup = ofNullable(this.unorderedLookup)
				.orElseGet(this.chainIndex::getUnorderedLookup);
			final Bitmap recordIds = ofNullable(this.recordIds)
				.orElseGet(() -> new BaseBitmap(unorderedLookup.getRecordIds()));
			this.recordIdToPositionsReversed = new SortedRecordsSupplier(
				this.chainIndex.getId(),
				ArrayUtils.reverse(unorderedLookup.getArray()),
				invert(unorderedLookup.getPositions()),
				recordIds
			);
			return this.recordIdToPositionsReversed;
		});
	}


}
