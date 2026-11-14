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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.RequestedThresholdPredicate;
import io.evitadb.core.query.algebra.attribute.ThresholdComparison;
import io.evitadb.core.query.algebra.attribute.ThresholdOperator;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link ReferenceBodyTransposer#transpose(Formula, Set, Supplier)} rebuilds a `referenceHaving` body one
 * index at a time - when it returns the body as it stands instead, that the rebuild agrees with the previous
 * algorithm, how it decides an `inScope` container for each row, and that it is linear in the size of the index
 * family.
 *
 * Skipping the rebuild for a body whose contributions meet only under `or` is what keeps a single attribute leaf
 * cheap on a reference with a six-figure partition count - and every attribute leaf arrives wrapped in an
 * {@link AttributeFormula}, so the skip has to see through that wrapper or it never applies to one at all. Every
 * other body is rebuilt, which used to visit every child of every `or` once per index and was quadratic: 544 ms at
 * 8,000 indexes, 10.9 s at 32,000. The nested classes pin the rebuild that replaced it.
 *
 * Which path was taken is read off the shape of the result: the fast path keeps the wrapper at the root around the
 * union, while the rebuild unions one wrapper per index. The row-scoped answers are asserted alongside, because a
 * shape alone could hide a wrong result.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference body transposer")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceBodyTransposerTest {
	/**
	 * Stands in for the index supplier on bodies without a negation, where the transposer must never consult it.
	 */
	private static final Supplier<List<EntityIndex>> NO_INDEXES_EXPECTED = () -> {
		throw new AssertionError("The index supplier must not be consulted for a body without a negation!");
	};
	/**
	 * The scopes of the rows of a body holding no scope container - every row lives in the live scope.
	 */
	private static final Set<Scope> ONE_SCOPE = EnumSet.of(Scope.LIVE);
	/**
	 * The scopes of the rows of a body evaluated in a query over both scopes.
	 */
	private static final Set<Scope> BOTH_SCOPES = EnumSet.of(Scope.LIVE, Scope.ARCHIVED);

	@DisplayName("Should return a wrapped union of per-index leaves as it stands")
	@Test
	void shouldReturnAWrappedUnionOfPerIndexLeavesAsItStands() {
		final int indexCount = 64;
		final Formula[] leaves = new Formula[indexCount];
		final int[] expected = new int[indexCount];
		for (int i = 0; i < indexCount; i++) {
			leaves[i] = new IndexTaggedFormula(i + 1, constant(i + 1));
			expected[i] = i + 1;
		}
		final Formula body = wrap(new OrFormula(leaves));

		final Formula result = ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED);

		// the rebuild would have produced an `or` of 64 wrappers - the wrapper at the root is the fast path
		final AttributeFormula wrapper = assertInstanceOf(AttributeFormula.class, result);
		final OrFormula union = assertInstanceOf(OrFormula.class, wrapper.getInnerFormulas()[0]);
		assertEquals(indexCount, union.getInnerFormulas().length);
		for (Formula child : union.getInnerFormulas()) {
			assertFalse(child instanceof IndexTaggedFormula, "The tags must not survive the transpose!");
		}
		assertArrayEquals(expected, result.compute().getArray());
	}

	@DisplayName("Should keep the histogram predicate of a wrapper it returns as it stands")
	@Test
	void shouldKeepTheHistogramPredicateOfAWrapperItReturnsAsItStands() {
		final RequestedThresholdPredicate requestedPredicate = new ThresholdComparison(
			ThresholdOperator.GREATER_THAN, BigDecimal.ZERO);
		final Formula body = new AttributeFormula(
			false, new AttributeKey("a"),
			new OrFormula(new IndexTaggedFormula(1, constant(1)), new IndexTaggedFormula(2, constant(2))),
			requestedPredicate
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED);

		assertSame(requestedPredicate, assertInstanceOf(AttributeFormula.class, result).getRequestedPredicate());
	}

	@DisplayName("Should still rebuild a conjunction held under the wrapper")
	@Test
	void shouldStillRebuildAConjunctionHeldUnderTheWrapper() {
		// index 1 holds a row matching both sides (record 2); index 2 matches the first side with record 3 and the
		// second with record 1 - two different rows, so only record 2 answers the row-scoped question, while the
		// union read across indexes would answer {1, 2}
		final Formula body = wrap(
			new AndFormula(
				new OrFormula(new IndexTaggedFormula(1, constant(1, 2)), new IndexTaggedFormula(2, constant(3))),
				new OrFormula(new IndexTaggedFormula(1, constant(2)), new IndexTaggedFormula(2, constant(1)))
			)
		);

		assertArrayEquals(
			new int[]{2}, ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED).compute().getArray()
		);
	}

	@DisplayName("Should still rebuild a conjunction of two wrapped unions")
	@Test
	void shouldStillRebuildAConjunctionOfTwoWrappedUnions() {
		final Formula body = new AndFormula(
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(1, 2)), new IndexTaggedFormula(2, constant(3)))),
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(2)), new IndexTaggedFormula(2, constant(1))))
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED);

		assertFalse(result instanceof AndFormula, "A conjunction across indexes must be rebuilt per index!");
		assertArrayEquals(new int[]{2}, result.compute().getArray());
	}

	/**
	 * Wraps the formula the way every attribute translator does.
	 *
	 * @param formula the formula to wrap
	 * @return the wrapper
	 */
	@Nonnull
	private static AttributeFormula wrap(@Nonnull Formula formula) {
		return new AttributeFormula(false, new AttributeKey("a"), formula);
	}

	/**
	 * Creates a constant formula over the passed record ids.
	 *
	 * @param values record ids in ascending order
	 * @return the constant formula
	 */
	@Nonnull
	private static ConstantFormula constant(@Nonnull int... values) {
		return new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values)));
	}


	/** Owner primary keys range over 1..OWNER_UNIVERSE. */
	private static final int OWNER_UNIVERSE = 40;
	/** Index primary keys start here, well away from owner primary keys. */
	private static final int FIRST_INDEX_PK = 1000;

	/**
	 * Returns a constant formula over the passed owners, or {@link EmptyFormula#INSTANCE} when there are none -
	 * {@link ConstantFormula} refuses an empty bitmap.
	 *
	 * @param owners owner primary keys
	 * @return the formula
	 */
	@Nonnull
	private static Formula constant(@Nonnull Set<Integer> owners) {
		return owners.isEmpty() ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(new BaseBitmap(owners.stream().mapToInt(Integer::intValue).sorted().toArray()));
	}

	/**
	 * Returns a mocked reduced index with the passed primary key and owners.
	 *
	 * @param primaryKey primary key of the index
	 * @param owners     owners of the index - the super set a negation inside it is complemented against
	 * @return the index
	 */
	@Nonnull
	private static EntityIndex index(int primaryKey, @Nonnull Set<Integer> owners) {
		final EntityIndex index = mock(EntityIndex.class);
		when(index.getPrimaryKey()).thenReturn(primaryKey);
		when(index.getAllPrimaryKeysFormula()).thenReturn(constant(owners));
		return index;
	}

	/**
	 * Returns a mocked reduced index of a scope with the passed primary key and owners.
	 *
	 * @param primaryKey primary key of the index
	 * @param scope      the scope of the index, and of the rows it holds
	 * @param owners     owners of the index - the super set a negation inside it is complemented against
	 * @return the index
	 */
	@Nonnull
	private static EntityIndex index(int primaryKey, @Nonnull Scope scope, @Nonnull Set<Integer> owners) {
		final EntityIndex index = index(primaryKey, owners);
		final EntityIndexKey indexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, scope, new RepresentativeReferenceKey(new ReferenceKey("r", primaryKey))
		);
		when(index.getIndexKey()).thenReturn(indexKey);
		return index;
	}

	/**
	 * Computes the formula into sorted owner primary keys.
	 *
	 * @param formula the formula
	 * @return the owners
	 */
	@Nonnull
	private static int[] owners(@Nonnull Formula formula) {
		return formula.compute().getArray();
	}

	/**
	 * Randomized bodies with a fixed seed, each transposed by the current algorithm and by the previous one.
	 */
	@Nested
	@DisplayName("Agrees with the previous algorithm")
	class Differential {

		@Test
		@DisplayName("on 2,000 random bodies of tagged, untagged and negated leaves under and / or")
		void shouldProduceTheSameAnswerAsVisitingEveryChild() {
			final Random random = new Random(1644L);
			int negating = 0;
			for (int round = 0; round < 2_000; round++) {
				final RandomBody body = RandomBody.generate(random);
				final Formula expected = LegacyTransposer.transpose(body.formula(), body.indexes());
				final Formula actual = ReferenceBodyTransposer.transpose(body.formula(), ONE_SCOPE, body::indexes);
				assertArrayEquals(
					owners(expected), owners(actual),
					"Round " + round + " diverged for body:\n" + body.formula()
				);
				if (ReferenceBodyTransposerTest.containsFutureNot(body.formula())) {
					negating++;
				}
			}
			assertTrue(
				negating > 200 && negating < 1_800,
				"The generator must exercise both the negating and the negation-free path, got " + negating +
					" negating bodies of 2,000."
			);
		}
	}

	/**
	 * Row semantics on hand-written bodies, independently of any algorithm.
	 */
	@Nested
	@DisplayName("Row semantics")
	class RowSemantics {

		@Test
		@DisplayName("a conjunction must hold on one index, not on two different ones")
		void shouldNotCombineConjunctsFromDifferentIndexes() {
			// owner 1 holds a row in index A matching the first conjunct and a row in index B matching the second
			final int indexA = FIRST_INDEX_PK;
			final int indexB = FIRST_INDEX_PK + 1;
			final Formula body = new AndFormula(
				new OrFormula(
					new IndexTaggedFormula(indexA, constant(Set.of(1, 2))),
					new IndexTaggedFormula(indexB, constant(Set.of(3)))
				),
				new OrFormula(
					new IndexTaggedFormula(indexA, constant(Set.of(2))),
					new IndexTaggedFormula(indexB, constant(Set.of(1, 3)))
				)
			);
			assertArrayEquals(
				new int[]{2, 3},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, List::of)),
				"owner 1 satisfies each conjunct only on a different index"
			);
		}

		@Test
		@DisplayName("a negation is complemented against the owners of each index")
		void shouldComplementANegationPerIndex() {
			final int indexA = FIRST_INDEX_PK;
			final int indexB = FIRST_INDEX_PK + 1;
			final List<EntityIndex> scope = List.of(index(indexA, Set.of(1, 2)), index(indexB, Set.of(2, 3)));
			// not(leaf): leaf matches owner 1 in A and owner 2 in B
			final Formula body = new FutureNotFormula(
				new OrFormula(
					new IndexTaggedFormula(indexA, constant(Set.of(1))),
					new IndexTaggedFormula(indexB, constant(Set.of(2)))
				)
			);
			assertArrayEquals(
				new int[]{2, 3},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, () -> scope)),
				"A \\ {1} = {2} and B \\ {2} = {3}; owner 2 matches through its row in A"
			);
		}
	}

	/**
	 * Row semantics of an `inScope` container: it restricts the rows of its own scope and is satisfied by the rows of
	 * another one, each row deciding it by the scope of its index.
	 */
	@Nested
	@DisplayName("Scope containers")
	class ScopeContainers {
		private static final int LIVE_A = FIRST_INDEX_PK;
		private static final int LIVE_B = FIRST_INDEX_PK + 1;
		private static final int ARCHIVED_C = FIRST_INDEX_PK + 2;
		private static final int ARCHIVED_D = FIRST_INDEX_PK + 3;

		/**
		 * Returns the indexes of both scopes: the live A owned by 1 and 2, the live B owned by 2 and 3, the archived C
		 * owned by 4 and the archived D owned by 5 and 6.
		 *
		 * @return the indexes
		 */
		@Nonnull
		private static List<EntityIndex> indexesOfBothScopes() {
			return List.of(
				index(LIVE_A, Scope.LIVE, Set.of(1, 2)),
				index(LIVE_B, Scope.LIVE, Set.of(2, 3)),
				index(ARCHIVED_C, Scope.ARCHIVED, Set.of(4)),
				index(ARCHIVED_D, Scope.ARCHIVED, Set.of(5, 6))
			);
		}

		@Test
		@DisplayName("a container keeps the matching rows of its scope and every row of another scope")
		void shouldKeepEveryRowOfAnotherScopeWhenContainerRestrictsOneScope() {
			// inScope(LIVE, leaf): the leaf matches owner 1 in A only - C and D tagged nothing
			final Formula body = new ScopeContainerFormula(
				Scope.LIVE, new IndexTaggedFormula(LIVE_A, constant(Set.of(1)))
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{1, 4, 5, 6},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes))
			);
		}

		@Test
		@DisplayName("a negation inside a container is complemented against the owners of each index of its scope")
		void shouldComplementNegationInsideContainerPerIndex() {
			// inScope(LIVE, not(leaf)): the leaf matches owner 1 in A and owners 2 and 3 in B - owner 3 holds no row
			// failing it, so it must not match although no row of A matches it either
			final Formula body = new ScopeContainerFormula(
				Scope.LIVE,
				new FutureNotFormula(
					new OrFormula(
						new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
						new IndexTaggedFormula(LIVE_B, constant(Set.of(2, 3)))
					)
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{2, 4, 5, 6},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes)),
				"A \\ {1} = {2}, B \\ {2, 3} is empty, and C and D are of another scope"
			);
		}

		@Test
		@DisplayName("a negated container excludes every row of another scope")
		void shouldExcludeEveryRowOfAnotherScopeWhenContainerIsNegated() {
			// not(inScope(LIVE, leaf)): a row of another scope satisfies the container, so it fails its negation
			final Formula body = new FutureNotFormula(
				new ScopeContainerFormula(
					Scope.LIVE, new IndexTaggedFormula(LIVE_A, constant(Set.of(1)))
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{2, 3},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes))
			);
		}

		@Test
		@DisplayName("a container of each scope applies to the rows of its scope only")
		void shouldApplyEachContainerToRowsOfItsScopeWhenBothScopesAreRestricted() {
			// and(inScope(LIVE, x), inScope(ARCHIVED, y)): x matches owner 2 in B, y matches owner 6 in D
			final Formula body = new AndFormula(
				new ScopeContainerFormula(
					Scope.LIVE, new IndexTaggedFormula(LIVE_B, constant(Set.of(2)))
				),
				new ScopeContainerFormula(
					Scope.ARCHIVED, new IndexTaggedFormula(ARCHIVED_D, constant(Set.of(6)))
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{2, 6},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes))
			);
		}

		@Test
		@DisplayName("a container of a scope no row lives in restricts nothing")
		void shouldKeepEveryRowWhenContainerRestrictsScopeOfNoRow() {
			// inScope(ARCHIVED, ...) in an evaluation of the live rows alone carries no children - and a conjunct
			// next to it still decides the row
			final Formula body = new AndFormula(
				new ScopeContainerFormula(Scope.ARCHIVED),
				new OrFormula(
					new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
					new IndexTaggedFormula(LIVE_B, constant(Set.of(3)))
				)
			);
			final List<EntityIndex> liveIndexes = indexesOfBothScopes().subList(0, 2);
			assertArrayEquals(
				new int[]{1, 3},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, () -> liveIndexes))
			);
		}

		@Test
		@DisplayName("a container of the one scope of all rows is rebuilt from the indexes that tagged something")
		void shouldNotConsultIndexesWhenContainerRestrictsTheOnlyScope() {
			// inScope(LIVE, and(x, y)) in a query over the live scope: owner 2 holds x in A and y in B only
			final Formula body = new ScopeContainerFormula(
				Scope.LIVE,
				new OrFormula(
					new IndexTaggedFormula(LIVE_A, constant(Set.of(1, 2))),
					new IndexTaggedFormula(LIVE_B, constant(Set.of(3)))
				),
				new OrFormula(
					new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
					new IndexTaggedFormula(LIVE_B, constant(Set.of(2, 3)))
				)
			);
			final Formula result = ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED);
			assertArrayEquals(new int[]{1, 3}, owners(result));
			assertTrue(
				FormulaFinder.find(result, ScopeContainerFormula.class, LookUp.DEEP).isEmpty(),
				"No container may reach the enclosing level: " + result
			);
		}

		@Test
		@DisplayName("a container of the one scope of all rows keeps an index-independent child as it stands")
		void shouldKeepIndexIndependentChildWhenContainerRestrictsTheOnlyScope() {
			// inScope(LIVE, constant) in a query over the live scope reads as the constant alone, which tags no index
			final Formula body = new ScopeContainerFormula(Scope.LIVE, constant(Set.of(1)));
			assertArrayEquals(
				new int[]{1},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, NO_INDEXES_EXPECTED))
			);
		}

		@Test
		@DisplayName("an index-independent child of a container is kept for every index of its scope")
		void shouldKeepIndexIndependentChildWhenContainerRestrictsOneOfTwoScopes() {
			// no index tagged anything, so the live indexes contribute only through the constant
			final Formula body = new ScopeContainerFormula(Scope.LIVE, constant(Set.of(1)));
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{1, 4, 5, 6},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes))
			);
		}

		@Test
		@DisplayName("a negated container of the one scope of all rows holding a negation cancels both")
		void shouldCancelNegationsAroundContainerOfTheOnlyScope() {
			// not(inScope(LIVE, not(leaf))) in a query over the live scope: the container hid the inner negation from
			// the outer one, so the pair meets only once the container is unwrapped - and reads as the leaf
			final Formula body = new FutureNotFormula(
				new ScopeContainerFormula(
					Scope.LIVE,
					new FutureNotFormula(
						new OrFormula(
							new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
							new IndexTaggedFormula(LIVE_B, constant(Set.of(2, 3)))
						)
					)
				)
			);
			final List<EntityIndex> indexes = List.of(index(LIVE_A, Set.of(1, 2)), index(LIVE_B, Set.of(2, 3)));
			assertArrayEquals(
				new int[]{1, 2, 3},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, () -> indexes))
			);
		}

		@Test
		@DisplayName("a negated container holding a negation keeps each negation in its scope across both scopes")
		void shouldResolveNegationsAroundContainerPerIndexWhenScopesDiffer() {
			// not(inScope(LIVE, not(leaf))): a live row matches when it matches the leaf, an archived row never
			final Formula body = new FutureNotFormula(
				new ScopeContainerFormula(
					Scope.LIVE,
					new FutureNotFormula(new IndexTaggedFormula(LIVE_A, constant(Set.of(1))))
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();
			assertArrayEquals(
				new int[]{1},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes))
			);
		}

		@Test
		@DisplayName("a negation inside a container of the one scope of all rows is complemented per index")
		void shouldComplementNegationInsideContainerOfTheOnlyScopePerIndex() {
			// inScope(LIVE, not(leaf)) in a query over the live scope - the indexes carry no scope, because a
			// container restricting every row is unwrapped and never decided by the scope of an index
			final Formula body = new ScopeContainerFormula(
				Scope.LIVE,
				new FutureNotFormula(
					new OrFormula(
						new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
						new IndexTaggedFormula(LIVE_B, constant(Set.of(2, 3)))
					)
				)
			);
			final List<EntityIndex> indexes = List.of(index(LIVE_A, Set.of(1, 2)), index(LIVE_B, Set.of(2, 3)));
			assertArrayEquals(
				new int[]{2},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, () -> indexes)),
				"A \\ {1} = {2} and B \\ {2, 3} is empty - owner 3 holds no row failing the leaf"
			);
		}

		@Test
		@DisplayName("a negated disjunction holding a negating container of the one scope of all rows is resolved")
		void shouldCancelNegationsAcrossDisjunctionWhenContainerRestrictsTheOnlyScope() {
			// not(or(inScope(LIVE, not(x)), y)) is `x and not y` for a live row: x matches owners 1 and 2 in A, y
			// owner 2 in A - unwrapped, the disjunction hands its negation up as a placeholder to the negation above
			final Formula body = new FutureNotFormula(
				new OrFormula(
					new ScopeContainerFormula(
						Scope.LIVE, new FutureNotFormula(new IndexTaggedFormula(LIVE_A, constant(Set.of(1, 2))))
					),
					new IndexTaggedFormula(LIVE_A, constant(Set.of(2)))
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes().subList(0, 2);

			assertArrayEquals(
				new int[]{1},
				owners(ReferenceBodyTransposer.transpose(body, ONE_SCOPE, () -> indexes)),
				"A \\ {2} = {1} for the rows matching x, and no row of B matches x"
			);
			assertArrayEquals(
				new int[]{1},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes)),
				"the projection of every index must agree with the unwrapped body"
			);
		}

		@Test
		@DisplayName("a container holding several children is their conjunction on each index of its scope")
		void shouldConjoinChildrenPerIndexWhenContainerRestrictsOneOfTwoScopes() {
			// inScope(LIVE, x, y): x matches owners 1 and 2 in A and owner 3 in B, y owner 1 in A and owners 2 and 3
			// in B - owner 2 matches each of them in a different row only
			final Formula body = new ScopeContainerFormula(
				Scope.LIVE,
				new OrFormula(
					new IndexTaggedFormula(LIVE_A, constant(Set.of(1, 2))),
					new IndexTaggedFormula(LIVE_B, constant(Set.of(3)))
				),
				new OrFormula(
					new IndexTaggedFormula(LIVE_A, constant(Set.of(1))),
					new IndexTaggedFormula(LIVE_B, constant(Set.of(2, 3)))
				)
			);
			final List<EntityIndex> indexes = indexesOfBothScopes();

			assertArrayEquals(
				new int[]{1, 3, 4, 5, 6},
				owners(ReferenceBodyTransposer.transpose(body, BOTH_SCOPES, () -> indexes)),
				"A: {1, 2} and {1} is {1}, B: {3} and {2, 3} is {3}, and C and D are of another scope"
			);
		}

		@Test
		@DisplayName("an unwrapped container of the one scope of all rows answers as projected, on 2,000 random bodies")
		void shouldAnswerAsProjectionWhenContainerOfTheOnlyScopeIsUnwrapped() {
			// over indexes of the live scope alone, a live container is the conjunction of its children for every
			// row: unwrapped, the body takes the paths of a body without containers, while projected, every container
			// is decided by the scope of each index - and both must give the same per-row answer
			final Random random = new Random(31L);
			int negating = 0;
			for (int round = 0; round < 2_000; round++) {
				final RandomBody body = RandomBody.generateWithScopeContainers(random);
				final Formula unwrapped = ReferenceBodyTransposer.transpose(body.formula(), ONE_SCOPE, body::indexes);
				final Formula projected = ReferenceBodyTransposer.transpose(body.formula(), BOTH_SCOPES, body::indexes);
				assertArrayEquals(
					owners(projected), owners(unwrapped),
					"Round " + round + " diverged for body:\n" + body.formula()
				);
				if (ReferenceBodyTransposerTest.containsFutureNot(body.formula())) {
					negating++;
				}
			}
			assertTrue(
				negating > 200 && negating < 1_800,
				"The generator must exercise both the negating and the negation-free path, got " + negating +
					" negating bodies of 2,000."
			);
		}
	}

	/**
	 * The cost that motivated the change, at production scale.
	 */
	@Nested
	@DisplayName("Cost")
	class Cost {

		@Test
		@DisplayName("a conjunctive body over 32,000 indexes is rebuilt in linear time")
		void shouldRebuildAConjunctiveBodyInLinearTime() {
			// calibration: the previous algorithm needed 10.9 s for this shape at 32,000 indexes and quadrupled with
			// every doubling - this test failed it at 23.6 s of CPU; the linear rebuild takes about 0.2 s, so the
			// bound cannot flake on a loaded machine and still fails for a quadratic regression
			final int indexCount = 32_000;
			final Formula[] firstConjunct = new Formula[indexCount];
			final Formula[] secondConjunct = new Formula[indexCount];
			for (int i = 0; i < indexCount; i++) {
				final int owner = i + 1;
				firstConjunct[i] = new IndexTaggedFormula(FIRST_INDEX_PK + i, constant(Set.of(owner)));
				// every other index satisfies the second conjunct
				secondConjunct[i] = new IndexTaggedFormula(
					FIRST_INDEX_PK + i, constant(i % 2 == 0 ? Set.of(owner) : Set.of(owner + indexCount))
				);
			}
			final Formula body = new AndFormula(new OrFormula(firstConjunct), new OrFormula(secondConjunct));

			// CPU time of this thread, not wall-clock: parallel test forks and GC pauses inflate the latter on a busy
			// machine, while the work the rebuild itself does is what the bound is about. A JVM that cannot measure
			// it skips the test rather than falling back to a clock that flakes; one that measures it disabled
			// reports -1 at both ends - a zero that would pass a quadratic rebuild - so it is switched on first
			final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			assumeTrue(threads.isCurrentThreadCpuTimeSupported(), "The JVM does not measure thread CPU time.");
			if (!threads.isThreadCpuTimeEnabled()) {
				threads.setThreadCpuTimeEnabled(true);
			}
			final long start = threads.getCurrentThreadCpuTime();
			final Formula transposed = ReferenceBodyTransposer.transpose(body, ONE_SCOPE, List::of);
			final long end = threads.getCurrentThreadCpuTime();
			assertTrue(start >= 0 && end >= start, "Thread CPU time must be measured, got " + start + " -> " + end);
			final long elapsedMillis = (end - start) / 1_000_000L;

			assertEquals(indexCount / 2, owners(transposed).length);
			assertTrue(
				elapsedMillis < 5_000L,
				"Rebuilding " + indexCount + " indexes took " + elapsedMillis +
					" ms of CPU - the rebuild is not linear."
			);
		}
	}

	/**
	 * Returns TRUE when a negation placeholder sits anywhere in the tree.
	 *
	 * @param node tree root
	 * @return whether the tree negates something
	 */
	private static boolean containsFutureNot(@Nonnull Formula node) {
		if (node instanceof FutureNotFormula) {
			return true;
		}
		for (final Formula child : node.getInnerFormulas()) {
			if (containsFutureNot(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when a negation placeholder sits in the tree outside every scope container - one the translation
	 * of a `not` above the tree would see. A container hides the placeholders inside it from the levels above.
	 *
	 * @param node tree root
	 * @return whether a placeholder is visible from above the tree
	 */
	private static boolean containsVisibleFutureNot(@Nonnull Formula node) {
		if (node instanceof FutureNotFormula) {
			return true;
		}
		if (node instanceof ScopeContainerFormula) {
			return false;
		}
		for (final Formula child : node.getInnerFormulas()) {
			if (containsVisibleFutureNot(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when a scope container sits anywhere in the tree, below a negation placeholder included.
	 *
	 * @param node tree root
	 * @return whether the tree restricts a scope
	 */
	private static boolean containsScopeContainer(@Nonnull Formula node) {
		if (node instanceof ScopeContainerFormula) {
			return true;
		}
		if (node instanceof final FutureNotFormula futureNot) {
			// the placeholder holds its operand apart from its inner formulas
			return containsScopeContainer(futureNot.getInnerFormula());
		}
		for (final Formula child : node.getInnerFormulas()) {
			if (containsScopeContainer(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What the generator of {@link RandomBody} may emit at a node.
	 */
	private enum BodyShape {
		/**
		 * Tagged leaves and index-independent constants, no scope container - the bodies the previous algorithm
		 * answers too.
		 */
		WITHOUT_CONTAINERS,
		/**
		 * Tagged leaves only, and live scope containers above them. Index-independent constants are left out: the
		 * rebuild bounds them by the owners of the index only where it complements them, and a projected container
		 * complements its children where the unwrapped one does not - `not(inScope(LIVE, not(c)))` projects to the
		 * owners of the index within `c`, but unwraps to `c`.
		 */
		WITH_CONTAINERS,
		/**
		 * Tagged leaves only, inside a container, which holds no other container.
		 */
		INSIDE_CONTAINER
	}

	/**
	 * A randomly generated body and the index family it was generated over.
	 *
	 * @param formula the body
	 * @param indexes the reduced indexes in scope, every index the body tags among them
	 */
	private record RandomBody(@Nonnull Formula formula, @Nonnull List<EntityIndex> indexes) {

		/**
		 * Generates a body shaped like a translated `referenceHaving` body: per-index constraints (an `or` with one
		 * tagged child per participating index, occasionally two for one index, sometimes wrapped in an
		 * `AttributeFormula`), index-independent constants, and
		 * `and`, `or` and negation placeholders above them, up to three levels deep. Every tagged leaf is a subset
		 * of its index's owners, as a real per-index contribution is, and no placeholder is nested inside another,
		 * as translation never produces one.
		 *
		 * @param random source of randomness
		 * @return the body
		 */
		@Nonnull
		static RandomBody generate(@Nonnull Random random) {
			return generate(random, BodyShape.WITHOUT_CONTAINERS);
		}

		/**
		 * Generates a body as {@link #generate(Random)} does, but holding at least one `inScope` container of the
		 * live scope, over live indexes, without index-independent constants and up to four levels deep. Containers
		 * are never nested and never empty, as the translation of a query over the live scope produces them, and a
		 * negation placeholder may sit above a container holding another one: the container hides it from the
		 * `not` above, which therefore does not cancel the pair as it does without the container.
		 *
		 * @param random source of randomness
		 * @return the body
		 */
		@Nonnull
		static RandomBody generateWithScopeContainers(@Nonnull Random random) {
			return generate(random, BodyShape.WITH_CONTAINERS);
		}

		/**
		 * Generates a body of the shape.
		 *
		 * @param random source of randomness
		 * @param shape  what the body may hold
		 * @return the body
		 */
		@Nonnull
		private static RandomBody generate(@Nonnull Random random, @Nonnull BodyShape shape) {
			final int indexCount = 1 + random.nextInt(12);
			final Map<Integer, Set<Integer>> ownersByIndex = new HashMap<>(indexCount * 2);
			final List<Integer> indexPks = new ArrayList<>(indexCount);
			final List<EntityIndex> indexes = new ArrayList<>(indexCount);
			for (int i = 0; i < indexCount; i++) {
				final Integer indexPk = FIRST_INDEX_PK + i;
				final Set<Integer> owners = randomSubset(random, allOwners(), 5 + random.nextInt(16));
				indexPks.add(indexPk);
				ownersByIndex.put(indexPk, owners);
				// a container projected per index is decided by the scope of the index
				indexes.add(
					shape == BodyShape.WITHOUT_CONTAINERS ? index(indexPk, owners) : index(indexPk, Scope.LIVE, owners)
				);
			}
			final int depth = shape == BodyShape.WITHOUT_CONTAINERS ? 3 : 4;
			Formula formula;
			do {
				formula = node(random, depth, shape, indexPks, ownersByIndex);
			} while (
				formula instanceof EmptyFormula ||
					(shape == BodyShape.WITH_CONTAINERS && !containsScopeContainer(formula))
			);
			return new RandomBody(formula, indexes);
		}

		/**
		 * Generates one node.
		 *
		 * @param random        source of randomness
		 * @param depth         remaining depth
		 * @param shape         what the node may hold
		 * @param indexPks      the index family
		 * @param ownersByIndex owners of every index
		 * @return the node, possibly {@link EmptyFormula#INSTANCE}
		 */
		@Nonnull
		private static Formula node(
			@Nonnull Random random,
			int depth,
			@Nonnull BodyShape shape,
			@Nonnull List<Integer> indexPks,
			@Nonnull Map<Integer, Set<Integer>> ownersByIndex
		) {
			final int roll = random.nextInt(100);
			if (depth == 0 || roll < 30) {
				return shape == BodyShape.WITHOUT_CONTAINERS && roll % 4 == 0 ?
					constant(randomSubset(random, allOwners(), random.nextInt(12))) :
					perIndexConstraint(random, indexPks, ownersByIndex);
			}
			if (roll < 50) {
				final Formula inner = node(random, depth - 1, shape, indexPks, ownersByIndex);
				// translation never nests one placeholder inside another it can see - `NotTranslator` collapses
				// `not(not(x))` and `not(or(a, not(b)))` at the only level where both are still visible - so neither
				// does this; a placeholder inside a scope container is hidden from it
				return inner instanceof EmptyFormula || containsVisibleFutureNot(inner) ?
					inner : new FutureNotFormula(inner);
			}
			if (shape == BodyShape.WITH_CONTAINERS && roll < 60) {
				final Formula[] containerChildren = new Formula[1 + random.nextInt(2)];
				for (int i = 0; i < containerChildren.length; i++) {
					containerChildren[i] = node(random, depth - 1, BodyShape.INSIDE_CONTAINER, indexPks, ownersByIndex);
				}
				final Formula[] nonEmptyChildren = Arrays.stream(containerChildren)
					.filter(it -> !(it instanceof EmptyFormula))
					.toArray(Formula[]::new);
				return nonEmptyChildren.length == 0 ?
					EmptyFormula.INSTANCE : new ScopeContainerFormula(Scope.LIVE, nonEmptyChildren);
			}
			final Formula[] children = new Formula[2 + random.nextInt(2)];
			for (int i = 0; i < children.length; i++) {
				children[i] = node(random, depth - 1, shape, indexPks, ownersByIndex);
			}
			final Formula[] nonEmpty = Arrays.stream(children)
				.filter(it -> !(it instanceof EmptyFormula))
				.toArray(Formula[]::new);
			if (nonEmpty.length < 2) {
				return nonEmpty.length == 0 ? EmptyFormula.INSTANCE : nonEmpty[0];
			}
			return roll < 75 ? new AndFormula(nonEmpty) : new OrFormula(nonEmpty);
		}

		/**
		 * Generates the translation of one constraint inside the body: an `or` of per-index contributions,
		 * sometimes inside the `AttributeFormula` an attribute translator wraps it in.
		 *
		 * @param random        source of randomness
		 * @param indexPks      the index family
		 * @param ownersByIndex owners of every index
		 * @return the constraint's formula
		 */
		@Nonnull
		private static Formula perIndexConstraint(
			@Nonnull Random random,
			@Nonnull List<Integer> indexPks,
			@Nonnull Map<Integer, Set<Integer>> ownersByIndex
		) {
			final List<Formula> contributions = new ArrayList<>();
			for (final Integer indexPk : indexPks) {
				if (random.nextInt(3) == 0) {
					continue;
				}
				final Set<Integer> indexOwners = ownersByIndex.get(indexPk);
				final int copies = random.nextInt(10) == 0 ? 2 : 1;
				for (int copy = 0; copy < copies; copy++) {
					final Formula leaf = constant(
						randomSubset(random, indexOwners, random.nextInt(indexOwners.size() + 1))
					);
					if (!(leaf instanceof EmptyFormula)) {
						contributions.add(new IndexTaggedFormula(indexPk, leaf));
					}
				}
			}
			final Formula constraint = FormulaFactory.or(contributions.toArray(Formula[]::new));
			// every attribute translator wraps its contributions in an `AttributeFormula`, a container the
			// transposer keeps by identity - a third of the constraints arrive wrapped the same way
			return constraint instanceof EmptyFormula || random.nextInt(3) != 0 ?
				constraint : new AttributeFormula(false, new AttributeKey("a"), constraint);
		}

		/**
		 * Returns every owner primary key.
		 *
		 * @return 1..{@link #OWNER_UNIVERSE}
		 */
		@Nonnull
		private static Set<Integer> allOwners() {
			final Set<Integer> result = new TreeSet<>();
			for (int owner = 1; owner <= OWNER_UNIVERSE; owner++) {
				result.add(owner);
			}
			return result;
		}

		/**
		 * Returns a random subset of the passed set of at most the passed size.
		 *
		 * @param random source of randomness
		 * @param from   the set to draw from
		 * @param size   the requested size
		 * @return the subset
		 */
		@Nonnull
		private static Set<Integer> randomSubset(@Nonnull Random random, @Nonnull Set<Integer> from, int size) {
			final List<Integer> pool = new ArrayList<>(from);
			final Set<Integer> result = new TreeSet<>();
			for (int i = 0; i < size && !pool.isEmpty(); i++) {
				result.add(pool.remove(random.nextInt(pool.size())));
			}
			return result;
		}
	}

	/**
	 * The transposition as it was before `or` nodes were split by tag: every projection visits every child of every
	 * node. Kept only as the oracle of {@link Differential}. It rebuilds every body that was evaluated per index -
	 * so the fast path for tagged bodies combined only by union is checked against a real rebuild too - and returns
	 * a body carrying neither a tag nor a negation as it stands, because such a body says the same thing about every
	 * row and the transposer is documented to pass it through.
	 */
	private static final class LegacyTransposer {

		/**
		 * Rebuilds the body per index and unions the results.
		 *
		 * @param body    the body
		 * @param indexes the indexes in scope
		 * @return the row-scoped equivalent
		 */
		@Nonnull
		static Formula transpose(@Nonnull Formula body, @Nonnull List<EntityIndex> indexes) {
			if (!projectable(body)) {
				return body;
			}
			final List<Formula> perIndex = new ArrayList<>();
			if (containsFutureNot(body)) {
				for (final EntityIndex index : indexes) {
					collect(body, index.getPrimaryKey(), index::getAllPrimaryKeysFormula, perIndex);
				}
			} else {
				final Set<Integer> tagged = new LinkedHashSet<>();
				collectTags(body, tagged);
				for (final Integer indexPk : tagged) {
					collect(
						body, indexPk,
						() -> {
							throw new IllegalStateException("No super set on the negation-free path.");
						},
						perIndex
					);
				}
			}
			return switch (perIndex.size()) {
				case 0 -> EmptyFormula.INSTANCE;
				case 1 -> perIndex.getFirst();
				default -> FormulaFactory.or(perIndex.toArray(Formula[]::new));
			};
		}

		/**
		 * Projects the body onto one index and keeps a non-empty result.
		 *
		 * @param body     the body
		 * @param indexPk  the index
		 * @param superSet owners of the index
		 * @param perIndex accumulator
		 */
		private static void collect(
			@Nonnull Formula body,
			int indexPk,
			@Nonnull Supplier<Formula> superSet,
			@Nonnull List<Formula> perIndex
		) {
			final Formula projection = resolveNegation(project(body, indexPk, superSet), superSet);
			if (!(projection instanceof EmptyFormula)) {
				perIndex.add(projection);
			}
		}

		/**
		 * The previous projection: every child of every node is visited.
		 *
		 * @param node     node being projected
		 * @param indexPk  the index
		 * @param superSet owners of the index
		 * @return the projection
		 */
		@Nonnull
		private static Formula project(@Nonnull Formula node, int indexPk, @Nonnull Supplier<Formula> superSet) {
			if (node instanceof final IndexTaggedFormula tagged) {
				return tagged.getIndexPrimaryKey() == indexPk ?
					ReferenceBodyTransposer.stripTags(tagged.getDelegate()) : EmptyFormula.INSTANCE;
			}
			if (node instanceof final FutureNotFormula futureNot) {
				final Formula projectedInner = project(futureNot.getInnerFormula(), indexPk, superSet);
				return projectedInner instanceof EmptyFormula ? superSet.get() : new FutureNotFormula(projectedInner);
			}
			if (!projectable(node)) {
				return node;
			}
			final Formula[] children = node.getInnerFormulas();
			final Formula[] projected = new Formula[children.length];
			for (int i = 0; i < children.length; i++) {
				projected[i] = project(children[i], indexPk, superSet);
			}
			if (node instanceof OrFormula) {
				final Formula[] survivors = withoutEmpty(projected);
				return survivors.length == 0 ?
					EmptyFormula.INSTANCE :
					FutureNotFormula.postProcess(survivors, EnclosingContainerRelation.DISJUNCTION, superSet);
			}
			if (node instanceof AndFormula) {
				for (final Formula child : projected) {
					if (child instanceof EmptyFormula) {
						return EmptyFormula.INSTANCE;
					}
				}
				return FutureNotFormula.postProcess(projected, EnclosingContainerRelation.CONJUNCTION, superSet);
			}
			for (int i = 0; i < projected.length; i++) {
				projected[i] = resolveNegation(projected[i], superSet);
			}
			return withoutEmpty(projected).length == 0 ?
				EmptyFormula.INSTANCE : node.getCloneWithInnerFormulas(projected);
		}

		/**
		 * Resolves a negation placeholder left at the top of a projection.
		 *
		 * @param formula  the projection
		 * @param superSet owners of the index
		 * @return the formula without a placeholder at its root
		 */
		@Nonnull
		private static Formula resolveNegation(@Nonnull Formula formula, @Nonnull Supplier<Formula> superSet) {
			return formula instanceof final FutureNotFormula futureNot ?
				new NotFormula(futureNot.getInnerFormula(), superSet.get()) : formula;
		}

		/**
		 * Answers whether the subtree carries a tag or a negation placeholder.
		 *
		 * @param node subtree root
		 * @return whether it has to be projected
		 */
		private static boolean projectable(@Nonnull Formula node) {
			if (node instanceof IndexTaggedFormula || node instanceof FutureNotFormula) {
				return true;
			}
			for (final Formula child : node.getInnerFormulas()) {
				if (projectable(child)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Collects the indexes that tagged something.
		 *
		 * @param node      subtree root
		 * @param collected accumulator
		 */
		private static void collectTags(@Nonnull Formula node, @Nonnull Set<Integer> collected) {
			if (node instanceof final IndexTaggedFormula tagged) {
				collected.add(tagged.getIndexPrimaryKey());
				return;
			}
			for (final Formula child : node.getInnerFormulas()) {
				collectTags(child, collected);
			}
		}

		/**
		 * Returns the formulas that are not empty.
		 *
		 * @param formulas formulas to filter
		 * @return the non-empty ones
		 */
		@Nonnull
		private static Formula[] withoutEmpty(@Nonnull Formula[] formulas) {
			return Arrays.stream(formulas).filter(it -> !(it instanceof EmptyFormula)).toArray(Formula[]::new);
		}
	}
}
