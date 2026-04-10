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

package io.evitadb.index.reference;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link TransactionalReference} covering construction,
 * non-transactional operations, transactional commit and rollback semantics,
 * compare-and-exchange operations, null value handling, and the
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Transactional reference")
class TransactionalReferenceTest implements TimeBoundedTestSupport {

	/**
	 * Tests for {@link TransactionalReference} constructors verifying correct initial state.
	 */
	@Nested
	@DisplayName("Constructor")
	class ConstructorTest {

		@Test
		@DisplayName("initializes with non-null value")
		void shouldInitializeWithNonNullValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>("hello");

			assertEquals("hello", ref.get());
		}

		@Test
		@DisplayName("initializes with null value")
		void shouldInitializeWithNullValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			assertNull(ref.get());
		}

	}

	/**
	 * Tests verifying that operations on {@link TransactionalReference} work correctly
	 * when no transaction is active (the `layer == null` branches).
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("sets and gets value without transaction")
		void shouldSetAndGetValueWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			ref.set("B");

			assertEquals("B", ref.get());
		}

		@Test
		@DisplayName("sets to null without transaction")
		void shouldSetNullWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			ref.set(null);

			assertNull(ref.get());
		}

		@Test
		@DisplayName("sets from null to non-null without transaction")
		void shouldSetFromNullWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			ref.set("A");

			assertEquals("A", ref.get());
		}

		@Test
		@DisplayName("handles multiple sequential sets without transaction")
		void shouldHandleMultipleSetsWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			ref.set("B");
			assertEquals("B", ref.get());

			ref.set("C");
			assertEquals("C", ref.get());

			ref.set("D");
			assertEquals("D", ref.get());
		}

		@Test
		@DisplayName("compare-and-exchange succeeds without transaction")
		void shouldCompareAndExchangeSuccessfullyWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			final String witness = ref.compareAndExchange("A", "B");

			assertEquals("A", witness);
			assertEquals("B", ref.get());
		}

		@Test
		@DisplayName("compare-and-exchange fails without transaction")
		void shouldCompareAndExchangeFailWithoutTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			final String witness = ref.compareAndExchange("X", "B");

			assertEquals("A", witness);
			assertEquals("A", ref.get());
		}

	}

	/**
	 * Tests verifying the
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} interface
	 * methods on {@link TransactionalReference}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("creates layer reflecting current non-null value")
		void shouldCreateLayerReflectingCurrentNonNullValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>("hello");

			final ReferenceChanges<String> layer = ref.createLayer();

			assertEquals("hello", layer.get());
		}

		@Test
		@DisplayName("creates layer reflecting current null value")
		void shouldCreateLayerReflectingCurrentNullValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			final ReferenceChanges<String> layer = ref.createLayer();

			assertNull(layer.get());
		}

		@Test
		@DisplayName("returns original value when layer is null and value is non-null")
		void shouldReturnOriginalValueWhenLayerIsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalReference<String> ref = new TransactionalReference<>("hello");

			final Optional<String> result =
				ref.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertTrue(result.isPresent());
			assertEquals("hello", result.get());
		}

		@Test
		@DisplayName("returns empty optional when layer is null and value is null")
		void shouldReturnEmptyOptionalWhenLayerIsNullAndValueIsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			final Optional<String> result =
				ref.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("returns layer value when layer is present")
		void shouldReturnLayerValueWhenLayerIsPresent() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalReference<String> ref = new TransactionalReference<>("original");
			final ReferenceChanges<String> changes = new ReferenceChanges<>("modified");

			final Optional<String> result =
				ref.createCopyWithMergedTransactionalMemory(changes, maintainer);

			assertTrue(result.isPresent());
			assertEquals("modified", result.get());
		}

		@Test
		@DisplayName("returns empty optional when layer holds null")
		void shouldReturnEmptyOptionalWhenLayerHoldsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalReference<String> ref = new TransactionalReference<>("original");
			final ReferenceChanges<String> changes = new ReferenceChanges<>(null);

			final Optional<String> result =
				ref.createCopyWithMergedTransactionalMemory(changes, maintainer);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldAssignUniqueIdToEachInstance() {
			final TransactionalReference<String> first = new TransactionalReference<>("A");
			final TransactionalReference<String> second = new TransactionalReference<>("B");

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
		@DisplayName("commits set value change")
		void shouldCommitSetValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set("B");
					assertEquals("B", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("B", committed.get());
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("commits last value after multiple sets")
		void shouldCommitLastValueAfterMultipleSets() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set("B");
					original.set("C");
					original.set("D");
					assertEquals("D", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("D", committed.get());
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("commits set to null")
		void shouldCommitSetToNull() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set(null);
					assertNull(ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isEmpty());
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("commits idempotent set to same value")
		void shouldCommitIdempotentSetToSameValue() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set("A");
					assertEquals("A", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("A", committed.get());
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("commits successful compare-and-exchange")
		void shouldCommitSuccessfulCompareAndExchange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange("A", "B");
					assertEquals("A", witness);
					assertEquals("B", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("B", committed.get());
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("commits unchanged value after failed compare-and-exchange")
		void shouldCommitAfterFailedCompareAndExchange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange("X", "B");
					assertEquals("A", witness);
					assertEquals("A", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("A", committed.get());
					assertEquals("A", original.get());
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
		@DisplayName("rolls back set change")
		void shouldRollbackSetChange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterRollback(
				ref,
				original -> {
					original.set("B");
					assertEquals("B", ref.get());
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("rolls back multiple set changes")
		void shouldRollbackMultipleSetChanges() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterRollback(
				ref,
				original -> {
					original.set("B");
					original.set("C");
					original.set("D");
					assertEquals("D", ref.get());
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("rolls back compare-and-exchange change")
		void shouldRollbackCompareAndExchangeChange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterRollback(
				ref,
				original -> {
					final String witness = original.compareAndExchange("A", "B");
					assertEquals("A", witness);
					assertEquals("B", ref.get());
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals("A", original.get());
				}
			);
		}

		@Test
		@DisplayName("rolls back set-to-null change")
		void shouldRollbackSetToNullChange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterRollback(
				ref,
				original -> {
					original.set(null);
					assertNull(ref.get());
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals("A", original.get());
				}
			);
		}

	}

	/**
	 * Tests verifying compare-and-exchange semantics within transactional context,
	 * including success/failure witness values and chained exchanges.
	 */
	@Nested
	@DisplayName("Compare and exchange")
	class CompareAndExchangeTest {

		@Test
		@DisplayName("returns expected value on successful exchange")
		void shouldReturnExpectedValueOnSuccessfulExchange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange("A", "B");
					// witness equals expected value on success
					assertEquals("A", witness);
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("B", committed.get());
				}
			);
		}

		@Test
		@DisplayName("returns actual value on failed exchange")
		void shouldReturnActualValueOnFailedExchange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange("X", "B");
					// witness returns the actual current value on failure
					assertEquals("A", witness);
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("A", committed.get());
				}
			);
		}

		@Test
		@DisplayName("does not change value on failed exchange")
		void shouldNotChangeValueOnFailedExchange() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					original.compareAndExchange("X", "B");
					// value remains unchanged after failed CAS
					assertEquals("A", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("A", committed.get());
				}
			);
		}

		@Test
		@DisplayName("exchanges from null to non-null")
		void shouldExchangeFromNullToNonNull() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange(null, "A");
					assertNull(witness);
					assertEquals("A", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("A", committed.get());
				}
			);
		}

		@Test
		@DisplayName("exchanges from non-null to null")
		void shouldExchangeFromNonNullToNull() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness = original.compareAndExchange("A", null);
					assertEquals("A", witness);
					assertNull(ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("chains multiple successful exchanges")
		void shouldChainMultipleSuccessfulExchanges() {
			final TransactionalReference<String> ref = new TransactionalReference<>("A");

			assertStateAfterCommit(
				ref,
				original -> {
					final String witness1 = original.compareAndExchange("A", "B");
					assertEquals("A", witness1);
					assertEquals("B", ref.get());

					final String witness2 = original.compareAndExchange("B", "C");
					assertEquals("B", witness2);
					assertEquals("C", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("C", committed.get());
					assertEquals("A", original.get());
				}
			);
		}

	}

	/**
	 * Tests verifying correct handling of null values across transactional
	 * boundaries, including transitions between null and non-null states.
	 */
	@Nested
	@DisplayName("Null value handling")
	class NullValueHandlingTest {

		@Test
		@DisplayName("commits null-to-non-null transition")
		void shouldCommitNullToNonNull() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			assertStateAfterCommit(
				ref,
				original -> {
					original.set("value");
					assertEquals("value", ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isPresent());
					assertEquals("value", committed.get());
					assertNull(original.get());
				}
			);
		}

		@Test
		@DisplayName("commits non-null-to-null transition")
		void shouldCommitNonNullToNull() {
			final TransactionalReference<String> ref = new TransactionalReference<>("value");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set(null);
					assertNull(ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isEmpty());
					assertEquals("value", original.get());
				}
			);
		}

		@Test
		@DisplayName("rolls back null-to-non-null transition")
		void shouldRollbackNullToNonNullTransition() {
			final TransactionalReference<String> ref = new TransactionalReference<>(null);

			assertStateAfterRollback(
				ref,
				original -> {
					original.set("value");
					assertEquals("value", ref.get());
				},
				(original, committed) -> {
					assertNull(committed);
					assertNull(original.get());
				}
			);
		}

		@Test
		@DisplayName("reads null within transaction when set to null")
		void shouldReadNullWithinTransaction() {
			final TransactionalReference<String> ref = new TransactionalReference<>("value");

			assertStateAfterCommit(
				ref,
				original -> {
					original.set(null);
					// within the transaction, null should be visible
					assertNull(ref.get());
				},
				(original, committed) -> {
					assertTrue(committed.isEmpty());
				}
			);
		}

	}

	/**
	 * Generational randomized proof test that applies random reference modifications
	 * within transactions and verifies the committed state matches expectations.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@DisplayName("survives generational randomized test applying modifications")
		@ParameterizedTest(name = "TransactionalReference should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final AtomicReference<Boolean> nextBooleanToCompare = new AtomicReference<>();

			runFor(
				input,
				50_000,
				new TestState(false),
				(random, testState) -> {
					final TransactionalReference<Boolean> transactionalBoolean =
						new TransactionalReference<>(testState.initialState());
					final AtomicReference<Boolean> committedResult = new AtomicReference<>();

					assertStateAfterCommit(
						transactionalBoolean,
						original -> {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								if (random.nextBoolean()) {
									transactionalBoolean.set(true);
									nextBooleanToCompare.set(true);
								} else {
									transactionalBoolean.set(false);
									nextBooleanToCompare.set(false);
								}
							}

							assertEquals(
								nextBooleanToCompare.get(),
								transactionalBoolean.get()
							);
						},
						(original, committed) -> {
							assertEquals(
								nextBooleanToCompare.get(),
								committed.orElse(null)
							);
							committedResult.set(committed.orElse(null));
						}
					);

					return new TestState(
						committedResult.get()
					);
				}
			);
		}

		/**
		 * Holds the state carried between generational test iterations.
		 */
		private record TestState(
			boolean initialState
		) {}

	}

}
