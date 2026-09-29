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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.EntityIndex;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
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
 * Pins how {@link ReferenceBodyTransposer#transpose(Formula, Supplier)} rebuilds a `referenceHaving` body one index
 * at a time - when it returns the body as it stands instead, that the rebuild agrees with the previous algorithm,
 * and that it is linear in the size of the index family.
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

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

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
		final Predicate<BigDecimal> requestedPredicate = value -> value.signum() > 0;
		final Formula body = new AttributeFormula(
			false, new AttributeKey("a"),
			new OrFormula(new IndexTaggedFormula(1, constant(1)), new IndexTaggedFormula(2, constant(2))),
			requestedPredicate
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

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
			new int[]{2}, ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED).compute().getArray()
		);
	}

	@DisplayName("Should still rebuild a conjunction of two wrapped unions")
	@Test
	void shouldStillRebuildAConjunctionOfTwoWrappedUnions() {
		final Formula body = new AndFormula(
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(1, 2)), new IndexTaggedFormula(2, constant(3)))),
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(2)), new IndexTaggedFormula(2, constant(1))))
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

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
				final Formula actual = ReferenceBodyTransposer.transpose(body.formula(), body::indexes);
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
				owners(ReferenceBodyTransposer.transpose(body, List::of)),
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
				owners(ReferenceBodyTransposer.transpose(body, () -> scope)),
				"A \\ {1} = {2} and B \\ {2} = {3}; owner 2 matches through its row in A"
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
			final Formula transposed = ReferenceBodyTransposer.transpose(body, List::of);
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
			final int indexCount = 1 + random.nextInt(12);
			final Map<Integer, Set<Integer>> ownersByIndex = new HashMap<>(indexCount * 2);
			final List<Integer> indexPks = new ArrayList<>(indexCount);
			final List<EntityIndex> indexes = new ArrayList<>(indexCount);
			for (int i = 0; i < indexCount; i++) {
				final Integer indexPk = FIRST_INDEX_PK + i;
				final Set<Integer> owners = randomSubset(random, allOwners(), 5 + random.nextInt(16));
				indexPks.add(indexPk);
				ownersByIndex.put(indexPk, owners);
				indexes.add(index(indexPk, owners));
			}
			Formula formula;
			do {
				formula = node(random, 3, indexPks, ownersByIndex);
			} while (formula instanceof EmptyFormula);
			return new RandomBody(formula, indexes);
		}

		/**
		 * Generates one node.
		 *
		 * @param random        source of randomness
		 * @param depth         remaining depth
		 * @param indexPks      the index family
		 * @param ownersByIndex owners of every index
		 * @return the node, possibly {@link EmptyFormula#INSTANCE}
		 */
		@Nonnull
		private static Formula node(
			@Nonnull Random random,
			int depth,
			@Nonnull List<Integer> indexPks,
			@Nonnull Map<Integer, Set<Integer>> ownersByIndex
		) {
			final int roll = random.nextInt(100);
			if (depth == 0 || roll < 30) {
				return roll % 4 == 0 ?
					constant(randomSubset(random, allOwners(), random.nextInt(12))) :
					perIndexConstraint(random, indexPks, ownersByIndex);
			}
			if (roll < 50) {
				final Formula inner = node(random, depth - 1, indexPks, ownersByIndex);
				// translation never nests one placeholder inside another - `NotTranslator` collapses `not(not(x))`
				// and `not(or(a, not(b)))` at the only level where both are still visible - so neither does this
				return inner instanceof EmptyFormula || containsFutureNot(inner) ? inner : new FutureNotFormula(inner);
			}
			final Formula[] children = new Formula[2 + random.nextInt(2)];
			for (int i = 0; i < children.length; i++) {
				children[i] = node(random, depth - 1, indexPks, ownersByIndex);
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
				case 1 -> perIndex.get(0);
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
