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

package io.evitadb.index.array;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.array.UnorderedLookupTree.LeafPageInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises all three bulk load paths through their public positional, record-id and page APIs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Unordered int array bulk-built value index")
@Tag(INDEXING)
@Tag(TRANSACTION)
class TransactionalUnorderedIntArrayBulkBuildTest {
	/**
	 * An odd id that {@link #recordIds(int)} never generates, so lookups of it must miss.
	 */
	private static final int ABSENT_RECORD_ID = 1;

	/**
	 * Clears a savepoint if a failed assertion interrupted its rollback.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			savepoint.commit();
		}
	}

	/**
	 * Checks complete positional and record-id reads at all requested sizes, preserving persisted page identities.
	 */
	@ParameterizedTest
	@MethodSource("loadPathsAndCounts")
	@DisplayName("plain, head-aware and paged loads retain every position and page")
	void shouldLoadEquivalentValueIndex(int path, int count) {
		final int[] ids = recordIds(count);
		final TransactionalUnorderedIntArray array = load(ids, path);
		assertArrayEquals(ids, array.getArray());
		assertArrayConsistent(array);
		assertEquals(Integer.MIN_VALUE, array.indexOf(ABSENT_RECORD_ID));
		assertFalse(array.contains(ABSENT_RECORD_ID));
		if (path != 0 && count > 0) {
			assertEquals(ids[0], array.findHeadCovering(count - 1).recordId());
		}
		if (path == 2) {
			assertTrue(array.collectChangedPages().isEmpty());
			final List<LeafPageInput> pages = pages(ids);
			final List<UnorderedLookupTree.LeafPageHandle> leaves = array.leafPageHandles();
			assertEquals(pages.size(), leaves.size());
			for (int i = 0; i < pages.size(); i++) {
				assertArrayEquals(pages.get(i).recordIds(), leaves.get(i).recordIds());
				assertEquals(pages.get(i).pageSequence(), leaves.get(i).getPageSequence());
			}
		}
	}

	/**
	 * Compaction must keep the last assignment across leaf boundaries, also for an id reported three times, and log
	 * the first repeated report once together with the report it repeats.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("duplicate ids retain their last assignment and log one ERROR")
	void shouldKeepLastDuplicateAssignment(int path) {
		final int[] ids = recordIds(3_000);
		ids[1_500] = ids[0];
		ids[2_500] = ids[0];
		ids[2_999] = ids[1];
		final Logger logger = (Logger) LoggerFactory.getLogger(TransactionalUnorderedIntArray.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		final TransactionalUnorderedIntArray array;
		try {
			array = load(ids, path);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		assertArrayEquals(ids, array.getArray());
		assertEquals(2_500, array.indexOf(ids[0]), "The last duplicate assignment must win.");
		assertEquals(2_999, array.indexOf(ids[1]), "The last duplicate assignment must win.");
		assertEquals(2_997, array.getRecordIds().size());
		assertEquals(1, appender.list.size(), "Duplicates must be logged once per load.");
		final ILoggingEvent event = appender.list.getFirst();
		assertEquals(Level.ERROR, event.getLevel());
		final String message = event.getFormattedMessage();
		assertTrue(message.contains(Long.toString(array.getId())));
		assertTrue(message.contains(Integer.toString(ids[0])));
		assertTrue(message.contains("position 1500 (previous position 0)"), message);
		assertTrue(message.contains("last assignment"), message);
		assertTrue(message.contains("correct"), message);
		assertFalse(message.contains("damaged"), message);
	}

	/**
	 * The reserved primary key must be rejected wherever it occurs on every bulk path.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("all load paths reject reserved ids at the beginning, middle and end")
	void shouldRejectReservedPrimaryKey(int path) {
		for (final int position : new int[]{0, 63, 1_499}) {
			final int[] ids = recordIds(1_500);
			ids[position] = EvitaDataTypes.RESERVED_PRIMARY_KEY;
			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> load(ids, path));
			assertTrue(error.getMessage().contains("is reserved by evitaDB"), error.getMessage());
		}
	}

	/**
	 * Compares later committed mutations with an incrementally constructed reference array.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("incremental writes after each load path commit correctly")
	void shouldCommitIncrementalChangesAfterLoad(int path) {
		final int[] ids = recordIds(3_000);
		final TransactionalUnorderedIntArray reference = new TransactionalUnorderedIntArray(path != 0);
		int previous = EvitaDataTypes.RESERVED_PRIMARY_KEY;
		for (final int id : ids) {
			reference.add(previous, id);
			previous = id;
		}
		mutate(reference, ids);
		assertStateAfterCommit(
			load(ids, path),
			array -> mutate(array, ids),
			(original, committed) -> {
				assertArrayEquals(ids, original.getArray());
				assertArrayEquals(reference.getArray(), committed.getArray());
				assertArrayConsistent(committed);
			}
		);
	}

	/**
	 * A bound transaction is detached while loading committed base data, then rebound for unrelated owner writes.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("loading under a bound transaction establishes a surviving base")
	void shouldLoadIntoBaseInsideTransaction(int path) {
		final int[] ids = recordIds(3_000);
		final AtomicReference<TransactionalUnorderedIntArray> loaded = new AtomicReference<>();
		assertStateAfterRollback(
			new TransactionalUnorderedIntArray(),
			owner -> {
				loaded.set(load(ids, path));
				assertTrue(Transaction.isTransactionAvailable(), "Loading must rebind the caller's transaction.");
				owner.add(EvitaDataTypes.RESERVED_PRIMARY_KEY, 1);
				assertArrayConsistent(loaded.get());
			},
			(owner, discarded) -> {
				assertArrayEquals(ids, loaded.get().getArray());
				assertArrayConsistent(loaded.get());
			}
		);
	}

	/**
	 * Observes the old array and inverse lookup from outside the writer, then checks both again after abort.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("later transactional writes stay isolated and disappear on abort")
	void shouldIsolateAndAbortChanges(int path) {
		final int[] ids = recordIds(3_000);
		assertStateAfterRollback(
			load(ids, path),
			array -> {
				mutate(array, ids);
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.unbindTransactionFromThread();
				try {
					assertArrayEquals(ids, array.getArray());
					assertArrayConsistent(array);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			(original, discarded) -> {
				assertArrayEquals(ids, original.getArray());
				assertArrayConsistent(original);
			}
		);
	}

	/**
	 * Observes chain heads as well as values before and after rolling back later warm-up writes.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	@DisplayName("warm-up rollback restores record positions and chain heads")
	void shouldRestoreValuesAndHeadsAtWarmUpSavepoint(int path) {
		final int[] ids = recordIds(3_000);
		final TransactionalUnorderedIntArray array = load(ids, path);
		array.markAsHead(ids[1_024]);
		array.markAsHead(ids[2_048]);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		array.add(ids[0], 1);
		array.remove(ids[1_000]);
		array.markAsHead(ids[100]);
		array.unmarkAsHead(ids[1_024]);
		assertEquals(ids[100], array.findHeadCovering(array.indexOf(ids[101])).recordId());
		assertEquals(1, array.indexOf(1));
		savepoint.rollback();
		assertArrayEquals(ids, array.getArray());
		assertArrayConsistent(array);
		assertEquals(Integer.MIN_VALUE, array.indexOf(1));
		for (int i = 0; i < ids.length; i++) {
			final int head = i >= 2_048 ? 2_048 : i >= 1_024 ? 1_024 : 0;
			assertEquals(new TransactionalUnorderedIntArray.HeadLocation(head, ids[head]), array.findHeadCovering(i));
		}
	}

	/**
	 * Reads both halves of the composite through the positional and inverse APIs, including sorted-id positions.
	 */
	private static void assertArrayConsistent(@Nonnull TransactionalUnorderedIntArray array) {
		final int[] ids = array.getArray();
		assertEquals(ids.length, array.getLength());
		for (int i = 0; i < ids.length; i++) {
			assertEquals(ids[i], array.get(i));
			assertEquals(i, array.indexOf(ids[i]), "The inverse value index must resolve the logical position.");
		}
		final int[] sortedIds = ids.clone();
		Arrays.sort(sortedIds);
		assertArrayEquals(sortedIds, array.getRecordIds().getArray());
		final int[] positions = array.getPositions();
		assertEquals(sortedIds.length, positions.length);
		for (int i = 0; i < positions.length; i++) {
			assertEquals(sortedIds[i], ids[positions[i]]);
		}
	}

	/**
	 * Loads one of the three constructor paths: plain, with heads, or preserved leaf pages.
	 */
	@Nonnull
	private static TransactionalUnorderedIntArray load(@Nonnull int[] ids, int path) {
		return switch (path) {
			case 0 -> new TransactionalUnorderedIntArray(ids);
			case 1 -> new TransactionalUnorderedIntArray(ids, ids.length == 0 ? new int[0] : new int[]{0});
			case 2 -> new TransactionalUnorderedIntArray(pages(ids));
			default -> throw new IllegalArgumentException("Unknown load path.");
		};
	}

	/**
	 * Uses deliberately uneven page boundaries and non-consecutive page sequences to detect accidental repacking.
	 */
	@Nonnull
	private static List<LeafPageInput> pages(@Nonnull int[] ids) {
		final List<LeafPageInput> pages = new ArrayList<>();
		int start = 0;
		while (start < ids.length) {
			final int end = Math.min(ids.length, start + 701);
			final long[] heads = new long[(end - start + 63) / 64];
			if (start == 0) {
				heads[0] = 1L;
			}
			pages.add(new LeafPageInput(pages.size() * 3 + 7, Arrays.copyOfRange(ids, start, end), heads));
			start = end;
		}
		return pages;
	}

	/**
	 * Crosses the three load paths with empty, single-leaf, leaf-boundary and multi-level sizes.
	 */
	@Nonnull
	private static List<Arguments> loadPathsAndCounts() {
		final int[] counts = {0, 1, 2, 62, 63, 64, 65, 127, 128, 129, 1_000, 100_000};
		final List<Arguments> arguments = new ArrayList<>(3 * counts.length);
		for (int path = 0; path < 3; path++) {
			for (final int count : counts) {
				arguments.add(Arguments.of(path, count));
			}
		}
		return arguments;
	}

	/**
	 * Produces unique signed record ids in random logical order, excluding the reserved id and absent probe. From two
	 * records on, the ids include both int extremes, so the packed record-id and ordinal sort meets its sign boundary.
	 */
	@Nonnull
	private static int[] recordIds(int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = (i + 1) * 2 * (i % 2 == 0 ? 1 : -1);
		}
		if (count > 1) {
			ids[0] = Integer.MIN_VALUE;
			ids[1] = Integer.MAX_VALUE;
		}
		final Random random = new Random(42);
		for (int i = count - 1; i > 0; i--) {
			final int other = random.nextInt(i + 1);
			final int swap = ids[i];
			ids[i] = ids[other];
			ids[other] = swap;
		}
		return ids;
	}

	/**
	 * Applies enough edits to trigger position-container changes and value-index splits and merges.
	 */
	private static void mutate(@Nonnull TransactionalUnorderedIntArray array, @Nonnull int[] ids) {
		for (int i = 1; i < 1500; i += 2) {
			array.remove(ids[i]);
		}
		for (int i = 0; i < 500; i++) {
			array.add(ids[i * 2], 100_001 + i * 2);
		}
	}

}
