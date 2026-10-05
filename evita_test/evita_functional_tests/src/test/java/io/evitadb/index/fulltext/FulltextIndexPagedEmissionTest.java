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

package io.evitadb.index.fulltext;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the paged emission of {@link FulltextIndex}'s dictionary — the dirty flag that gates a flush, the pages
 * {@link FulltextIndex#collectChangedPages()} emits and frees, and the page bookkeeping across warm-up flushes, warm-up
 * savepoint rollbacks and commits.
 *
 * Every flush is applied to a {@link SimulatedDisk}: changed pages are written, freed pages removed. After each flush
 * the disk must hold exactly the live page list, and its pages read in that order must reproduce the dictionary —
 * keys, postings and the impacts aligned with them. A page the flush failed to free stays on that disk and breaks
 * both checks; it is the overlapping-leaf-page corruption a cold load would hit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext index paged emission")
@Tag(INDEXING)
@Tag(FULLTEXT)
class FulltextIndexPagedEmissionTest {

	/**
	 * Number of distinct synthetic terms per field - with two fields well over a dozen dictionary leaves of 256.
	 */
	private static final int TERM_COUNT = 2_000;

	/**
	 * Exclusive upper bound of the primary keys used.
	 */
	private static final int PK_BOUND = 50;

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;

	@BeforeAll
	static void setUpRegistry() {
		registry = new FulltextAnalyzerRegistry();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	/**
	 * Creates an empty index with two registered fields, ids 0 and 1. The registrations leave it dirty.
	 *
	 * @return the index
	 */
	@Nonnull
	private static FulltextIndex newIndex() {
		final FulltextIndex index = new FulltextIndex(
			registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"))
		);
		index.getOrAssignFieldId(attribute("title"));
		index.getOrAssignFieldId(attribute("body"));
		return index;
	}

	/**
	 * Returns the synthetic term of the passed number.
	 *
	 * @param number the number
	 * @return the term
	 */
	@Nonnull
	private static String term(int number) {
		return String.format("t%05d", number);
	}

	/**
	 * Adds random postings, each with a random impact.
	 *
	 * @param random the source of randomness
	 * @param index  the index
	 * @param count  how many postings to add
	 */
	private static void addRandom(@Nonnull Random random, @Nonnull FulltextIndex index, int count) {
		for (int i = 0; i < count; i++) {
			index.addPosting(
				random.nextInt(2), term(random.nextInt(TERM_COUNT)), random.nextInt(PK_BOUND), 1 + random.nextInt(255)
			);
		}
	}

	/**
	 * Removes every posting of a contiguous range of terms in both fields - which empties whole leaves, so their
	 * neighbours merge.
	 *
	 * @param index the index
	 * @param from  first term number, inclusive
	 * @param to    last term number, exclusive
	 */
	private static void removeRange(@Nonnull FulltextIndex index, int from, int to) {
		for (int fieldId = 0; fieldId < 2; fieldId++) {
			for (int number = from; number < to; number++) {
				for (int pk = 0; pk < PK_BOUND; pk++) {
					index.removePosting(fieldId, term(number), pk);
				}
			}
		}
	}

	/**
	 * Flushes the index into the disk when it is dirty, the way a flush gates on the flag.
	 *
	 * @param index the index
	 * @param disk  the disk
	 * @return the emission, or null when the index was clean
	 */
	private static PageEmission<DictionaryPage> flush(@Nonnull FulltextIndex index, @Nonnull SimulatedDisk disk) {
		if (!index.isDirty()) {
			return null;
		}
		final PageEmission<DictionaryPage> emission = index.collectChangedPages();
		disk.apply(emission);
		index.resetDirty();
		return emission;
	}

	/**
	 * Returns the dictionary as the index reads it: one line per key, with its postings and their impacts.
	 *
	 * @param index the index
	 * @return the lines in key order
	 */
	@Nonnull
	private static List<String> dictionaryOf(@Nonnull FulltextIndex index) {
		final List<String> lines = new ArrayList<>(4096);
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			final int theFieldId = fieldId;
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				lines.add(line(FulltextTermKeys.encode(theFieldId, term), postings.getArray(), impacts.toArray()));
				return true;
			});
		}
		return lines;
	}

	/**
	 * Formats one dictionary entry.
	 *
	 * @param key      the encoded key
	 * @param postings the postings
	 * @param impacts  their impacts
	 * @return the line
	 */
	@Nonnull
	private static String line(@Nonnull String key, @Nonnull int[] postings, @Nonnull byte[] impacts) {
		return key + " " + Arrays.toString(postings) + " " + Arrays.toString(impacts);
	}

	/**
	 * The pages a sequence of flushes left on disk, keyed by page sequence.
	 */
	private static final class SimulatedDisk {
		/**
		 * The pages on disk.
		 */
		private final Map<Integer, DictionaryPage> pages = new TreeMap<>();
		/**
		 * The page list of the last flush, in key order.
		 */
		private int[] orderedPageSequences = new int[0];
		/**
		 * How many pages the flushes freed in total.
		 */
		private int freedTotal;
		/**
		 * The high-water page sequence of the last flush.
		 */
		private int highWater = -1;

		/**
		 * Applies one flush: writes the changed pages, removes the freed ones, and checks the disk holds exactly the
		 * live page list.
		 *
		 * @param emission the flush's emission
		 */
		void apply(@Nonnull PageEmission<DictionaryPage> emission) {
			for (final DictionaryPage page : emission.changedPages()) {
				this.pages.put(page.pageSequence(), page);
			}
			for (final int freed : emission.freedPageSequences()) {
				assertNotNull(this.pages.remove(freed), "A freed page must have been on disk: " + freed);
				this.freedTotal++;
			}
			this.orderedPageSequences = emission.orderedPageSequences();
			this.highWater = emission.highWaterPageSequence();
			final Set<Integer> live = new HashSet<>();
			for (final int sequence : this.orderedPageSequences) {
				assertTrue(live.add(sequence), "A page is listed twice: " + sequence);
			}
			assertEquals(live, this.pages.keySet(), "The disk must hold exactly the live pages - no stale page left.");
		}

		/**
		 * Reads the disk the way a cold load would: the listed pages in order, concatenated.
		 *
		 * @return the dictionary lines in page order
		 */
		@Nonnull
		List<String> read() {
			final List<String> lines = new ArrayList<>(4096);
			String previousKey = null;
			for (final int sequence : this.orderedPageSequences) {
				final DictionaryPage page = this.pages.get(sequence);
				assertEquals(page.buckets().length, page.impacts().length, "One impact array per bucket.");
				for (int i = 0; i < page.buckets().length; i++) {
					final ValueToRecord bucket = page.buckets()[i];
					final String key = (String) bucket.getValue();
					if (previousKey != null) {
						assertTrue(previousKey.compareTo(key) < 0, "Pages overlap at " + previousKey + " / " + key);
					}
					previousKey = key;
					lines.add(line(key, bucket.getRecordIds().getArray(), page.impacts()[i]));
				}
			}
			return lines;
		}

		/**
		 * Returns the listed pages in list order - what a cold load reads.
		 *
		 * @return the pages
		 */
		@Nonnull
		DictionaryPage[] listedPages() {
			final DictionaryPage[] result = new DictionaryPage[this.orderedPageSequences.length];
			for (int i = 0; i < result.length; i++) {
				result[i] = this.pages.get(this.orderedPageSequences[i]);
			}
			return result;
		}

		/**
		 * Loads the index back from this disk, with the registry of the passed index.
		 *
		 * @param index the index whose field registry to restore
		 * @return the reloaded index
		 */
		@Nonnull
		FulltextIndex reload(@Nonnull FulltextIndex index) {
			final List<FulltextIndex.Field> fields = new ArrayList<>(index.getFieldCount());
			for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
				fields.add(
					new FulltextIndex.Field(
						index.getFieldKey(fieldId), index.getLengthPivot(fieldId), index.isFieldRetired(fieldId),
						index.getFieldLengths(fieldId)
					)
				);
			}
			return FulltextIndex.fromPersistedPages(
				registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs")),
				FulltextIndex.DEFAULT_LENGTH_PIVOT,
				fields,
				this.orderedPageSequences,
				listedPages(),
				this.highWater
			);
		}

		/**
		 * Asserts the disk reproduces the index.
		 *
		 * @param index the index
		 */
		void assertHolds(@Nonnull FulltextIndex index) {
			assertEquals(dictionaryOf(index), read(), "The disk must reproduce the dictionary.");
		}

	}

	@Nested
	@DisplayName("Dirty flag")
	class DirtyFlag {

		@Test
		@DisplayName("A fresh index is clean; a field registration and every write dirty it; a reset clears it")
		void shouldTrackWritesSinceTheLastFlush() {
			final FulltextIndex index = new FulltextIndex(
				registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"))
			);
			assertFalse(index.isDirty());

			index.getOrAssignFieldId(attribute("title"));
			assertTrue(index.isDirty(), "A new field changes what the index persists.");
			index.resetDirty();

			index.getOrAssignFieldId(attribute("title"));
			assertFalse(index.isDirty(), "Looking up an existing field writes nothing.");

			index.addValue(attribute("title"), 1, "horské kolo");
			assertTrue(index.isDirty());
			index.resetDirty();

			index.removePosting(0, "nonexistent", 1);
			assertTrue(index.isDirty(), "A write that changes nothing still dirties - a harmless over-emission.");
		}

		@Test
		@DisplayName("A rolled-back transaction leaves the index clean")
		@Tag(TRANSACTION)
		void shouldStayCleanAfterARolledBackTransaction() {
			final FulltextIndex index = newIndex();
			index.resetDirty();
			assertStateAfterRollback(
				index,
				t -> {
					t.addPosting(0, term(1), 1, 10);
					assertTrue(t.isDirty(), "The transaction sees its own write.");
				},
				(original, committed) -> assertFalse(original.isDirty())
			);
		}

	}

	@Nested
	@DisplayName("Warm-up flushes")
	class WarmUpFlushes {

		@Test
		@DisplayName("The first flush writes every leaf in a dense page sequence")
		void shouldWriteEveryLeafOnTheFirstFlush() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(1), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();

			final PageEmission<DictionaryPage> emission = flush(index, disk);

			assertNotNull(emission);
			final int[] ordered = emission.orderedPageSequences();
			assertTrue(ordered.length > 4, "The dictionary must span several leaves, it spans " + ordered.length);
			for (int i = 0; i < ordered.length; i++) {
				assertEquals(i, ordered[i]);
			}
			assertEquals(ordered.length, emission.changedPages().size());
			disk.assertHolds(index);
		}

		@Test
		@DisplayName("An unchanged dictionary rewrites nothing; a change rewrites only its leaf")
		void shouldRewriteOnlyChangedLeaves() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(2), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);

			final PageEmission<DictionaryPage> unchanged = index.collectChangedPages();
			assertTrue(unchanged.changedPages().isEmpty());
			assertEquals(0, unchanged.freedPageSequences().length);

			// the smallest key of field 0 lives in the first leaf
			index.addPosting(0, "a", 7, 99);
			final PageEmission<DictionaryPage> changed = flush(index, disk);
			assertNotNull(changed);
			assertEquals(1, changed.changedPages().size());
			assertEquals(0, changed.changedPages().get(0).pageSequence());
			disk.assertHolds(index);
		}

		@Test
		@DisplayName("A leaf merge between warm-up flushes frees the dropped page, though no commit ever publishes")
		void shouldFreeMergedPagesAcrossWarmUpFlushes() {
			final FulltextIndex index = newIndex();
			final Random random = new Random(3);
			addRandom(random, index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final int pagesBefore = disk.pages.size();

			// warm-up never reaches a commit merge, so only publishPreviousFlush moves the baseline: without it the
			// dropped pages would stay on the simulated disk and on its page list
			removeRange(index, 0, 1_200);
			final PageEmission<DictionaryPage> shrunk = flush(index, disk);

			assertNotNull(shrunk);
			assertTrue(shrunk.freedPageSequences().length > 0, "Emptied leaves must free their pages.");
			assertTrue(disk.pages.size() < pagesBefore);
			disk.assertHolds(index);
		}

		@Test
		@DisplayName("Random growth and shrinkage over many warm-up flushes keeps the disk equal to the dictionary")
		void shouldKeepTheDiskEqualToTheDictionary() {
			final FulltextIndex index = newIndex();
			final Random random = new Random(4);
			final SimulatedDisk disk = new SimulatedDisk();
			for (int round = 0; round < 40; round++) {
				if (random.nextInt(3) == 0) {
					final int from = random.nextInt(TERM_COUNT);
					removeRange(index, from, Math.min(TERM_COUNT, from + random.nextInt(600)));
				} else {
					addRandom(random, index, 1 + random.nextInt(3_000));
				}
				flush(index, disk);
				disk.assertHolds(index);
			}
			assertTrue(disk.freedTotal > 0, "The sequence must have exercised leaf merges.");
		}

	}

	@Nested
	@DisplayName("Warm-up savepoint rollback")
	class WarmUpSavepointRollback {

		@Test
		@DisplayName("Pages flushed after a rolled-back savepoint hold the restored dictionary")
		void shouldFlushTheRestoredDictionary() {
			final FulltextIndex index = newIndex();
			final Random random = new Random(5);
			addRandom(random, index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final List<String> before = dictionaryOf(index);

			// splits and merges inside the savepoint, all of it rolled back
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			try {
				addRandom(random, index, 4_000);
				removeRange(index, 300, 900);
			} finally {
				savepoint.rollback();
			}
			assertEquals(before, dictionaryOf(index), "The rollback must restore the dictionary.");

			flush(index, disk);
			disk.assertHolds(index);

			// and the bookkeeping keeps working after it
			addRandom(random, index, 2_000);
			removeRange(index, 1_000, 1_500);
			flush(index, disk);
			disk.assertHolds(index);
		}

		@Test
		@DisplayName("A savepoint rolled back between two flushes leaves no stale page behind")
		void shouldLeaveNoStalePageAfterARollbackBetweenFlushes() {
			final FulltextIndex index = newIndex();
			final Random random = new Random(6);
			final SimulatedDisk disk = new SimulatedDisk();
			for (int round = 0; round < 20; round++) {
				addRandom(random, index, 1 + random.nextInt(2_000));
				final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
				try {
					final int from = random.nextInt(TERM_COUNT);
					removeRange(index, from, Math.min(TERM_COUNT, from + random.nextInt(500)));
					addRandom(random, index, random.nextInt(1_000));
				} finally {
					if (random.nextBoolean()) {
						savepoint.rollback();
					} else {
						savepoint.commit();
					}
				}
				flush(index, disk);
				disk.assertHolds(index);
			}
		}

	}

	@Nested
	@DisplayName("Reload")
	class Reload {

		@Test
		@DisplayName("A reloaded dictionary equals the flushed one, and its first flush writes nothing")
		void shouldReloadBoundaryStable() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(9), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);

			final FulltextIndex reloaded = disk.reload(index);

			assertEquals(dictionaryOf(index), dictionaryOf(reloaded));
			assertFalse(reloaded.isDirty(), "A reloaded index is clean.");
			final PageEmission<DictionaryPage> first = reloaded.collectChangedPages();
			assertTrue(first.changedPages().isEmpty(), "Every leaf kept its page and none is dirty.");
			assertEquals(0, first.freedPageSequences().length);
			assertArrayEquals(disk.orderedPageSequences, first.orderedPageSequences());
			assertEquals(disk.highWater, first.highWaterPageSequence());
		}

		@Test
		@DisplayName("A reloaded index takes writes with impacts, splits and merges, and flushes them correctly")
		void shouldKeepWorkingAfterAReload() {
			final Random random = new Random(10);
			final FulltextIndex index = newIndex();
			addRandom(random, index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);

			FulltextIndex current = disk.reload(index);
			for (int round = 0; round < 10; round++) {
				final int from = random.nextInt(TERM_COUNT);
				removeRange(current, from, Math.min(TERM_COUNT, from + random.nextInt(600)));
				addRandom(random, current, random.nextInt(3_000));
				flush(current, disk);
				disk.assertHolds(current);
				current = disk.reload(current);
				assertEquals(dictionaryOf(current), disk.read());
			}
		}

		@Test
		@DisplayName("Every record tier reloads with its impacts - single, array and a bitmap over several containers")
		void shouldReloadEveryRecordTier() {
			final FulltextIndex index = newIndex();
			index.addPosting(0, "single", 5, 17);
			for (int pk = 0; pk < 40; pk++) {
				index.addPosting(0, "array", pk * 3, 1 + pk);
			}
			// above the array tier's threshold, and spread over two roaring containers
			for (int pk = 0; pk < 200; pk++) {
				index.addPosting(0, "bitmap", pk, 1 + pk % 255);
				index.addPosting(0, "bitmap", 70_000 + pk, 255 - pk % 200);
			}
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);

			final FulltextIndex reloaded = disk.reload(index);

			assertEquals(dictionaryOf(index), dictionaryOf(reloaded));
			assertArrayEquals(index.getImpacts(0, "bitmap"), reloaded.getImpacts(0, "bitmap"));
			// the reloaded buckets keep working in every tier
			reloaded.addPosting(0, "bitmap", 70_500, 99);
			reloaded.removePosting(0, "array", 0);
			reloaded.addPosting(0, "single", 6, 33);
			flush(reloaded, disk);
			disk.assertHolds(reloaded);
		}

		@Test
		@DisplayName("An empty dictionary is persisted as one empty page and reloads as such")
		void shouldReloadAnEmptyDictionary() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(11), index, 3_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			removeRange(index, 0, TERM_COUNT);
			flush(index, disk);
			assertEquals(1, disk.orderedPageSequences.length, "An empty dictionary keeps its root leaf's page.");
			assertEquals(0, disk.listedPages()[0].buckets().length);

			final FulltextIndex reloaded = disk.reload(index);

			assertEquals(0, reloaded.getTermCount());
			assertTrue(reloaded.collectChangedPages().changedPages().isEmpty());
			addRandom(new Random(12), reloaded, 2_000);
			flush(reloaded, disk);
			disk.assertHolds(reloaded);
		}

		@Test
		@DisplayName("Pages listed out of key order are refused as overlapping")
		void shouldRefuseOverlappingPages() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(13), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final int[] swapped = disk.orderedPageSequences.clone();
			final int last = swapped[swapped.length - 1];
			swapped[swapped.length - 1] = swapped[0];
			swapped[0] = last;
			disk.orderedPageSequences = swapped;

			assertThrows(GenericEvitaInternalError.class, () -> disk.reload(index));
		}

		@Test
		@DisplayName("A page whose impacts do not cover its records is refused")
		void shouldRefuseMisalignedImpacts() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(14), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final DictionaryPage page = disk.pages.get(disk.orderedPageSequences[0]);
			final byte[][] impacts = page.impacts().clone();
			impacts[0] = new byte[impacts[0].length + 1];
			disk.pages.put(page.pageSequence(), new DictionaryPage(page.pageSequence(), page.buckets(), impacts));

			assertThrows(GenericEvitaInternalError.class, () -> disk.reload(index));
		}

		@Test
		@DisplayName("A page listed under another sequence is refused")
		void shouldRefuseAMislabelledPage() {
			final FulltextIndex index = newIndex();
			addRandom(new Random(15), index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final DictionaryPage page = disk.pages.get(disk.orderedPageSequences[1]);
			disk.pages.put(
				disk.orderedPageSequences[1],
				new DictionaryPage(page.pageSequence() + 1_000, page.buckets(), page.impacts())
			);

			assertThrows(GenericEvitaInternalError.class, () -> disk.reload(index));
		}

	}

	@Nested
	@DisplayName("Commits")
	@Tag(TRANSACTION)
	class Commits {

		@Test
		@DisplayName("A flush inside a transaction is published by the commit, which carries the bookkeeping over")
		void shouldPublishAtCommitAndCarryTheRegistry() {
			final FulltextIndex index = newIndex();
			final Random random = new Random(7);
			addRandom(random, index, 6_000);
			final SimulatedDisk disk = new SimulatedDisk();
			flush(index, disk);
			final PageStreamRegistry registry = index.getPageStreamRegistry();

			assertStateAfterCommit(
				index,
				t -> {
					removeRange(t, 0, 800);
					addRandom(random, t, 1_500);
					assertTrue(t.isDirty());
					flush(t, disk);
					disk.assertHolds(t);
				},
				(original, committed) -> {
					assertFalse(committed.isDirty(), "The committed copy starts clean.");
					assertSame(registry, committed.getPageStreamRegistry());
					disk.assertHolds(committed);
					// nothing changed since the flush: the next collect writes and frees nothing
					final PageEmission<DictionaryPage> next = committed.collectChangedPages();
					assertTrue(next.changedPages().isEmpty());
					assertEquals(0, next.freedPageSequences().length);
					assertArrayEquals(disk.orderedPageSequences, next.orderedPageSequences());
				}
			);
		}

		@Test
		@DisplayName("Warm-up flushes followed by commits keep the disk equal to the dictionary")
		void shouldKeepTheDiskEqualAcrossWarmUpAndCommits() {
			final Random random = new Random(8);
			final SimulatedDisk disk = new SimulatedDisk();
			FulltextIndex index = newIndex();
			for (int round = 0; round < 5; round++) {
				addRandom(random, index, 2_000);
				removeRange(index, random.nextInt(TERM_COUNT - 400), TERM_COUNT);
				flush(index, disk);
				disk.assertHolds(index);
			}
			for (int round = 0; round < 6; round++) {
				final FulltextIndex[] next = new FulltextIndex[1];
				assertStateAfterCommit(
					index,
					t -> {
						final int from = random.nextInt(TERM_COUNT);
						removeRange(t, from, Math.min(TERM_COUNT, from + random.nextInt(700)));
						addRandom(random, t, random.nextInt(2_500));
						flush(t, disk);
					},
					(original, committed) -> next[0] = committed
				);
				index = next[0];
				disk.assertHolds(index);
			}
		}

	}

}
