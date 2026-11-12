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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.evitadb.core.query.algebra.identity.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import io.evitadb.dataType.Scope;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached computation identity on real holders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Hierarchy predicate identity")
@Tag(ENGINE)
@Tag(CACHE)
class HierarchyFilteringPredicateKeyTest {
	/** Predicate operands and operation kinds both contribute to identity. */
	@Test
	@DisplayName("Distinguishes predicate operands and operations")
	void shouldDistinguishPredicateInputs() {
		final HierarchyFilteringPredicate first = new MatchNodeIdHierarchyFilteringPredicate(1);
		final HierarchyFilteringPredicate second = new MatchNodeIdHierarchyFilteringPredicate(2);
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		assertNotEquals(first.and(second).getKey(), first.or(second).getKey());
		assertNotEquals(first.getKey(), first.negate().getKey());
	}

	/** Filter-only and ancestor-expanding constructor paths have different computation identities. */
	@Test
	@DisplayName("Distinguishes having from any-having over the same node filter")
	void shouldDistinguishHavingAndAnyHaving() {
		final java.util.Map<String,
			io.evitadb.test.identity.ConstructionMode<FilteringFormulaHierarchyEntityPredicate>> modes =
			new FilteringFormulaHierarchyEntityPredicateIdentityFixture().modes();
		final FilteringFormulaHierarchyEntityPredicate having = modes.get("node filter").base().get();
		final FilteringFormulaHierarchyEntityPredicate anyHaving = modes.get("any child").opaqueVariants()
			.get("child filter").get();
		assertFalse(having.getKey().isOpaque());
		assertTrue(anyHaving.getKey().isOpaque());
		assertNotEquals(having.getKey(), anyHaving.getKey());
	}

	/** Equal leaves preserve operation discrimination between locale and general filtering predicates. */
	@Test
	@DisplayName("Distinguishes predicate holder classes over equal formulas")
	void shouldDistinguishLocaleAndFilteringPredicates() {
		final io.evitadb.index.GlobalEntityIndex index = new io.evitadb.index.GlobalEntityIndex(
			1, "category", new io.evitadb.index.EntityIndexKey(
				io.evitadb.api.index.EntityIndexType.GLOBAL, Scope.LIVE));
		final LocaleHierarchyEntityPredicate locale = new LocaleHierarchyEntityPredicate(index,
			java.util.Locale.ENGLISH);
		final FilteringFormulaHierarchyEntityPredicate filter = new FilteringFormulaHierarchyEntityPredicate(
			"category", Set.of(Scope.LIVE), index.getRecordsWithLanguageFormula(java.util.Locale.ENGLISH));
		assertNotEquals(locale.getKey(), filter.getKey());
		assertNotEquals(locale.getKey().getHash64(), filter.getKey().getHash64());
	}

	/** The symmetric and asymmetric doubling graphs must compare without expanding their paths. */
	@Test
	@DisplayName("Compares shared predicate DAGs at depths 20, 40 and 200")
	void shouldCompareRealHolderDoublingDags() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			for (final int depth : new int[]{20, 40, 200}) {
				final HierarchyFilteringPredicate[] predicates = predicateGraphs(depth, depth != 40, false);
				final FormulaKey[] keys = {predicates[0].getKey(), predicates[1].getKey()};
				HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
				assertEquals(keys[0], keys[1]);
				assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] <= 4 * depth + 4);
				HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
				assertEquals(keys[0], keys[1]);
				assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] <= 4 * depth + 4);
				if (depth >= 40) {
					assertEquals(Integer.MAX_VALUE, keys[0].getWeight());
				}
			}
		});
	}

	/** Comparing the same detached graphs from eight threads must not share mutable comparison state. */
	@Test
	@DisplayName("Compares the same predicate keys concurrently")
	void shouldComparePredicateDagsConcurrently() throws Exception {
		final FormulaKey[] keys = asymmetricKeys(200);
		try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final java.util.List<Future<Boolean>> comparisons = new java.util.ArrayList<>();
			for (int i = 0; i < 8; i++) {
				comparisons.add(executor.submit(() -> keys[0].equals(keys[1])));
			}
			for (final Future<Boolean> comparison : comparisons) {
				assertTrue(comparison.get(30, TimeUnit.SECONDS));
			}
		}
	}

	/** Compares the asymmetric shape through memoized detached side keys. */
	@Nonnull
	private static FormulaKey[] asymmetricKeys(int depth) {
		final HierarchyFilteringPredicate[] predicates = predicateGraphs(depth, true, false);
		return new FormulaKey[]{predicates[0].getKey(), predicates[1].getKey()};
	}

	/** Builds distinct real predicate holders with only the sharing shown by each graph's recurrence. */
	@Nonnull
	private static HierarchyFilteringPredicate[] predicateGraphs(int depth, boolean asymmetric, boolean unequal) {
		HierarchyFilteringPredicate p = countedLeaf(1);
		HierarchyFilteringPredicate q = countedLeaf(unequal ? 2 : 1);
		HierarchyFilteringPredicate r = countedLeaf(1);
		for (int i = 0; i < depth; i++) {
			final HierarchyFilteringPredicate nextP = p.and(p);
			final HierarchyFilteringPredicate nextQ = q.and(asymmetric ? r : q);
			final HierarchyFilteringPredicate nextR = q.and(r);
			p = nextP;
			q = nextQ;
			r = nextR;
		}
		return new HierarchyFilteringPredicate[]{p, q};
	}

	/** A change confined to a deepest leaf must stay bounded and unequal. */
	@Test
	@DisplayName("Rejects a deepest-leaf difference within the comparison bound")
	void shouldBoundUnequalPredicateDags() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final HierarchyFilteringPredicate[] predicates = predicateGraphs(200, true, true);
			final FormulaKey left = predicates[0].getKey();
			final FormulaKey right = predicates[1].getKey();
			HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
			assertNotEquals(left, right);
			assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] <= 804);
		});
	}

	/**
	 * Traversing the same real holder operands without remembering proven pairs exceeds the linear bound.
	 * The traversal stops once the count exceeds that bound, rather than expanding all remaining paths.
	 */
	@Test
	@DisplayName("Exceeds the leaf-comparison bound when operand memoization is bypassed")
	void shouldExceedBoundWithoutOperandMemoization() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final HierarchyFilteringPredicate[] predicates = predicateGraphs(20, true, false);
			assertEquals(predicates[0].getKey(), predicates[1].getKey());
			HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
			assertFalse(compareWithoutOperandMemoization(predicates[0], predicates[1], 84));
			assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > 84);
		});
	}

	/** Walks real conjunction operands directly, comparing each encountered leaf through its detached key. */
	private static boolean compareWithoutOperandMemoization(
		@Nonnull HierarchyFilteringPredicate left, @Nonnull HierarchyFilteringPredicate right, int limit
	) throws ReflectiveOperationException {
		if (HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > limit) {
			return false;
		}
		if (left instanceof HierarchyFilteringPredicate.AndHierarchyFilteringPredicate &&
			right instanceof HierarchyFilteringPredicate.AndHierarchyFilteringPredicate) {
			final java.lang.reflect.Field first = left.getClass().getDeclaredField("first");
			final java.lang.reflect.Field second = left.getClass().getDeclaredField("second");
			first.setAccessible(true);
			second.setAccessible(true);
			return compareWithoutOperandMemoization(
				(HierarchyFilteringPredicate) first.get(left), (HierarchyFilteringPredicate) first.get(right), limit) &&
				compareWithoutOperandMemoization(
					(HierarchyFilteringPredicate) second.get(left),
						(HierarchyFilteringPredicate) second.get(right), limit);
		}
		return left.getKey().equals(right.getKey());
	}

	/** Wraps an executable constant leaf in the real general filtering predicate. */
	@Nonnull
	private static HierarchyFilteringPredicate countedLeaf(int value) {
		return new FilteringFormulaHierarchyEntityPredicate(
			"category", Set.of(Scope.LIVE), new HierarchyPredicateLeafFormula(new BaseBitmap(value)));
	}

	/**
	 * Executable constant leaf whose detached record counts comparisons on the comparing thread.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public static class HierarchyPredicateLeafFormula extends ConstantFormula {
		/** Stable discriminator of this executable test leaf. */
		private static final long CLASS_ID = 7811415314548036107L;
		/** Thread-confined count of leaf record comparisons. */
		private static final ThreadLocal<int[]> COMPARISONS = ThreadLocal.withInitial(() -> new int[1]);

		/** Captures a real bitmap operand with the constant-formula computation. */
		public HierarchyPredicateLeafFormula(@Nonnull io.evitadb.index.bitmap.Bitmap bitmap) {
			super(bitmap);
		}

		/** Captures the same detached bitmap identity as the constant computation. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			return new Identity(getDelegateIdentityToken());
		}

		/** Returns this leaf's discriminator. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached bitmap input with observable equality work.
		 *
		 * @param delegateIdentity detached bitmap operand
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity(@Nonnull IdentityToken delegateIdentity) implements IdentityRecord {
			/** Returns the leaf discriminator. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Describes the entire bitmap operand. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.delegateIdentity);
			}

			/** Counts one comparison before comparing the detached operand. */
			@Override
			public boolean equals(@Nullable Object other) {
				COMPARISONS.get()[0]++;
				return other instanceof Identity that && this.delegateIdentity.equals(that.delegateIdentity);
			}
		}
	}
}
