/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.query.sort;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the interface-default position SPI on {@link SortedRecordsProvider} - {@link SortedRecordsProvider#recordAt},
 * {@link SortedRecordsProvider#positionOf} and the array merge-walk {@link SortedRecordsProvider#resolvePositions}.
 *
 * These defaults only run for a provider that supplies the raw materialized artifacts (record-id order, positions and
 * the record-id bitmap) WITHOUT overriding the positional operations; the production tree-backed and legacy array-backed
 * suppliers both override them, so a hand-rolled minimal provider is used here to reach the default bodies directly. The
 * fixture is derived straight from the worked example in the {@link SortedRecordsProvider} JavaDoc.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@Tag(COMPARATOR)
@DisplayName("SortedRecordsProvider interface defaults")
class SortedRecordsProviderDefaultsTest {

	/**
	 * All record ids in ascending id order (the shape {@link SortedRecordsProvider#getAllRecords()} returns).
	 */
	private static final int[] ALL_RECORDS = {1, 3, 4, 6, 8, 12};

	/**
	 * Position of each id-ordered record within {@link #SORTED_RECORD_IDS} (aligned with {@link #ALL_RECORDS}).
	 */
	private static final int[] RECORD_POSITIONS = {1, 4, 5, 0, 3, 2};

	/**
	 * Record ids in sorted order - position `p` is occupied by `SORTED_RECORD_IDS[p]`.
	 */
	private static final int[] SORTED_RECORD_IDS = {6, 1, 12, 8, 3, 4};

	/**
	 * Shared, read-only defaults-only provider: it implements ONLY the five data getters so every positional operation
	 * falls through to the interface default under test. All test methods read it without mutation, so a single instance
	 * is safe to share across the class.
	 */
	private static final SortedRecordsProvider PROVIDER = defaultsOnlyProvider(
		SORTED_RECORD_IDS, RECORD_POSITIONS, new BaseBitmap(ALL_RECORDS)
	);

	/**
	 * Builds a {@link SortedRecordsProvider} that overrides ONLY the five data getters, leaving `recordAt`, `positionOf`
	 * and `resolvePositions` at their interface defaults so those default bodies are the code under test.
	 *
	 * @param sortedRecordIds record ids in sorted order
	 * @param recordPositions position of each id-ordered record within `sortedRecordIds`
	 * @param allRecords      bitmap of all record ids in ascending id order
	 * @return a provider whose positional operations run the interface defaults
	 */
	@Nonnull
	private static SortedRecordsProvider defaultsOnlyProvider(
		@Nonnull int[] sortedRecordIds,
		@Nonnull int[] recordPositions,
		@Nonnull Bitmap allRecords
	) {
		return new SortedRecordsProvider() {

			@Override
			public int getRecordCount() {
				return sortedRecordIds.length;
			}

			@Nonnull
			@Override
			public Bitmap getAllRecords() {
				return allRecords;
			}

			@Nonnull
			@Override
			public int[] getRecordPositions() {
				return recordPositions;
			}

			@Nonnull
			@Override
			public int[] getSortedRecordIds() {
				return sortedRecordIds;
			}

			@Nonnull
			@Override
			public SortedComparableForwardSeeker getSortedComparableForwardSeeker() {
				return SortedComparableForwardSeeker.EMPTY;
			}
		};
	}

	@Test
	@DisplayName("recordAt resolves each sorted position through the array-index default")
	void shouldReturnRecordAtPositionViaDefault() {
		for (int position = 0; position < SORTED_RECORD_IDS.length; position++) {
			assertEquals(
				SORTED_RECORD_IDS[position], PROVIDER.recordAt(position),
				"recordAt must index the sorted-record-id array at position " + position
			);
		}
	}

	@Test
	@DisplayName("positionOf composes the all-records index with the position array for present records")
	void shouldReturnPositionOfPresentRecordViaDefault() {
		// each record id maps to its sorted position via getAllRecords().indexOf -> getRecordPositions()
		assertEquals(0, PROVIDER.positionOf(6), "record 6 sits at sorted position 0");
		assertEquals(1, PROVIDER.positionOf(1), "record 1 sits at sorted position 1");
		assertEquals(2, PROVIDER.positionOf(12), "record 12 sits at sorted position 2");
		assertEquals(3, PROVIDER.positionOf(8), "record 8 sits at sorted position 3");
		assertEquals(4, PROVIDER.positionOf(3), "record 3 sits at sorted position 4");
		assertEquals(5, PROVIDER.positionOf(4), "record 4 sits at sorted position 5");
	}

	@Test
	@DisplayName("positionOf returns the not-found sentinel for records absent from the provider")
	void shouldReturnPositionNotFoundForAbsentRecordViaDefault() {
		// 5, 7 and 99 are not part of ALL_RECORDS, so the all-records index misses and the default returns the sentinel
		assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, PROVIDER.positionOf(5), "record 5 is absent");
		assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, PROVIDER.positionOf(7), "record 7 is absent");
		assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, PROVIDER.positionOf(99), "record 99 is absent");
	}

	@Test
	@DisplayName("resolvePositions merge-walks the arrays into an ascending mask and a not-found hand-off")
	void shouldResolvePositionsViaArrayMergeWalkDefault() {
		// select three present records (3, 6, 12) mixed with two absent ones (5, 99), in ascending id order
		final int[] selectedAll = {3, 5, 6, 12, 99};
		final PersistentRoaringBitmap selected =
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll));
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];

		final PositionResolution resolution =
			PROVIDER.resolvePositions(selected, selectedAll.length, bufferA, bufferB);

		// present records resolve to positions 6 -> 0, 12 -> 2, 3 -> 4; the mask keeps them ascending
		assertArrayEquals(
			new int[]{0, 2, 4}, new BaseBitmap(resolution.mask()).getArray(),
			"the mask must hold the ascending sorted positions of the present selection"
		);
		// the two absent ids are handed off untouched
		assertArrayEquals(
			new int[]{5, 99}, new BaseBitmap(resolution.notFoundRecords()).getArray(),
			"the not-found hand-off must be exactly the absent ids"
		);
		assertEquals(2, resolution.notFoundRecordsCount(), "the not-found count must match the absent id count");
	}

	@Test
	@DisplayName("resolvePositions finds nothing for an empty selection whatever the scratch buffers held before")
	void shouldResolveNothingForEmptySelectionWithDirtyBuffers() {
		// pooled buffers keep what their previous borrower wrote - here the provider's first record id, which is
		// exactly what the walk compares its first record against
		final PositionResolution resolution = PROVIDER.resolvePositions(
			new PersistentRoaringBitmap(), 0, dirtyBuffer(512, ALL_RECORDS[0]), dirtyBuffer(512, ALL_RECORDS[0])
		);

		assertAll(
			() -> assertArrayEquals(
				new int[0], new BaseBitmap(resolution.mask()).getArray(),
				"an empty selection must not match the stale contents of the selection buffer"
			),
			() -> assertArrayEquals(new int[0], new BaseBitmap(resolution.notFoundRecords()).getArray()),
			() -> assertEquals(0, resolution.notFoundRecordsCount())
		);
	}

	@Test
	@DisplayName("mirrored merge-walk finds nothing for an empty selection whatever the scratch buffers held before")
	void shouldResolveNothingForEmptySelectionWithDirtyBuffersWhenMirrored() {
		// the descending share of the ascending positions runs the very same walk
		final PositionResolution resolution = SortedRecordsProvider.resolvePositionsByArrayMergeWalk(
			new BaseBitmap(ALL_RECORDS), RECORD_POSITIONS, new PersistentRoaringBitmap(), 0,
			dirtyBuffer(512, ALL_RECORDS[0]), dirtyBuffer(512, ALL_RECORDS[0]), true, ALL_RECORDS.length - 1
		);

		assertAll(
			() -> assertArrayEquals(new int[0], new BaseBitmap(resolution.mask()).getArray()),
			() -> assertArrayEquals(new int[0], new BaseBitmap(resolution.notFoundRecords()).getArray()),
			() -> assertEquals(0, resolution.notFoundRecordsCount())
		);
	}

	@Test
	@DisplayName("resolvePositions resolves a strict subset across batch boundaries with dirty scratch buffers")
	void shouldResolveStrictSubsetAcrossBatchesWithDirtyBuffers() {
		// two-slot buffers split both sides into several batches, and each buffer starts out full of record ids of
		// the provider, so any read past a batch's end would produce a match
		final int[] selectedAll = {3, 8};
		final PositionResolution resolution = PROVIDER.resolvePositions(
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll)), selectedAll.length,
			dirtyBuffer(2, 4), dirtyBuffer(2, 4)
		);

		assertAll(
			// 8 -> 3, 3 -> 4
			() -> assertArrayEquals(new int[]{3, 4}, new BaseBitmap(resolution.mask()).getArray()),
			() -> assertArrayEquals(new int[0], new BaseBitmap(resolution.notFoundRecords()).getArray()),
			() -> assertEquals(0, resolution.notFoundRecordsCount())
		);
	}

	@Test
	@DisplayName("resolvePositions hands off absent ids after the last batch with dirty scratch buffers")
	void shouldHandOffAbsentIdsAfterLastBatchWithDirtyBuffers() {
		// the selection outlives the provider's records: 5 falls between two batches, 13 lies past the last record,
		// and the buffers hold the provider's record 12 from a previous borrower
		final int[] selectedAll = {4, 5, 13};
		final PositionResolution resolution = PROVIDER.resolvePositions(
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll)), selectedAll.length,
			dirtyBuffer(2, 12), dirtyBuffer(2, 12)
		);

		assertAll(
			// 4 -> 5
			() -> assertArrayEquals(new int[]{5}, new BaseBitmap(resolution.mask()).getArray()),
			() -> assertArrayEquals(new int[]{5, 13}, new BaseBitmap(resolution.notFoundRecords()).getArray()),
			() -> assertEquals(2, resolution.notFoundRecordsCount())
		);
	}

	/**
	 * Creates a scratch buffer as a pool hands it out after a previous use: full of a value that is not the
	 * caller's.
	 *
	 * @param length     the length of the buffer
	 * @param staleValue the value every slot holds
	 * @return the buffer
	 */
	@Nonnull
	private static int[] dirtyBuffer(int length, int staleValue) {
		final int[] buffer = new int[length];
		Arrays.fill(buffer, staleValue);
		return buffer;
	}

}
