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

package io.evitadb.index.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
public class ChainIndexChanges
	implements Serializable, Snapshotable<ChainIndexChanges.ChainIndexChangesMemento> {

	@Serial private static final long serialVersionUID = -1108329020855413122L;
	/**
	 * Default implementation of {@link SortedComparableForwardSeeker} for chainable types. It makes no sense to
	 * attempt to compare two chainable types, so this implementation throws an exception.
	 */
	private static final SortedComparableForwardSeeker THROWING_COMPARABLE_SEEKER = position -> {
		throw new UnsupportedOperationException("Chainable types are not comparable one with another!");
	};
	/**
	 * Reference to the {@link ChainIndex} this data structure is linked to.
	 */
	private final ChainIndex chainIndex;
	/**
	 * Cached aggregation of "chained" results in ascending order - computed as plain aggregation or all record ids
	 * in the histogram from left to right.
	 */
	@Nullable private SortedRecordsSupplier recordIdToPositions;
	/**
	 * Cached aggregation of "chained" results in descending order - computed as plain aggregation or all record ids
	 * in the histogram from right to left.
	 */
	@Nullable private SortedRecordsSupplier recordIdToPositionsReversed;
	/**
	 * Cached {@link UnorderedLookup} that contains all data related to sort along this index.
	 */
	@Nullable private UnorderedLookup unorderedLookup;
	/**
	 * Cached {@link Bitmap} that contains all record ids in ascending order.
	 */
	@Nullable private Bitmap recordIds;

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
	 * Captures the four memoized derived caches by reference into an immutable memento.
	 *
	 * This class carries no authoritative transactional diff state of its own — it is a lazily-computed,
	 * recomputable read cache over the chain index sorted-records views (its own class JavaDoc states all
	 * data here can be safely thrown out and recreated from {@link ChainIndex} data). The genuine per-entity
	 * rollback of the chain index is delivered by the {@code elements}/{@code chains}/{@code predecessors}/
	 * {@code dirty} transactional members of {@link ChainIndex}, each its own {@link Snapshotable}; this
	 * memento merely preserves (or, on restore, restores) the memoized caches across a savepoint.
	 *
	 * A shallow (reference) capture honors the memento-independence invariant because every cached value is
	 * either deeply immutable ({@link SortedRecordsSupplier}) or built from freshly-allocated arrays and
	 * never mutated in place afterwards ({@link UnorderedLookup}, {@link BaseBitmap}); on recompute the field
	 * is reference-reassigned to a new object rather than mutated, so the memento's captured reference can
	 * never be corrupted. The final back reference to {@link ChainIndex} is intentionally excluded from the
	 * memento.
	 *
	 * @return an immutable memento holding the current cache references
	 */
	@Nonnull
	@Override
	public ChainIndexChangesMemento snapshot() {
		return new ChainIndexChangesMemento(
			this.recordIdToPositions,
			this.recordIdToPositionsReversed,
			this.unorderedLookup,
			this.recordIds
		);
	}

	/**
	 * Restores the four memoized derived caches from the given memento, reverting every cache
	 * (in)validation that happened since the memento was produced. Each field is reference-reassigned out of
	 * the memento, so the same memento can be restored repeatedly and stays a faithful snapshot of that
	 * moment. Any caches dropped by this operation re-memoize lazily from the (already-rolled-back)
	 * {@link ChainIndex} on the next supplier read.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same instance
	 */
	@Override
	public void restore(@Nonnull ChainIndexChangesMemento memento) {
		this.recordIdToPositions = memento.recordIdToPositions();
		this.recordIdToPositionsReversed = memento.recordIdToPositionsReversed();
		this.unorderedLookup = memento.unorderedLookup();
		this.recordIds = memento.recordIds();
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
			this.recordIdToPositions = ofNullable(this.chainIndex.getReferenceKey())
				.map(
					referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
						this.chainIndex.predecessors.getId(),
						unorderedLookup.getArray(),
						unorderedLookup.getPositions(),
						recordIds,
						THROWING_COMPARABLE_SEEKER,
						referenceKey
					)
				)
				.orElseGet(
					() -> new SortedRecordsSupplier(
						this.chainIndex.predecessors.getId(),
						unorderedLookup.getArray(),
						unorderedLookup.getPositions(),
						recordIds,
						THROWING_COMPARABLE_SEEKER
					)
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
			this.recordIdToPositionsReversed = ofNullable(this.chainIndex.getReferenceKey())
				.map(
					referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
						this.chainIndex.getId(),
						ArrayUtils.reverse(unorderedLookup.getArray()),
						invert(unorderedLookup.getPositions()),
						recordIds,
						THROWING_COMPARABLE_SEEKER,
						referenceKey
					)
				)
				.orElseGet(
					() -> new SortedRecordsSupplier(
						this.chainIndex.getId(),
						ArrayUtils.reverse(unorderedLookup.getArray()),
						invert(unorderedLookup.getPositions()),
						recordIds,
						THROWING_COMPARABLE_SEEKER
					)
				);
			return this.recordIdToPositionsReversed;
		});
	}

	/**
	 * Immutable memento carrying the four memoized derived caches of {@link ChainIndexChanges}.
	 *
	 * All components are captured and restored by reference (no array/bitmap cloning): they are either deeply
	 * immutable ({@link SortedRecordsSupplier}) or freshly-allocated, never-mutated-in-place values
	 * ({@link UnorderedLookup}, {@link Bitmap}). The owning {@link ChainIndex} back reference is deliberately
	 * not part of the memento because it is final and shared. Any component may be {@code null} when the
	 * corresponding cache was not (yet) computed.
	 *
	 * @param recordIdToPositions         memoized ascending sorted-records supplier, or {@code null}
	 * @param recordIdToPositionsReversed memoized descending sorted-records supplier, or {@code null}
	 * @param unorderedLookup             memoized unordered lookup cache, or {@code null}
	 * @param recordIds                   memoized ascending record-id bitmap, or {@code null}
	 */
	public record ChainIndexChangesMemento(
		@Nullable SortedRecordsSupplier recordIdToPositions,
		@Nullable SortedRecordsSupplier recordIdToPositionsReversed,
		@Nullable UnorderedLookup unorderedLookup,
		@Nullable Bitmap recordIds
	) {
	}

}
