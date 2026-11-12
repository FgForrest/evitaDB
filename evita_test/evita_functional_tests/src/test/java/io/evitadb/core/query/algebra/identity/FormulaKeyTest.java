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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached identity, reference-pair comparison bounds and scope lifetime.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula key equality and comparison scopes")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaKeyTest {
	/** Number of threads comparing the same graphs at once. */
	private static final int COMPARING_THREADS = 8;

	/** Builds a leaf with a token input. */
	@Nonnull
	private static FormulaKey tokenKey(@Nonnull IdentityToken token, @Nonnull Object owner) {
		return FormulaKey.of(new TokenIdentity(token), 1, FormulaKey.NO_CHILDREN, owner);
	}

	/**
	 * Token-only test identity.
	 *
	 * @param token operand
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record TokenIdentity(@Nonnull IdentityToken token) implements IdentityRecord {
		/** Returns a synthetic identity id unrelated to any formula class. */
		@Override
		public long classId() { return 1; }

		/** Describes the operand. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.token); }
	}

	/**
	 * Counted record with two side keys; leaf values deliberately collide to exercise deep unequal comparisons.
	 *
	 * @param left first side key
	 * @param right second side key
	 * @param leaf leaf discriminator
	 * @param counter equality call counter
	 * @param fails whether equality throws
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record CountedIdentity(
		@Nullable FormulaKey left, @Nullable FormulaKey right, int leaf,
		@Nonnull AtomicInteger counter, boolean fails
	) implements IdentityRecord {
		/** Returns a synthetic record id. */
		@Override
		public long classId() { return 2; }

		/** Describes side keys with a deliberately colliding leaf hash. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.left);
			accumulator.add(this.right);
		}

		/** Counts actual record comparisons and evaluates the conjunction in declared order. */
		@Override
		public boolean equals(@Nullable Object other) {
			this.counter.incrementAndGet();
			if (this.fails) { throw new IllegalStateException("Record comparison failure."); }
			return other instanceof final CountedIdentity that && this.leaf == that.leaf &&
				(this.left == that.left || this.left != null && this.left.equals(that.left)) &&
				(this.right == that.right || this.right != null && this.right.equals(that.right));
		}

		/** Provides a hash consistent with the deliberately colliding record values. */
		@Override
		public int hashCode() { return 0; }
	}

	/** Builds one counted leaf or side-key node. */
	@Nonnull
	private static FormulaKey counted(
		@Nullable FormulaKey left, @Nullable FormulaKey right, int leaf, @Nonnull AtomicInteger counter, boolean fails
	) {
		return FormulaKey.of(new CountedIdentity(left, right, leaf, counter, fails), 2,
			FormulaKey.NO_CHILDREN, new Object());
	}

	/** Builds symmetric or asymmetric equal DAGs, sharing only their declared predecessor references. */
	@Nonnull
	private static FormulaKey[] graphs(int depth, boolean asymmetric, boolean unequal, @Nonnull AtomicInteger counter) {
		FormulaKey p = counted(null, null, 0, counter, false);
		FormulaKey q = counted(null, null, unequal ? 1 : 0, counter, false);
		FormulaKey r = counted(null, null, 0, counter, false);
		for (int i = 0; i < depth; i++) {
			final FormulaKey nextP = counted(p, p, 0, counter, false);
			final FormulaKey nextQ = counted(q, asymmetric ? r : q, 0, counter, false);
			final FormulaKey nextR = counted(q, r, 0, counter, false);
			p = nextP;
			q = nextQ;
			r = nextR;
		}
		return new FormulaKey[]{p, q};
	}

	/** Checks the counted linear bound under a timeout that only guards against an unbounded regression. */
	private static void assertBound(int depth, boolean asymmetric, boolean unequal) {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final AtomicInteger counter = new AtomicInteger();
			final FormulaKey[] pair = graphs(depth, asymmetric, unequal, counter);
			assertEquals(!unequal, pair[0].equals(pair[1]));
			assertTrue(counter.get() <= 4 * depth + 4,
				"Record calls: " + counter.get() + ", bound: " + (4 * depth + 4));
		});
	}

	/** Verifies the symmetric doubling acceptance case. */
	@Test
	@DisplayName("Bounds symmetric doubling at depth 40")
	void shouldBoundSymmetricDoubling() { assertBound(40, false, false); }

	/** Verifies both asymmetric acceptance depths. */
	@Test
	@DisplayName("Bounds asymmetric DAGs at depths 20 and 200")
	void shouldBoundAsymmetricDag() {
		assertBound(20, true, false);
		assertBound(200, true, false);
	}

	/** Verifies early failure at the deepest unequal leaf. */
	@Test
	@DisplayName("Bounds deepest-leaf inequality")
	void shouldBoundDeepInequality() {
		assertBound(20, true, true);
		assertBound(40, true, true);
		assertBound(200, true, true);
	}

	/** Verifies independent scopes when all threads compare the same graphs at the same time. */
	@Test
	@DisplayName("Compares the same DAGs concurrently on eight threads")
	void shouldCompareOnEightThreads() throws Exception {
		final FormulaKey[] pair = graphs(200, true, false, new AtomicInteger());
		final CountDownLatch started = new CountDownLatch(COMPARING_THREADS);
		final List<Callable<Boolean>> tasks = new ArrayList<>(COMPARING_THREADS);
		for (int i = 0; i < COMPARING_THREADS; i++) {
			tasks.add(() -> {
				// each task waits for all the others, so the comparisons overlap instead of running in sequence
				started.countDown();
				assertTrue(started.await(30, TimeUnit.SECONDS), "Not all comparing threads started.");
				return pair[0].equals(pair[1]);
			});
		}

		try (final ExecutorService executor = Executors.newFixedThreadPool(COMPARING_THREADS)) {
			final List<Future<Boolean>> results = new ArrayList<>(COMPARING_THREADS);
			for (final Callable<Boolean> task : tasks) {
				results.add(executor.submit(task));
			}
			for (final Future<Boolean> result : results) {
				assertTrue(result.get(30, TimeUnit.SECONDS));
			}
		}
	}

	/** Checks that a comparison ending with an exception still closes its scope on the comparing thread. */
	@Test
	@DisplayName("Clears the comparison scope after a comparison throws")
	void shouldClearScopeAfterFailedComparison() {
		final AtomicInteger counter = new AtomicInteger();
		final FormulaKey first = counted(counted(null, null, 0, counter, true), null, 0, counter, false);
		final FormulaKey second = counted(counted(null, null, 0, counter, true), null, 0, counter, false);
		assertThrows(IllegalStateException.class, () -> first.equals(second));

		final FormulaKey[] pair = graphs(2, false, false, counter);
		assertEquals(pair[0], pair[1]);
		assertFalse(ComparisonScope.current().contains(pair[0], pair[1]),
			"An outermost comparison must release its proven pairs.");
	}

	/** Checks owner-bound opacity, side propagation and child locality. */
	@Test
	@DisplayName("Binds opaque keys to their holder and distinguishes side keys from children")
	void shouldBindOpaqueKeysToOwner() throws Exception {
		final Object owner = new Object();
		final FormulaKey key = tokenKey(IdentityToken.OPAQUE, owner);
		assertEquals(key, tokenKey(IdentityToken.OPAQUE, owner));
		assertEquals(key.hashCode(), tokenKey(IdentityToken.OPAQUE, owner).hashCode());
		assertNotEquals(key, tokenKey(IdentityToken.OPAQUE, new Object()));
		assertTrue(key.isOpaque());
		assertTrue(key.isQueryLocal());
		final FormulaKey parent = FormulaKey.of(new TokenIdentity(null), 1, new FormulaKey[]{key}, new Object());
		assertFalse(parent.isOpaque());
		assertTrue(parent.isQueryLocal());
		assertEquals(parent, FormulaKey.of(new TokenIdentity(null), 1, new FormulaKey[]{key}, new Object()));
		final FormulaKey side = counted(key, null, 0, new AtomicInteger(), false);
		assertTrue(side.isOpaque());
		assertNotEquals(side, counted(key, null, 0, new AtomicInteger(), false));
		try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
			assertEquals(key, executor.submit(() -> tokenKey(IdentityToken.OPAQUE, owner)).get());
		}
	}

	/** Checks that opaque snapshots retain owner identity despite changed components and children. */
	@Test
	@DisplayName("Hashes opaque snapshots only by owner identity")
	void shouldHashOpaqueSnapshotsByOwner() {
		final Object owner = new Object();
		final FormulaKey first = FormulaKey.of(new OpaqueSnapshot(IdentityToken.OPAQUE, "a", null), 3,
			FormulaKey.NO_CHILDREN, owner);
		final FormulaKey side = tokenKey(IdentityToken.ofContent(new int[]{7}), new Object());
		final FormulaKey second = FormulaKey.of(new OpaqueSnapshot(IdentityToken.OPAQUE, "b".repeat(160), side), 3,
			new FormulaKey[]{side}, owner);
		assertEquals(first, second);
		assertEquals(first.getHash64(), second.getHash64());
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(FormulaKey.of(new TokenIdentity(null), 1, new FormulaKey[]{first}, new Object()),
			FormulaKey.of(new TokenIdentity(null), 1, new FormulaKey[]{second}, new Object()));
		assertTrue(second.getWeight() > first.getWeight());
		assertTrue(second.isQueryLocal());
		assertTrue(second.hasSideKeys());
	}

	/**
	 * Opaque snapshot with independently changing retained inputs.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record OpaqueSnapshot(
		@Nonnull IdentityToken token, @Nonnull String text, @Nullable FormulaKey side
	) implements IdentityRecord {
		/** Returns a synthetic identifier. */
		@Override public long classId() { return 3; }

		/** Describes all retained inputs. */
		@Override public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.token);
			accumulator.add(this.text);
			accumulator.add(this.side);
		}
	}

	/** Checks that a large comparison does not leave oversized thread-local storage behind. */
	@Test
	@DisplayName("Releases grown comparison storage after outermost exit")
	void shouldReleaseGrownComparisonStorage() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final ComparisonScope scope = ComparisonScope.current();
			final long initialSize = GraphLayout.parseInstance(scope).totalSize();
			scope.enter();
			try {
				for (int i = 0; i < 2_048; i++) {
					scope.add(tokenKey(IdentityToken.ofContent(new int[]{i}), new Object()),
						tokenKey(IdentityToken.ofContent(new int[]{i}), new Object()));
				}
			} finally {
				scope.leave();
			}
			assertEquals(initialSize, GraphLayout.parseInstance(scope).totalSize());
		});
	}

	/** Checks that pairs sharing one right key spread over the table instead of forming one probe chain. */
	@Test
	@DisplayName("Records many pairs sharing one right key in linear time")
	void shouldRecordPairsSharingOneRightKeyInLinearTime() {
		final FormulaKey right = tokenKey(IdentityToken.ofContent(new int[]{0}), new Object());
		final FormulaKey[] lefts = new FormulaKey[250_000];
		for (int i = 0; i < lefts.length; i++) {
			lefts[i] = tokenKey(IdentityToken.ofContent(new int[]{0}), new Object());
		}

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			final ComparisonScope scope = ComparisonScope.current();
			scope.enter();
			try {
				for (final FormulaKey left : lefts) {
					scope.add(left, right);
				}
				for (final FormulaKey left : lefts) {
					assertTrue(scope.contains(left, right));
				}
			} finally {
				scope.leave();
			}
		});
	}

	/** Checks strict class identifiers, ordered children, and equality boundaries. */
	@Test
	@DisplayName("Checks class identifiers and preserves child positions")
	void shouldCheckClassAndChildOrder() {
		final FormulaKey first = tokenKey(IdentityToken.ofContent(new int[]{1}), new Object());
		final FormulaKey second = tokenKey(IdentityToken.ofContent(new int[]{2}), new Object());
		final FormulaKey parent = FormulaKey.of(new TokenIdentity(null), 1,
			new FormulaKey[]{first, second}, new Object());
		assertNotEquals(parent, FormulaKey.of(new TokenIdentity(null), 1,
			new FormulaKey[]{second, first}, new Object()));
		assertNotEquals(parent, first);
		assertFalse(first.equals(null));
		assertFalse(first.equals("key"));
		assertEquals(first, first);
		assertEquals(1, first.getClassId());
		assertThrows(GenericEvitaInternalError.class, () ->
			FormulaKey.of(new TokenIdentity(null), 2, FormulaKey.NO_CHILDREN, new Object()));
	}

	/** Verifies saturating weight on a retained DAG. */
	@Test
	@DisplayName("Saturates key weight on deep DAGs")
	void shouldSaturateWeight() {
		final FormulaKey[] pair = graphs(200, true, false, new AtomicInteger());
		assertEquals(Integer.MAX_VALUE, pair[0].getWeight());
		assertEquals(Integer.MAX_VALUE, pair[1].getWeight());
	}

	/** Builds and computes a tree over leaves without a declared identity, which any key request would fail. */
	@Test
	@DisplayName("Constructs and computes ordinary formulas without requesting keys")
	void shouldKeepFormulaConstructionIndependentOfKeys() {
		final AndFormula formula = new AndFormula(new UndeclaredFormula(), new UndeclaredFormula());
		assertArrayEquals(new int[0], formula.compute().getArray());
	}

	/** Checks the explicit defaults of every holder family on holders that never declare an identity. */
	@Test
	@DisplayName("Fails explicitly when a holder has no declared identity")
	void shouldRejectUndeclaredHolderIdentity() {
		final UndeclaredFormula formula = new UndeclaredFormula();
		assertThrows(GenericEvitaInternalError.class, formula::getKey);

		final HierarchyFilteringPredicate predicate = new UndeclaredHierarchyPredicate();
		assertThrows(GenericEvitaInternalError.class, predicate::getKey);
		assertThrows(GenericEvitaInternalError.class, predicate::identity);
		assertThrows(GenericEvitaInternalError.class, predicate::getClassId);

		final EntityToBitmapFilter filter = new UndeclaredEntityFilter();
		assertThrows(GenericEvitaInternalError.class, filter::identity);
		assertThrows(GenericEvitaInternalError.class, filter::getClassId);
	}

	/**
	 * Leaf formula that never declares an identity, so the throwing default stays observable whatever the production
	 * formulas declare.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class UndeclaredFormula extends AbstractFormula {
		/** Stable holder identifier. */
		private static final long CLASS_ID = 6979462139904334639L;

		/** Creates an initialized leaf without inner formulas. */
		private UndeclaredFormula() {
			this.initFields();
		}

		/** Contributes nothing beyond the class identifier to the legacy hash. */
		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			return 0L;
		}

		/** Returns this holder's identifier. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/** Produces no records. */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			return EmptyBitmap.INSTANCE;
		}

		/** Produces no records. */
		@Override
		public int getEstimatedCardinality() {
			return 0;
		}

		/** Charges one unit per processed record. */
		@Override
		public long getOperationCost() {
			return 1L;
		}

		/** Returns a fresh leaf; a leaf has no inner formulas to replace. */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			return new UndeclaredFormula();
		}
	}

	/**
	 * Hierarchy predicate that never declares an identity, so the interface defaults stay observable.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class UndeclaredHierarchyPredicate implements HierarchyFilteringPredicate {
		/** Has no state to initialize. */
		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			// the predicate accepts every node and needs no execution context
		}

		/** Returns a constant legacy hash. */
		@Override
		public long getHash() {
			return 0L;
		}

		/** Accepts every node. */
		@Override
		public boolean test(int value) {
			return true;
		}
	}

	/**
	 * Entity filter alternative that never declares an identity, so the interface defaults stay observable.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class UndeclaredEntityFilter implements EntityToBitmapFilter {
		/** Requires no entity content. */
		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}

		/** Matches no entity. */
		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			return EmptyBitmap.INSTANCE;
		}
	}
}
