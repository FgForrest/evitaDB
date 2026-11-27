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

package io.evitadb.index;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.reference.TransactionalReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the index structures whose ENTIRE mutable state has an `O(1)` pre-image — the two scalar holders and
 * the `TransactionalIntArray` delegate reference — rewind their non-transactional (WARM_UP) writes when the
 * {@link WarmUpSavepoint} bracketing them is rolled back, and keep them when it is committed.
 *
 * The three structures capture the pre-image on the FIRST write-touch inside the savepoint
 * and restore that state on rollback, so the tests deliberately hammer each structure with SEVERAL writes per
 * savepoint — a strategy that re-captured on every write would pass a single-write test and still lose the
 * pre-savepoint value here.
 *
 * The savepoint is opened directly rather than through `LocalMutationExecutorCollector`, because what is under test is
 * the structures' own journaling and not the bracket — an open savepoint records the same thing however it came to be
 * open.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepointCollectionRollbackTest for the structures that journal per operation instead
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of scalar and array structures")
class WarmUpSavepointScalarAndArrayRollbackTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("TransactionalBoolean")
	class Booleans {

		@Test
		@DisplayName("Rollback restores the pre-savepoint value however many times the flag was flipped")
		void shouldRestoreFlagAfterRepeatedFlips() {
			final TransactionalBoolean flag = new TransactionalBoolean(true);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			flag.setToFalse();
			flag.setToTrue();
			flag.setToFalse();
			assertFalse(flag.isTrue(), "self-check: the writes must have taken effect inside the savepoint");
			savepoint.rollback();

			assertTrue(flag.isTrue(), "Rollback must restore the value the flag held before the savepoint opened.");
		}

		@Test
		@DisplayName("Rollback restores a flag reset through the reset() alias")
		void shouldRestoreFlagResetThroughAlias() {
			final TransactionalBoolean flag = new TransactionalBoolean(true);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			flag.reset();
			savepoint.rollback();

			assertTrue(flag.isTrue(), "reset() is setToFalse() and must be journaled the same way.");
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepFlagOnCommit() {
			final TransactionalBoolean flag = new TransactionalBoolean(false);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			flag.setToTrue();
			savepoint.commit();

			assertTrue(flag.isTrue(), "Commit must keep the savepoint's writes.");
		}

		@Test
		@DisplayName("Only the flags actually written inside the savepoint are rewound")
		void shouldLeaveUntouchedFlagsAlone() {
			final TransactionalBoolean touched = new TransactionalBoolean(false);
			final TransactionalBoolean untouched = new TransactionalBoolean(true);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touched.setToTrue();
			savepoint.rollback();

			assertFalse(touched.isTrue(), "The written flag must be rewound.");
			assertTrue(untouched.isTrue(), "A flag nobody wrote must be left exactly as it was.");
		}
	}

	@Nested
	@DisplayName("TransactionalReference")
	class References {

		@Test
		@DisplayName("Rollback restores the pre-savepoint reference after repeated sets")
		void shouldRestoreReferenceAfterRepeatedSets() {
			final TransactionalReference<String> reference = new TransactionalReference<>("original");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			reference.set("first");
			reference.set("second");
			savepoint.rollback();

			assertEquals("original", reference.get(), "Rollback must restore the pre-savepoint reference.");
		}

		@Test
		@DisplayName("Rollback restores a reference exchanged through compareAndExchange")
		void shouldRestoreReferenceAfterCompareAndExchange() {
			final TransactionalReference<String> reference = new TransactionalReference<>("original");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertEquals("original", reference.compareAndExchange("original", "exchanged"));
			assertEquals("exchanged", reference.get(), "self-check: the exchange must have succeeded");
			savepoint.rollback();

			assertEquals("original", reference.get(), "Rollback must restore the pre-exchange reference.");
		}

		@Test
		@DisplayName("Rollback restores a null reference that was written inside the savepoint")
		void shouldRestoreNullReference() {
			final TransactionalReference<String> reference = new TransactionalReference<>(null);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			reference.set("written");
			savepoint.rollback();

			assertNull(reference.get(), "A reference that was null before the savepoint must be null again.");
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepReferenceOnCommit() {
			final TransactionalReference<String> reference = new TransactionalReference<>("original");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			reference.set("kept");
			savepoint.commit();

			assertEquals("kept", reference.get(), "Commit must keep the savepoint's writes.");
		}
	}

	@Nested
	@DisplayName("TransactionalIntArray")
	class IntArrays {

		/**
		 * Checks that rollback restores the original contents after several additions and removals.
		 */
		@Test
		@DisplayName("Rollback restores the array through every mutator kind")
		void shouldRestoreArrayAfterMixedMutations() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 3, 5});

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			array.add(2);
			array.add(4);
			array.add(6);
			array.add(7);
			array.remove(3);
			array.remove(1);
			array.remove(5);
			assertArrayEquals(new int[]{2, 4, 6, 7}, array.getArray(), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 3, 5}, array.getArray(), "Rollback must restore the pre-savepoint array.");
		}

		@Test
		@DisplayName("Rollback restores the very array instance the structure held, not a rebuilt equal one")
		void shouldRestoreTheOriginalArrayInstance() {
			final int[] originalDelegate = new int[]{1, 2, 3};
			final TransactionalIntArray array = new TransactionalIntArray(originalDelegate);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			array.add(4);
			savepoint.rollback();

			assertSame(
				originalDelegate, array.getArray(),
				"The pre-image is the outgoing array reference itself, so the restore must hand it straight back."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepArrayOnCommit() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2});

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			array.add(3);
			savepoint.commit();

			assertArrayEquals(new int[]{1, 2, 3}, array.getArray(), "Commit must keep the savepoint's writes.");
		}

		@Test
		@DisplayName("Only the arrays actually written inside the savepoint are rewound")
		void shouldLeaveUntouchedArraysAlone() {
			final TransactionalIntArray touched = new TransactionalIntArray(new int[]{1});
			final TransactionalIntArray untouched = new TransactionalIntArray(new int[]{9});

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touched.add(2);
			savepoint.rollback();

			assertArrayEquals(new int[]{1}, touched.getArray(), "The written array must be rewound.");
			assertArrayEquals(new int[]{9}, untouched.getArray(), "An array nobody wrote must be left as it was.");
		}
	}
}
