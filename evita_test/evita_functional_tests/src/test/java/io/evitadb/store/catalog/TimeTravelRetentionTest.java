/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.catalog;

import io.evitadb.store.catalog.TimeTravelRetention.CatalogDataFile;
import io.evitadb.store.catalog.TimeTravelRetention.DataFileInventory;
import io.evitadb.store.catalog.TimeTravelRetention.EntityCollectionDataFile;
import io.evitadb.store.catalog.TimeTravelRetention.GenerationPin;
import io.evitadb.store.catalog.TimeTravelRetention.HorizonDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the survivor rules and the horizon search that bound the disk space time travel may occupy.
 *
 * The survivor rules are shared with {@link ObsoleteFileMaintainer}, which executes them to delete files while these
 * tests evaluate them to predict bytes - the two drifting apart would make the size guard trim history it never
 * actually reclaims.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Time travel retention rules and horizon search")
@Tag(STORAGE)
@Tag(WAL)
class TimeTravelRetentionTest {

	/**
	 * Builds an inventory out of a single catalog data file per index and one collection file per entry.
	 *
	 * @param catalogFiles    catalog data files
	 * @param collectionFiles entity collection data files
	 * @return the assembled inventory
	 */
	@Nonnull
	private static DataFileInventory inventory(
		@Nonnull CatalogDataFile[] catalogFiles,
		@Nonnull EntityCollectionDataFile[] collectionFiles
	) {
		return new DataFileInventory(catalogFiles, collectionFiles);
	}

	@Nested
	@DisplayName("Catalog data file survivor rule")
	class CatalogDataFileRule {

		@Test
		@DisplayName("should reclaim only the files below the index the horizon pins")
		void shouldReclaimOnlyFilesBelowTheHorizonIndex() {
			assertTrue(TimeTravelRetention.isCatalogDataFileObsolete(0, 3));
			assertTrue(TimeTravelRetention.isCatalogDataFileObsolete(2, 3));
			assertFalse(TimeTravelRetention.isCatalogDataFileObsolete(3, 3));
			// a file written after the horizon record - a compaction whose record is not published yet - must stay
			assertFalse(TimeTravelRetention.isCatalogDataFileObsolete(4, 3));
		}
	}

	@Nested
	@DisplayName("Entity collection file survivor rule")
	class EntityCollectionFileRule {

		@Test
		@DisplayName("should reclaim only the files below the generation a live collection pins")
		void shouldReclaimOnlyFilesBelowThePinnedGeneration() {
			final Map<Integer, Integer> pinned = Map.of(1, 2);
			assertTrue(TimeTravelRetention.isEntityCollectionFileObsolete(1, 0, pinned, 5));
			assertTrue(TimeTravelRetention.isEntityCollectionFileObsolete(1, 1, pinned, 5));
			assertFalse(TimeTravelRetention.isEntityCollectionFileObsolete(1, 2, pinned, 5));
			assertFalse(TimeTravelRetention.isEntityCollectionFileObsolete(1, 3, pinned, 5));
		}

		@Test
		@DisplayName("should keep every file of a collection created after the horizon")
		void shouldKeepEveryFileOfCollectionCreatedAfterTheHorizon() {
			// pk 7 is above the watermark of 5, so the collection did not exist yet at the horizon version and every
			// file it has was written later - including its only, live one
			assertFalse(TimeTravelRetention.isEntityCollectionFileObsolete(7, 0, Map.of(1, 2), 5));
			assertFalse(TimeTravelRetention.isEntityCollectionFileObsolete(7, 4, Map.of(1, 2), 5));
		}

		@Test
		@DisplayName("should reclaim every file of a collection dropped before the horizon")
		void shouldReclaimEveryFileOfCollectionDroppedBeforeTheHorizon() {
			// pk 3 sits below the watermark of 5 yet is absent from the horizon header - it existed once and was
			// dropped, so nothing retained can reach any of its files
			assertTrue(TimeTravelRetention.isEntityCollectionFileObsolete(3, 0, Map.of(1, 2), 5));
			assertTrue(TimeTravelRetention.isEntityCollectionFileObsolete(3, 9, Map.of(1, 2), 5));
		}

		@Test
		@DisplayName("should treat the watermark itself as a dropped collection")
		void shouldTreatWatermarkItselfAsDroppedCollection() {
			// the watermark is the last key *assigned*, so a collection carrying it did exist at the horizon
			assertTrue(TimeTravelRetention.isEntityCollectionFileObsolete(5, 0, Map.of(1, 2), 5));
			assertFalse(TimeTravelRetention.isEntityCollectionFileObsolete(6, 0, Map.of(1, 2), 5));
		}
	}

	@Nested
	@DisplayName("Retained byte accounting")
	class RetainedByteAccounting {

		@Test
		@DisplayName("should sum only the files the horizon can still reach")
		void shouldSumOnlyReachableFiles() {
			final DataFileInventory inventory = inventory(
				new CatalogDataFile[]{
					new CatalogDataFile(0, 100L),
					new CatalogDataFile(1, 200L)
				},
				new EntityCollectionDataFile[]{
					new EntityCollectionDataFile(1, 0, 10L),
					new EntityCollectionDataFile(1, 1, 20L),
					// dropped before the horizon - below the watermark, absent from the pin map
					new EntityCollectionDataFile(2, 0, 1_000L),
					// created after the horizon - above the watermark
					new EntityCollectionDataFile(9, 0, 40L)
				}
			);
			final GenerationPin pin = new GenerationPin(1, Map.of(1, 1), 3);
			assertEquals(200L + 20L + 40L, TimeTravelRetention.retainedBytes(inventory, pin));
		}
	}

	@Nested
	@DisplayName("Horizon search")
	class HorizonSearch {

		/**
		 * Builds a synthetic history of `generationCount` generations, each one catalog data file of
		 * `bytesPerGeneration` bytes. Record `i` pins catalog file index `i`, so history bytes at horizon `i` are
		 * exactly `(generationCount - 1 - i) * bytesPerGeneration`.
		 *
		 * @param generationCount    number of generations, one bootstrap record each
		 * @param bytesPerGeneration size of each generation's catalog data file
		 * @return the inventory covering all generations
		 */
		@Nonnull
		private DataFileInventory linearHistory(int generationCount, long bytesPerGeneration) {
			final List<CatalogDataFile> files = new ArrayList<>(generationCount);
			for (int i = 0; i < generationCount; i++) {
				files.add(new CatalogDataFile(i, bytesPerGeneration));
			}
			return inventory(files.toArray(CatalogDataFile[]::new), new EntityCollectionDataFile[0]);
		}

		/**
		 * Resolver for the linear history above - record `i` pins catalog file index `i` and no collections.
		 *
		 * @param recordIndex index of the bootstrap record
		 * @return the generation that record pins
		 */
		@Nonnull
		private GenerationPin linearPin(int recordIndex) {
			return new GenerationPin(recordIndex, Map.of(), 0);
		}

		@Test
		@DisplayName("should leave the horizon alone when the history already fits")
		void shouldLeaveHorizonAloneWhenHistoryFits() {
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				5, this::linearPin, linearHistory(5, 100L), 1_000L
			);
			assertEquals(0, decision.recordIndex());
			assertEquals(400L, decision.retainedHistoryBytes());
			assertEquals(400L, decision.historyBytesBeforeAdvance());
			assertFalse(decision.historyCollapsedToNothing());
		}

		@Test
		@DisplayName("should advance to the oldest generation that still fits the budget")
		void shouldAdvanceToOldestGenerationThatFits() {
			// history at horizon i is (4 - i) * 100 -> 400, 300, 200, 100, 0; a 250 byte budget admits i = 2
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				5, this::linearPin, linearHistory(5, 100L), 250L
			);
			assertEquals(2, decision.recordIndex());
			assertEquals(200L, decision.retainedHistoryBytes());
			assertEquals(400L, decision.historyBytesBeforeAdvance());
			assertFalse(decision.historyCollapsedToNothing());
		}

		@Test
		@DisplayName("should pick the exact boundary rather than the generation below it")
		void shouldPickExactBoundary() {
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				5, this::linearPin, linearHistory(5, 100L), 200L
			);
			assertEquals(2, decision.recordIndex());
			assertEquals(200L, decision.retainedHistoryBytes());
		}

		@Test
		@DisplayName("should give up history entirely and say so when the budget cannot hold one generation")
		void shouldGiveUpHistoryEntirelyWhenBudgetCannotHoldOneGeneration() {
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				5, this::linearPin, linearHistory(5, 100L), 10L
			);
			assertEquals(4, decision.recordIndex());
			assertEquals(0L, decision.retainedHistoryBytes());
			assertEquals(400L, decision.historyBytesBeforeAdvance());
			assertTrue(decision.historyCollapsedToNothing());
		}

		@Test
		@DisplayName("should not report a collapse when there was no history to begin with")
		void shouldNotReportCollapseWithoutHistory() {
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				1, this::linearPin, linearHistory(1, 100L), 0L
			);
			assertEquals(0, decision.recordIndex());
			assertEquals(0L, decision.retainedHistoryBytes());
			assertFalse(decision.historyCollapsedToNothing());
		}

		@Test
		@DisplayName("should collapse consecutive records that share one generation without giving up history")
		void shouldCollapseRecordsSharingOneGeneration() {
			// eight checkpoint records over three generations: 0,0,0 | 1,1,1 | 2,2 - the budget admits one generation
			// of history, which the search must express as the first record of generation 1, not as a record inside it
			final int[] pinnedIndexes = {0, 0, 0, 1, 1, 1, 2, 2};
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				pinnedIndexes.length,
				recordIndex -> new GenerationPin(pinnedIndexes[recordIndex], Map.of(), 0),
				linearHistory(3, 100L),
				100L
			);
			assertEquals(3, decision.recordIndex());
			assertEquals(100L, decision.retainedHistoryBytes());
			assertEquals(200L, decision.historyBytesBeforeAdvance());
		}

		@Test
		@DisplayName("should probe the bootstrap file logarithmically rather than record by record")
		void shouldProbeLogarithmically() {
			final int recordCount = 32_768;
			final AtomicInteger probeCount = new AtomicInteger();
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				recordCount,
				recordIndex -> {
					probeCount.incrementAndGet();
					return linearPin(recordIndex);
				},
				linearHistory(recordCount, 1L),
				10L
			);
			assertEquals(recordCount - 1 - 10, decision.recordIndex());
			assertEquals(10L, decision.retainedHistoryBytes());
			// 2 bracketing reads + ceil(log2(32768)) probes + 1 confirming read - nowhere near a linear scan
			assertTrue(
				probeCount.get() <= 20,
				"the horizon search must stay logarithmic, but it read " + probeCount.get() + " catalog headers"
			);
		}

		@Test
		@DisplayName("should account for collections dropped and created across the searched range")
		void shouldAccountForCollectionLifecycleAcrossTheRange() {
			// generation 0 has collection 1 alive; generation 1 drops it and creates collection 2; generation 2 keeps
			// collection 2 only. Giving up generation 0 must reclaim both the catalog file and collection 1's file.
			final DataFileInventory inventory = inventory(
				new CatalogDataFile[]{
					new CatalogDataFile(0, 10L),
					new CatalogDataFile(1, 10L),
					new CatalogDataFile(2, 10L)
				},
				new EntityCollectionDataFile[]{
					new EntityCollectionDataFile(1, 0, 90L),
					new EntityCollectionDataFile(2, 0, 30L)
				}
			);
			final GenerationPin[] pins = {
				new GenerationPin(0, Map.of(1, 0), 1),
				new GenerationPin(1, Map.of(2, 0), 2),
				new GenerationPin(2, Map.of(2, 0), 2)
			};
			// history at horizon 0 is 110 (catalog files 0 and 1, plus collection 1's file), at horizon 1 it is 10
			final HorizonDecision decision = TimeTravelRetention.resolveHorizon(
				3, recordIndex -> pins[recordIndex], inventory, 50L
			);
			assertEquals(1, decision.recordIndex());
			assertEquals(10L, decision.retainedHistoryBytes());
			assertEquals(110L, decision.historyBytesBeforeAdvance());
		}
	}
}
