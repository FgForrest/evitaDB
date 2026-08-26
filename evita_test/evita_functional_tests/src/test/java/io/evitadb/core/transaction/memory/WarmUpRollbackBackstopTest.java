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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.HashMap;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the runtime backstop of the warm-up savepoint mechanism — the check in
 * {@link Transaction#getOrCreateTransactionalMemoryLayer(TransactionalLayerCreator)} that refuses a delegate-branch
 * write by a structure which has not declared {@link TransactionalLayerCreator#supportsWarmUpRollback()}.
 *
 * The backstop exists because warm-up has no {@link TransactionalLayerMaintainer}, and therefore no single place that
 * can see every write. Journalling is a per-structure obligation, and the failure mode of forgetting it is the one
 * worth engineering against: the rollback reports success while that structure's changes stay applied. This suite
 * therefore asserts both halves — that a structure which has NOT met the obligation is rejected while a savepoint is
 * open, and that either gating condition (no savepoint open, or the declaration present) suppresses the check on its
 * own, so the ordinary write path is untouched.
 *
 * The violating structure is a purpose-built stand-in rather than a production class: making a real index stop
 * declaring support would be a change to the thing under test, and the stand-in reproduces the exact idiom — resolve
 * the layer, and on `null` write own fields in place without journalling — that the backstop is written to catch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up rollback support backstop")
class WarmUpRollbackBackstopTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would fail every subsequent test in this fork.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("A structure that has not declared support")
	class UndeclaredStructure {

		@Test
		@DisplayName("Mutating it inside an open warm-up savepoint throws")
		void shouldThrowExceptionWhenMutatedInsideWarmUpSavepoint() {
			final UnjournalledCounter counter = new UnjournalledCounter();
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				counter::increment,
				"A structure writing in place inside a savepoint without declaring support must be refused."
			);
			assertTrue(
				error.getPrivateMessage().contains(UnjournalledCounter.class.getName()),
				"The failure must name the offending structure so it can be found and ported."
			);
			assertEquals(0, counter.value, "The refused mutation must not have been applied.");

			savepoint.commit();
		}

		@Test
		@DisplayName("Mutating it outside any savepoint is untouched")
		void shouldNotThrowExceptionWhenNoSavepointIsOpen() {
			final UnjournalledCounter counter = new UnjournalledCounter();

			// the absence of an open savepoint short-circuits the check - the state every ALIVE catalog and every
			// unbracketed warm-up write is in, and the reason the backstop costs the ordinary write path nothing
			assertDoesNotThrow(counter::increment, "Without an open savepoint there is nothing to rewind.");
			assertEquals(1, counter.value);
		}
	}

	@Nested
	@DisplayName("A structure that has declared support")
	class DeclaredStructure {

		@Test
		@DisplayName("Mutating it inside an open warm-up savepoint is allowed")
		void shouldNotThrowExceptionWhenDeclaringStructureIsMutated() {
			final JournalledCounter counter = new JournalledCounter();
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();

			assertDoesNotThrow(counter::increment, "A declared structure must be allowed to write.");
			assertEquals(1, counter.value);

			savepoint.rollback();
			assertEquals(0, counter.value, "The journalled inverse must have rewound the write.");
		}

		@Test
		@DisplayName("The ported production structures declare it")
		void shouldDeclareSupportOnPortedProductionStructures() {
			// a spot check across the three journalling strategies - first-touch reference capture, per-operation
			// membership capture, and per-operation slot capture - so a structure that loses its declaration in a
			// future refactor fails here rather than at the first bracketed mutation that reaches it. The exhaustive
			// version of this assertion is WarmUpRollbackConformanceTest
			assertTrue(new TransactionalBoolean().supportsWarmUpRollback());
			assertTrue(new TransactionalBitmap().supportsWarmUpRollback());
			assertTrue(new TransactionalMap<>(new HashMap<String, String>()).supportsWarmUpRollback());
		}
	}

	/**
	 * A structure written the way every delegate-branch mutator is written — resolve the layer, and when there is none
	 * write own state in place — but WITHOUT journalling the write and WITHOUT declaring
	 * {@link TransactionalLayerCreator#supportsWarmUpRollback()}. This is precisely the shape a structure has after
	 * being reached by the warm-up write path but never ported, and the shape the backstop must refuse.
	 */
	private static class UnjournalledCounter implements TransactionalLayerCreator<Object> {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private int value;

		@Override
		public long getId() {
			return this.id;
		}

		@Nullable
		@Override
		public Object createLayer() {
			return null;
		}

		/**
		 * Increments the counter, resolving the diff layer first exactly as a production mutator does. The layer
		 * resolution is where the backstop sits, so the increment below is never reached when the savepoint refuses.
		 */
		void increment() {
			final Object layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				this.value++;
			}
		}
	}

	/**
	 * The same structure, ported: it journals the inverse of its in-place write before applying it, and declares that
	 * it has done so. The counterpart of {@link UnjournalledCounter}, so the two differ in exactly the property under
	 * test.
	 */
	private static class JournalledCounter implements TransactionalLayerCreator<Object>, WarmUpTouchStamped {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private int value;
		private long warmUpTouchStamp;

		@Override
		public long getId() {
			return this.id;
		}

		@Override
		public long getWarmUpTouchStamp() {
			return this.warmUpTouchStamp;
		}

		@Override
		public void setWarmUpTouchStamp(long stamp) {
			this.warmUpTouchStamp = stamp;
		}

		@Nullable
		@Override
		public Object createLayer() {
			return null;
		}

		@Override
		public boolean supportsWarmUpRollback() {
			return true;
		}

		/**
		 * Increments the counter, capturing the whole pre-image on the first write-touch inside a savepoint — the
		 * `O(1)`-pre-image strategy, since the counter's entire mutable state is one `int`.
		 */
		void increment() {
			final Object layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
				if (savepoint != null && savepoint.claimFirstTouch(this)) {
					final int preImage = this.value;
					savepoint.push(() -> this.value = preImage);
				}
				this.value++;
			}
		}
	}

}
