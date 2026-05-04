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

package io.evitadb.index.bool;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link TransactionalBoolean} covering construction,
 * non-transactional operations, transactional commit and rollback semantics, and the
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Transactional boolean")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalBooleanTest {

	/**
	 * Tests for {@link TransactionalBoolean} constructors verifying correct initial state.
	 */
	@Nested
	@DisplayName("Constructor")
	class ConstructorTest {

		@Test
		@DisplayName("initializes to false with no-arg constructor")
		void shouldInitializeToFalseWithNoArgConstructor() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean();
			assertFalse(theBoolean.isTrue());
		}

		@Test
		@DisplayName("initializes to given value")
		void shouldInitializeToGivenValue() {
			final TransactionalBoolean theFalse = new TransactionalBoolean(false);
			assertFalse(theFalse.isTrue());

			final TransactionalBoolean theTrue = new TransactionalBoolean(true);
			assertTrue(theTrue.isTrue());
		}

	}

	/**
	 * Tests verifying that operations on {@link TransactionalBoolean} work correctly
	 * when no transaction is active (the `layer == null` branches).
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("sets to true without transaction")
		void shouldSetToTrueWithoutTransaction() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(false);

			theBoolean.setToTrue();

			assertTrue(theBoolean.isTrue());
		}

		@Test
		@DisplayName("sets to false without transaction")
		void shouldSetToFalseWithoutTransaction() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			theBoolean.setToFalse();

			assertFalse(theBoolean.isTrue());
		}

		@Test
		@DisplayName("resets to false without transaction")
		void shouldResetWithoutTransaction() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			theBoolean.reset();

			assertFalse(theBoolean.isTrue());
		}

		@Test
		@DisplayName("reads correct value without transaction for both states")
		void shouldReadValueWithoutTransaction() {
			final TransactionalBoolean theFalse = new TransactionalBoolean(false);
			assertFalse(theFalse.isTrue());

			final TransactionalBoolean theTrue = new TransactionalBoolean(true);
			assertTrue(theTrue.isTrue());
		}

		@Test
		@DisplayName("handles multiple toggles without transaction")
		void shouldToggleWithoutTransaction() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(false);

			theBoolean.setToTrue();
			assertTrue(theBoolean.isTrue());

			theBoolean.setToFalse();
			assertFalse(theBoolean.isTrue());

			theBoolean.setToTrue();
			assertTrue(theBoolean.isTrue());

			theBoolean.setToTrue();
			assertTrue(theBoolean.isTrue());

			theBoolean.setToFalse();
			assertFalse(theBoolean.isTrue());
		}

	}

	/**
	 * Tests verifying the
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} interface
	 * methods on {@link TransactionalBoolean}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("creates layer reflecting current value")
		void shouldCreateLayerWithCurrentValue() {
			final TransactionalBoolean falseBoolean = new TransactionalBoolean(false);
			final BooleanChanges falseLayer = falseBoolean.createLayer();
			assertFalse(falseLayer.isTrue());

			final TransactionalBoolean trueBoolean = new TransactionalBoolean(true);
			final BooleanChanges trueLayer = trueBoolean.createLayer();
			assertTrue(trueLayer.isTrue());
		}

		@Test
		@DisplayName("returns original value when layer is null")
		void shouldReturnOriginalValueWhenLayerIsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			final TransactionalBoolean falseBoolean = new TransactionalBoolean(false);
			final Boolean falseResult =
				falseBoolean.createCopyWithMergedTransactionalMemory(null, maintainer);
			assertFalse(falseResult);

			final TransactionalBoolean trueBoolean = new TransactionalBoolean(true);
			final Boolean trueResult =
				trueBoolean.createCopyWithMergedTransactionalMemory(null, maintainer);
			assertTrue(trueResult);
		}

		@Test
		@DisplayName("returns layer value when layer is present")
		void shouldReturnLayerValueWhenLayerIsPresent() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);

			// original is false, but layer says true
			final TransactionalBoolean falseBoolean = new TransactionalBoolean(false);
			final BooleanChanges trueChanges = new BooleanChanges(true);
			final Boolean resultTrue =
				falseBoolean.createCopyWithMergedTransactionalMemory(trueChanges, maintainer);
			assertTrue(resultTrue);

			// original is true, but layer says false
			final TransactionalBoolean trueBoolean = new TransactionalBoolean(true);
			final BooleanChanges falseChanges = new BooleanChanges(false);
			final Boolean resultFalse =
				trueBoolean.createCopyWithMergedTransactionalMemory(falseChanges, maintainer);
			assertFalse(resultFalse);
		}

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldHaveUniqueId() {
			final TransactionalBoolean first = new TransactionalBoolean();
			final TransactionalBoolean second = new TransactionalBoolean();

			assertNotEquals(first.getId(), second.getId());
		}

	}

	/**
	 * Tests verifying that transactional changes are correctly committed,
	 * producing the expected new state while the original remains unchanged
	 * until the commit is finalized.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("commits setToTrue change")
		void shouldCorrectlySetToTrueAndCommit() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean();

			assertStateAfterCommit(
				theBoolean,
				original -> {
					original.setToTrue();
					assertTrue(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertTrue(committed);
					assertFalse(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("commits setToFalse change")
		void shouldCorrectlySetToFalseAndCommit() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterCommit(
				theBoolean,
				original -> {
					original.setToFalse();
					assertFalse(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertFalse(committed);
					assertTrue(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("commits reset change")
		void shouldCorrectlyResetAndCommit() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterCommit(
				theBoolean,
				original -> {
					original.reset();
					assertFalse(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertFalse(committed);
					assertTrue(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("commits last state when toggled within transaction")
		void shouldCommitLastStateWhenToggledInTransaction() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean();

			assertStateAfterCommit(
				theBoolean,
				original -> {
					original.setToTrue();
					assertTrue(theBoolean.isTrue());
					original.setToFalse();
					assertFalse(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertFalse(committed);
					assertFalse(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("commits idempotent setToTrue on already-true value")
		void shouldCommitToTrueFromAlreadyTrue() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterCommit(
				theBoolean,
				original -> {
					original.setToTrue();
					assertTrue(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertTrue(committed);
					assertTrue(original.isTrue());
				}
			);
		}

	}

	/**
	 * Tests verifying that transactional changes are correctly discarded on rollback,
	 * leaving the original state untouched.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("rolls back setToTrue change")
		void shouldCorrectlySetToTrueAndRollback() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean();

			assertStateAfterRollback(
				theBoolean,
				original -> {
					original.setToTrue();
					assertTrue(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertNull(committed);
					assertFalse(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("rolls back setToFalse change")
		void shouldCorrectlySetToFalseAndRollback() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterRollback(
				theBoolean,
				original -> {
					original.setToFalse();
					assertFalse(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("rolls back toggled state preserving original value")
		void shouldRollbackToggledState() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterRollback(
				theBoolean,
				original -> {
					// toggle: true -> false -> true
					original.setToFalse();
					assertFalse(theBoolean.isTrue());
					original.setToTrue();
					assertTrue(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isTrue());
				}
			);
		}

		@Test
		@DisplayName("rolls back reset change preserving original value")
		void shouldRollbackResetChange() {
			final TransactionalBoolean theBoolean = new TransactionalBoolean(true);

			assertStateAfterRollback(
				theBoolean,
				original -> {
					original.reset();
					assertFalse(theBoolean.isTrue());
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isTrue());
				}
			);
		}

	}

}
