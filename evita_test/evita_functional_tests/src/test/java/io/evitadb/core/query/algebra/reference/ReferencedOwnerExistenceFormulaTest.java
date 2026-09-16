/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReferencedOwnerExistenceFormula} - the formula that answers a `referenceHaving` from the opposite
 * end of a bidirectional (reflected) reference pair by asking one short-circuiting "does anything survive here?"
 * question per candidate owner.
 *
 * The class keeps its candidate owner keys and its per-owner formulas **outside** {@link Formula#getInnerFormulas()},
 * which is exactly what makes it worth unit testing in isolation: everything the generic {@link AbstractFormula}
 * machinery derives from the inner formula array - the hash, the transactional ids, the cost estimates and the
 * execution-context propagation - has to be re-implemented here, and each of those re-implementations is a place
 * where the formula can silently start serving one query's answer to another.
 *
 * No catalog, no fixture and no Evita instance is needed - every operand is a {@link ConstantFormula}, an
 * {@link EmptyFormula} or the local {@link RecordingFormula} stub, so
 * {@link AbstractFormula#initFields(Formula...)} (which runs from the constructor) is all the initialization
 * {@link Formula#compute()} requires.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferencedOwnerExistenceFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferencedOwnerExistenceFormulaTest {

	/**
	 * Transactional id of the counterpart type-level index used wherever the test does not vary it.
	 */
	private static final long COUNTERPART_TYPE_INDEX_ID = 42L;

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should emit only owners whose per-owner formula intersects the narrowed set")
		void shouldEmitOnlyOwnersWhoseFormulaIntersectsTheNarrowedSet() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20, 30},
				new Formula[]{constant(1, 2), constant(5), constant(2, 9)},
				constant(2, 7),
				COUNTERPART_TYPE_INDEX_ID
			);

			final Bitmap result = formula.compute();

			// owner 10 holds {1,2} and owner 30 holds {2,9} - both meet the narrowing {2,7}; owner 20 holds {5} only
			assertArrayEquals(new int[]{10, 30}, result.getArray());
		}

		@Test
		@DisplayName("should skip owners with an empty per-owner formula without relabelling the remaining ones")
		void shouldSkipOwnersWithAnEmptyPerOwnerFormula() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20, 30},
				// `EmptyFormula.INSTANCE` is what `BidirectionalReferenceRewriter#createPerOwnerFormulas` emits for
				// an owner the counterpart type-level index announced but which carries no reduced index at all
				new Formula[]{EmptyFormula.INSTANCE, constant(2), constant(2, 4)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			final Bitmap result = formula.compute();

			// the positional-pairing invariant: owner 10 drops out, and 20/30 keep *their own* keys - an off-by-one
			// in the `matchedOwners` bookkeeping would answer {10, 20} here and relabel everything downstream
			assertArrayEquals(new int[]{20, 30}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return empty bitmap when the narrowing set is empty")
		void shouldReturnEmptyBitmapWhenNarrowingSetIsEmpty() {
			final RecordingFormula firstOwner = recordingFormula(new int[]{1}, new long[0], 1L, 0L, 0L);
			final RecordingFormula secondOwner = recordingFormula(new int[]{2}, new long[0], 2L, 0L, 0L);
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				EmptyFormula.INSTANCE,
				COUNTERPART_TYPE_INDEX_ID
			);

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
			// the short-circuit happens before any per-owner work is paid for
			assertFalse(firstOwner.isComputed());
			assertFalse(secondOwner.isComputed());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should wrap an empty formula rather than returning it when cloned with no inner formulas")
		void shouldWrapEmptyFormulaRatherThanReturningItWhenClonedWithNoInnerFormulas() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			final Formula clone = formula.getCloneWithInnerFormulas();

			// answering `EmptyFormula.INSTANCE` would declare this wrapper the *identity* element of its parent
			// (see the behaviour contract on `Formula#getCloneWithInnerFormulas`), and
			// `io.evitadb.core.query.filter.FormulaOptimizer:117` would then drop it from an enclosing AND -
			// which widens a `referenceHaving` result instead of emptying it. This path is reachable, not
			// defensive: the optimizer calls it with an empty array as soon as the only child is optimised away
			final ReferencedOwnerExistenceFormula clonedFormula =
				assertInstanceOf(ReferencedOwnerExistenceFormula.class, clone);
			assertNotSame(EmptyFormula.INSTANCE, clone);
			assertSame(EmptyBitmap.INSTANCE, clonedFormula.compute());
		}

		@Test
		@DisplayName("should throw when cloned with more than one inner formula")
		void shouldThrowWhenClonedWithMoreThanOneInnerFormula() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10},
				new Formula[]{constant(1)},
				constant(1),
				COUNTERPART_TYPE_INDEX_ID
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> formula.getCloneWithInnerFormulas(constant(1), constant(2))
			);

			assertEquals(ReferencedOwnerExistenceFormula.ERROR_SINGLE_FORMULA_EXPECTED, exception.getMessage());
		}

		@Test
		@DisplayName("should pair normally with exactly one inner formula")
		void shouldPairNormallyWithExactlyOneInnerFormula() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20, 30},
				new Formula[]{constant(1, 2), constant(5), constant(2, 9)},
				constant(2, 7),
				COUNTERPART_TYPE_INDEX_ID
			);

			final ReferencedOwnerExistenceFormula clone = assertInstanceOf(
				ReferencedOwnerExistenceFormula.class,
				formula.getCloneWithInnerFormulas(constant(5))
			);

			// only the narrowing set changed - the owner keys and their per-owner formulas survived the clone,
			// so the new narrowing {5} now selects owner 20 alone
			assertArrayEquals(new int[]{20}, clone.compute().getArray());
			assertEquals(3, clone.getEstimatedCardinality());
			// the original is untouched by the clone
			assertArrayEquals(new int[]{10, 30}, formula.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final ReferencedOwnerExistenceFormula formulaA = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1, 2), constant(3)},
				constant(2, 7),
				COUNTERPART_TYPE_INDEX_ID
			);
			final ReferencedOwnerExistenceFormula formulaB = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1, 2), constant(3)},
				constant(2, 7),
				COUNTERPART_TYPE_INDEX_ID
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
			assertEquals(formulaA.getTransactionalIdHash(), formulaB.getTransactionalIdHash());
		}
	}

	/**
	 * The cache-correctness invariant, from three sides. The candidate owner set and the per-owner formulas are baked
	 * into the formula at planning time and are invisible through {@link Formula#getInnerFormulas()}, so a cache keyed
	 * on {@link Formula#getHash()} would happily serve one query's answer to another unless every one of those
	 * components reaches `includeAdditionalHash`. Each test below varies exactly one component.
	 */
	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should change hash when the owner key set changes")
		void shouldChangeHashWhenOwnerKeySetChanges() {
			final ReferencedOwnerExistenceFormula formulaA = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);
			final ReferencedOwnerExistenceFormula formulaB = createFormula(
				new int[]{10, 21},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should change hash when any per-owner formula changes")
		void shouldChangeHashWhenAnyPerOwnerFormulaChanges() {
			final ReferencedOwnerExistenceFormula formulaA = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);
			final ReferencedOwnerExistenceFormula formulaB = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(3)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should change hash when the counterpart type index id changes")
		void shouldChangeHashWhenCounterpartTypeIndexIdChanges() {
			final ReferencedOwnerExistenceFormula formulaA = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				100L
			);
			final ReferencedOwnerExistenceFormula formulaB = createFormula(
				new int[]{10, 20},
				new Formula[]{constant(1), constant(2)},
				constant(2),
				200L
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}
	}

	@Nested
	@DisplayName("Transactional ids")
	class TransactionalIdsTest {

		@Test
		@DisplayName("should gather transactional ids from per-owner formulas as well as inner ones")
		void shouldGatherTransactionalIdsFromPerOwnerFormulasAsWellAsInnerOnes() {
			final RecordingFormula innerFormula = recordingFormula(new int[]{2}, new long[]{1L}, 1L, 0L, 0L);
			final RecordingFormula firstOwner = recordingFormula(new int[]{2}, new long[]{2L}, 2L, 0L, 0L);
			final RecordingFormula secondOwner = recordingFormula(new int[]{3}, new long[]{3L, 4L}, 3L, 0L, 0L);

			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				innerFormula,
				COUNTERPART_TYPE_INDEX_ID
			);

			// the same invariant as the hash rows, seen from the other side: a formula either declares
			// a transactional id for every bitmap it depends on, or it hashes that bitmap's contents
			assertArrayEquals(new long[]{1L, 2L, 3L, 4L}, formula.gatherTransactionalIds());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should estimate cardinality as the candidate owner count")
		void shouldEstimateCardinalityAsCandidateCount() {
			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20, 30},
				new Formula[]{constant(1), constant(2), constant(3)},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			// in the worst case every candidate owner survives
			assertEquals(3, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Cost")
	class CostTest {

		@Test
		@DisplayName("should include per-owner formulas in the estimated base cost")
		void shouldIncludePerOwnerFormulasInEstimatedBaseCost() {
			final RecordingFormula firstOwner = recordingFormula(new int[]{2}, new long[0], 1L, 100L, 0L);
			final RecordingFormula secondOwner = recordingFormula(new int[]{2}, new long[0], 2L, 250L, 0L);

			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			// the per-owner formulas are not reached by the inner formula walk in `getEstimatedCostInternal()`,
			// so the base cost is the only place they can be accounted for
			assertEquals(350L, formula.getEstimatedBaseCost());
			assertTrue(
				formula.getEstimatedCost() >= 350L,
				"Estimated cost " + formula.getEstimatedCost() + " must not be lower than the per-owner sum 350"
			);
		}

		@Test
		@DisplayName("should saturate the estimated base cost instead of overflowing")
		void shouldSaturateEstimatedBaseCostInsteadOfOverflowing() {
			final long halfOfMaximumPlusOne = Long.MAX_VALUE / 2 + 1;
			final RecordingFormula firstOwner = recordingFormula(
				new int[]{2}, new long[0], 1L, halfOfMaximumPlusOne, 0L
			);
			final RecordingFormula secondOwner = recordingFormula(
				new int[]{2}, new long[0], 2L, halfOfMaximumPlusOne, 0L
			);

			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			// a wrapped-around (negative) cost would make this the cheapest plan on offer and the planner would
			// then always pick it, whatever it actually costs
			assertEquals(Long.MAX_VALUE, formula.getEstimatedBaseCost());
			assertEquals(Long.MAX_VALUE, formula.getEstimatedCost());
		}

		@Test
		@DisplayName("should include per-owner formulas in the computed cost")
		void shouldIncludePerOwnerFormulasInComputedCost() {
			final RecordingFormula firstOwner = recordingFormula(new int[]{2}, new long[0], 1L, 0L, 700L);
			final RecordingFormula secondOwner = recordingFormula(new int[]{2}, new long[0], 2L, 0L, 300L);

			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);

			// `getCost()` answers `Long.MAX_VALUE` until the result has been memoized - see `AbstractFormula#getCost`
			formula.compute();

			assertTrue(
				formula.getCost() >= 1000L,
				"Computed cost " + formula.getCost() + " must not be lower than the per-owner sum 1000"
			);
		}
	}

	@Nested
	@DisplayName("Initialization")
	class InitializationTest {

		@Test
		@DisplayName("should propagate execution context to per-owner formulas")
		void shouldPropagateExecutionContextToPerOwnerFormulas() {
			final RecordingFormula firstOwner = recordingFormula(new int[]{2}, new long[0], 1L, 0L, 0L);
			final RecordingFormula secondOwner = recordingFormula(new int[]{2}, new long[0], 2L, 0L, 0L);

			final ReferencedOwnerExistenceFormula formula = createFormula(
				new int[]{10, 20},
				new Formula[]{firstOwner, secondOwner},
				constant(2),
				COUNTERPART_TYPE_INDEX_ID
			);
			final QueryExecutionContext executionContext = Mockito.mock(QueryExecutionContext.class);

			formula.initialize(executionContext);

			// the per-owner formulas sit outside `innerFormulas`, so `super.initialize` never reaches them -
			// anything built by `FilterByVisitor#executeInContextAndIsolatedFormulaStack` (which is what
			// `BidirectionalReferenceRewriter#createPerOwnerFormulas` produces once reference-attribute
			// constraints are present) would fail on a missing execution context at execution time
			assertSame(executionContext, firstOwner.getCapturedExecutionContext());
			assertSame(executionContext, secondOwner.getCapturedExecutionContext());
			assertEquals(1, firstOwner.getInitializationCount());
			assertEquals(1, secondOwner.getInitializationCount());
		}
	}

	@Nested
	@DisplayName("Type contract")
	class TypeContractTest {

		@Test
		@DisplayName("should not be treated as a conjunctive formula")
		void shouldNotBeTreatedAsConjunctiveFormula() {
			// three post-processors walk the planned tree and flip out of "conjunctive scope" the moment they meet
			// a formula that is not in `FilterByVisitor#CONJUNCTIVE_FORMULAS`, and each of them is correct *only*
			// because this formula is outside that set:
			//
			// - the hierarchy optimizer, `filter/translator/hierarchy/AbstractHierarchyTranslator:216`
			// - the `EntityLocaleEquals` post-processor, `filter/translator/entity/EntityLocaleEqualsTranslator:146`
			// - `SuperSetMatchingPostProcessor`, `filter/translator/entity/EntityPrimaryKeyInSetTranslator:223`
			//
			// adding the class to that set later would silently widen all three
			assertFalse(FilterByVisitor.isConjunctiveFormula(ReferencedOwnerExistenceFormula.class));
		}

		@Test
		@DisplayName("should not implement ChildrenDependentFormula")
		void shouldNotImplementChildrenDependentFormula() {
			// `io.evitadb.core.query.filter.FormulaOptimizer:117` *removes* a `ChildrenDependentFormula` outright
			// once its children have been optimised away, and removing a `referenceHaving` from an enclosing
			// conjunction widens the result instead of narrowing it
			assertFalse(ChildrenDependentFormula.class.isAssignableFrom(ReferencedOwnerExistenceFormula.class));
		}
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should reject mismatched owner and per-owner formula counts")
		void shouldRejectMismatchedOwnerAndFormulaCounts() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> createFormula(
					new int[]{10, 20},
					new Formula[]{constant(1)},
					constant(2),
					COUNTERPART_TYPE_INDEX_ID
				)
			);
		}
	}

	/**
	 * Creates the formula under test. Present only so the individual tests read as data rather than as constructor
	 * calls - the constructor is public and takes no collaborators.
	 *
	 * @param ownerPrimaryKeys                     candidate owner primary keys, ascending
	 * @param perOwnerFormulas                     one formula per candidate owner, positionally paired
	 * @param referencedEntityFormula              the shared narrowing of the referenced entities
	 * @param counterpartTypeIndexTransactionalId  transactional id of the counterpart type-level index
	 * @return new formula instance
	 */
	@Nonnull
	private static ReferencedOwnerExistenceFormula createFormula(
		@Nonnull int[] ownerPrimaryKeys,
		@Nonnull Formula[] perOwnerFormulas,
		@Nonnull Formula referencedEntityFormula,
		long counterpartTypeIndexTransactionalId
	) {
		return new ReferencedOwnerExistenceFormula(
			ownerPrimaryKeys, perOwnerFormulas, referencedEntityFormula, counterpartTypeIndexTransactionalId
		);
	}

	/**
	 * Creates a {@link ConstantFormula} over the passed primary keys. Note that {@link ConstantFormula} rejects an
	 * empty delegate - use {@link EmptyFormula#INSTANCE} where an empty operand is wanted.
	 *
	 * @param primaryKeys primary keys the formula returns, must not be empty
	 * @return constant formula over the passed primary keys
	 */
	@Nonnull
	private static Formula constant(@Nonnull int... primaryKeys) {
		return new ConstantFormula(new BaseBitmap(primaryKeys));
	}

	/**
	 * Creates a {@link RecordingFormula} stub with fully controlled transactional ids, hash contribution and costs.
	 *
	 * @param result          primary keys the stub returns from `compute()`
	 * @param transactionalIds transactional ids the stub declares
	 * @param additionalHash  the stub's contribution to its own hash
	 * @param estimatedCost   value the stub answers from `getEstimatedCost()`
	 * @param computedCost    value the stub answers from `getCost()` once it has been computed
	 * @return new stub instance
	 */
	@Nonnull
	private static RecordingFormula recordingFormula(
		@Nonnull int[] result,
		@Nonnull long[] transactionalIds,
		long additionalHash,
		long estimatedCost,
		long computedCost
	) {
		return new RecordingFormula(
			new BaseBitmap(result), transactionalIds, additionalHash, estimatedCost, computedCost
		);
	}

	/**
	 * A hand-written {@link Formula} stub used instead of a Mockito mock.
	 * {@link AbstractFormula#initFields(Formula...)} runs from the constructor and calls back into
	 * `getClassId()`, `includeAdditionalHash(...)`, `gatherBitmapIdsInternal()` and `getEstimatedCostInternal()`,
	 * so a mock of a formula class would have to answer those calls before its own stubbing had been installed.
	 * Writing the stub out is both shorter and honest.
	 *
	 * Every quantity the enclosing tests assert on - transactional ids, hash contribution, estimated cost, computed
	 * cost - is supplied at construction time, and the stub additionally records whether it was computed and which
	 * {@link QueryExecutionContext} (if any) reached it.
	 */
	private static class RecordingFormula extends AbstractFormula {
		/**
		 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
		 */
		private static final long CLASS_ID = 4611686018427387903L;
		/**
		 * Bitmap this stub answers with from {@link #computeInternal()}.
		 */
		private final Bitmap result;
		/**
		 * Transactional ids this stub declares from {@link #gatherBitmapIdsInternal()}.
		 */
		private final long[] declaredTransactionalIds;
		/**
		 * This stub's contribution to its own hash.
		 */
		private final long additionalHash;
		/**
		 * Value answered from {@link #getEstimatedCostInternal()}.
		 */
		private final long estimatedCost;
		/**
		 * Value answered from {@link #getCostInternal()}.
		 */
		private final long computedCost;
		/**
		 * Number of times {@link #initialize(QueryExecutionContext)} has been called on this stub.
		 */
		private int initializationCount;
		/**
		 * The execution context handed to {@link #initialize(QueryExecutionContext)}, `null` until that happens.
		 */
		private QueryExecutionContext capturedExecutionContext;
		/**
		 * TRUE once {@link #computeInternal()} has been entered.
		 */
		private boolean computed;

		RecordingFormula(
			@Nonnull Bitmap result,
			@Nonnull long[] declaredTransactionalIds,
			long additionalHash,
			long estimatedCost,
			long computedCost
		) {
			this.result = result;
			this.declaredTransactionalIds = declaredTransactionalIds;
			this.additionalHash = additionalHash;
			this.estimatedCost = estimatedCost;
			this.computedCost = computedCost;
			this.initFields();
		}

		/**
		 * Returns the number of times this stub has been initialized.
		 *
		 * @return initialization call count
		 */
		int getInitializationCount() {
			return this.initializationCount;
		}

		/**
		 * Returns the execution context that reached this stub.
		 *
		 * @return the captured execution context, or `null` when the stub was never initialized
		 */
		@Nullable
		QueryExecutionContext getCapturedExecutionContext() {
			return this.capturedExecutionContext;
		}

		/**
		 * Returns TRUE when {@link #computeInternal()} has been entered at least once.
		 *
		 * @return `true` when this stub has been computed
		 */
		boolean isComputed() {
			return this.computed;
		}

		@Override
		public void initialize(@Nonnull QueryExecutionContext executionContext) {
			super.initialize(executionContext);
			this.initializationCount++;
			this.capturedExecutionContext = executionContext;
		}

		@Nonnull
		@Override
		protected long[] gatherBitmapIdsInternal() {
			return this.declaredTransactionalIds;
		}

		@Override
		protected long getEstimatedCostInternal() {
			return this.estimatedCost;
		}

		@Override
		protected long getCostInternal() {
			return this.computedCost;
		}

		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			return this.additionalHash;
		}

		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		@Override
		public int getEstimatedCardinality() {
			return this.result.size();
		}

		@Override
		public long getOperationCost() {
			return 1L;
		}

		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			this.computed = true;
			return this.result;
		}

		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			throw new UnsupportedOperationException("The recording stub never carries inner formulas!");
		}

		@Override
		public String toString() {
			return "RECORDING STUB (" + this.result.size() + " primary keys)";
		}
	}

}
