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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.identity.ConstructionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the detached keys of real {@link HierarchyFilteringPredicate} holders: operand and operation sensitivity,
 * locality propagation through planned filters, and bounded comparison of deeply shared predicate graphs.
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

	/** A planned opaque constant propagates locality through its superset intersection and telemetry wrapper. */
	@ParameterizedTest
	@ValueSource(strings = {"node filter", "hierarchy requirements",
		"referenced node filter", "referenced hierarchy requirements"})
	@DisplayName("Propagates large primary-key filter locality through filter-only planning")
	void shouldPropagatePlannedFilterLocality(@Nonnull String modeName) {
		final ConstructionMode<FilteringFormulaHierarchyEntityPredicate> mode =
			new FilteringFormulaHierarchyEntityPredicateIdentityFixture().modes().get(modeName);
		assertFalse(mode.base().get().getKey().isQueryLocal(), modeName);
		final FilteringFormulaHierarchyEntityPredicate large =
			mode.variants().get("large primary key filter").get();
		assertFalse(large.getKey().isOpaque(), modeName);
		assertTrue(large.getKey().isQueryLocal(), modeName);
	}

	/** Equal leaves preserve operation discrimination between locale and general filtering predicates. */
	@Test
	@DisplayName("Distinguishes predicate holder classes over equal formulas")
	void shouldDistinguishLocaleAndFilteringPredicates() {
		final GlobalEntityIndex index = new GlobalEntityIndex(
			1, "category", new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		final LocaleHierarchyEntityPredicate locale = new LocaleHierarchyEntityPredicate(index, Locale.ENGLISH);
		final FilteringFormulaHierarchyEntityPredicate filter = new FilteringFormulaHierarchyEntityPredicate(
			"category", Set.of(Scope.LIVE), index.getRecordsWithLanguageFormula(Locale.ENGLISH));
		assertNotEquals(locale.getKey(), filter.getKey());
		assertNotEquals(locale.getKey().getHash64(), filter.getKey().getHash64());
	}

	/** The symmetric and asymmetric doubling graphs must compare without expanding their paths. */
	@Test
	@DisplayName("Compares shared predicate DAGs at depths 20, 40 and 200")
	void shouldCompareRealHolderDoublingDags() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			for (final int depth : new int[]{20, 40, 200}) {
				final Operands[] predicates = predicateGraphs(depth, depth != 40, false);
				final FormulaKey[] keys = {predicates[0].predicate().getKey(), predicates[1].predicate().getKey()};
				HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
				assertEquals(keys[0], keys[1]);
				// the leaves are distinct holders, so equality must have been proven on at least one of them
				assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > 0);
				assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] <= 4 * depth + 4);
				HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
				assertEquals(keys[0], keys[1]);
				assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > 0);
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
			final List<Future<Boolean>> comparisons = new ArrayList<>(8);
			for (int i = 0; i < 8; i++) {
				comparisons.add(executor.submit(() -> keys[0].equals(keys[1])));
			}
			for (final Future<Boolean> comparison : comparisons) {
				assertTrue(comparison.get(30, TimeUnit.SECONDS));
			}
		}
	}

	/** A difference confined to a deepest leaf reaches the root hash, so no leaf needs to be compared. */
	@Test
	@DisplayName("Rejects a deepest-leaf difference by the hash prefilter without comparing leaves")
	void shouldRejectDeepLeafDifferenceByHashPrefilter() {
		final Operands[] predicates = predicateGraphs(200, true, true);
		final FormulaKey left = predicates[0].predicate().getKey();
		final FormulaKey right = predicates[1].predicate().getKey();
		HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
		assertNotEquals(left.getHash64(), right.getHash64());
		assertNotEquals(left, right);
		assertEquals(0, HierarchyPredicateLeafFormula.COMPARISONS.get()[0]);
	}

	/**
	 * Traversing the same real holder operands without remembering proven pairs exceeds the linear bound.
	 * The traversal stops once the count exceeds that bound, rather than expanding all remaining paths.
	 */
	@Test
	@DisplayName("Exceeds the leaf-comparison bound when operand memoization is bypassed")
	void shouldExceedBoundWithoutOperandMemoization() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final Operands[] predicates = predicateGraphs(20, true, false);
			assertEquals(predicates[0].predicate().getKey(), predicates[1].predicate().getKey());
			HierarchyPredicateLeafFormula.COMPARISONS.get()[0] = 0;
			assertFalse(compareWithoutOperandMemoization(predicates[0], predicates[1], 84));
			assertTrue(HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > 84);
		});
	}

	/** Compares the asymmetric shape through memoized detached side keys. */
	@Nonnull
	private static FormulaKey[] asymmetricKeys(int depth) {
		final Operands[] predicates = predicateGraphs(depth, true, false);
		return new FormulaKey[]{predicates[0].predicate().getKey(), predicates[1].predicate().getKey()};
	}

	/** Builds distinct real predicate holders with only the sharing shown by each graph's recurrence. */
	@Nonnull
	private static Operands[] predicateGraphs(int depth, boolean asymmetric, boolean unequal) {
		Operands p = countedLeaf(1);
		Operands q = countedLeaf(unequal ? 2 : 1);
		Operands r = countedLeaf(1);
		for (int i = 0; i < depth; i++) {
			final Operands nextP = p.and(p);
			final Operands nextQ = q.and(asymmetric ? r : q);
			final Operands nextR = q.and(r);
			p = nextP;
			q = nextQ;
			r = nextR;
		}
		return new Operands[]{p, q};
	}

	/** Walks the conjunction operands directly, comparing each encountered leaf through its detached key. */
	private static boolean compareWithoutOperandMemoization(
		@Nonnull Operands left, @Nonnull Operands right, int limit
	) {
		if (HierarchyPredicateLeafFormula.COMPARISONS.get()[0] > limit) {
			return false;
		}
		if (left.first() != null && left.second() != null && right.first() != null && right.second() != null) {
			return compareWithoutOperandMemoization(left.first(), right.first(), limit) &&
				compareWithoutOperandMemoization(left.second(), right.second(), limit);
		}
		return left.predicate().getKey().equals(right.predicate().getKey());
	}

	/** Wraps an executable constant leaf in the real general filtering predicate. */
	@Nonnull
	private static Operands countedLeaf(int value) {
		return new Operands(
			new FilteringFormulaHierarchyEntityPredicate(
				"category", Set.of(Scope.LIVE), new HierarchyPredicateLeafFormula(new BaseBitmap(value))),
			null, null
		);
	}

	/**
	 * Real predicate together with the operands it was combined from, so a comparison can walk the conjunctions
	 * without reading their private state.
	 *
	 * @param predicate the real predicate holder
	 * @param first left operand of the conjunction, null for a leaf
	 * @param second right operand of the conjunction, null for a leaf
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record Operands(
		@Nonnull HierarchyFilteringPredicate predicate,
		@Nullable Operands first,
		@Nullable Operands second
	) {
		/** Combines both operands into the real conjunction built by {@link HierarchyFilteringPredicate#and}. */
		@Nonnull
		Operands and(@Nonnull Operands other) {
			return new Operands(this.predicate.and(other.predicate), this, other);
		}
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
		public HierarchyPredicateLeafFormula(@Nonnull Bitmap bitmap) {
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
