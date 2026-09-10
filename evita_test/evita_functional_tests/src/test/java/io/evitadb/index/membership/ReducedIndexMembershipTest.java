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

package io.evitadb.index.membership;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the state machine of {@link ReducedIndexMembership} directly, at the boundaries the functional suites
 * cannot reach reliably.
 *
 * The functional counterpart, `ReducedIndexMembershipCompletenessTest`, drives this structure through real writes and
 * checks the result against the reduced indexes themselves. That is the right test for the *quantifier* — "every
 * boundary maintains the lookup" — but it can only reach the coverage threshold through tens of entities, and cannot
 * reach the premise guards at all, because the engine never re-registers a known index. This class covers what is
 * left: the exact boundary value, the hysteresis band, the guards, and the transactional lifecycle.
 *
 * The properties asserted here are the ones the structure's own invariant rests on:
 *
 * - `covered` and `residual` stay disjoint, and an index leaving coverage leaves no entry behind. Neither is
 *   detectable from the outside — the resolver probes each selected index and intersects its real members, so an
 *   entry naming an index the owner has left costs a wasted probe and nothing more — which is exactly why it has to
 *   be asserted here: an entry left behind is memory that coverage was dropped to reclaim, and the bookkeeping drift
 *   it starts is what eventually loses an index from **both** sets;
 * - a crossing is paid at most once per half a threshold's worth of writes, which is the amortised-`O(1)` claim the
 *   class javadoc makes and nothing else measures;
 * - commit carries every sub-structure forward and rollback rewinds every sub-structure, together. A partial rewind is
 *   the single way this structure can end up claiming coverage it cannot honour.
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReducedIndexMembership")
@Tag(INDEXING)
@Tag(TRANSACTION)
class ReducedIndexMembershipTest {

	/**
	 * Small threshold used by the boundary tests, so a crossing costs four owners rather than sixteen.
	 */
	private static final int SMALL_THRESHOLD = 4;

	/**
	 * Primary key standing for the reduced index under test; the value itself carries no meaning.
	 */
	private static final int INDEX_PK = 1_000;

	/**
	 * Tests pinning where an index lands when it first becomes known, and the premise guards that stop it being
	 * re-decided behind entries built from a membership that has since moved on.
	 */
	@Nested
	@DisplayName("Registration and the coverage boundary")
	class Registration {

		@Test
		@DisplayName("an index of exactly the threshold is covered")
		void registerIndexAtThresholdIsCovered() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2, 3, 4));

			assertEquals(Set.of(INDEX_PK), toSet(tested.getCoveredIndexPrimaryKeys()));
			assertEquals(Set.of(), toSet(tested.getResidualIndexPrimaryKeys()));
			assertEquals(Set.of(1, 2, 3, 4), toSet(tested.getCoveredOwners()));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getIndexPrimaryKeys(3)));
			assertTrue(tested.isKnown(INDEX_PK));
		}

		@Test
		@DisplayName("an index one owner above the threshold is residual")
		void registerIndexAboveThresholdIsResidual() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2, 3, 4, 5));

			assertEquals(Set.of(), toSet(tested.getCoveredIndexPrimaryKeys()));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getResidualIndexPrimaryKeys()));
			assertTrue(tested.getCoveredOwners().isEmpty());
			assertTrue(tested.isKnown(INDEX_PK));
		}

		@Test
		@DisplayName("an index with no members enters neither set and stays unknown")
		void registerIndexWithNoMembersLeavesItUnknown() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, EmptyBitmap.INSTANCE);

			// deliberate, and load-bearing for the caller: the only consumer treats "not covered, not residual" as
			// "walk it", so an empty index must not be made to look like a suppressed one
			assertFalse(tested.isKnown(INDEX_PK));
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("re-registering a covered index raises rather than stranding its entries")
		void reRegisteringAKnownIndexRaises() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.registerIndex(INDEX_PK, new BaseBitmap(2, 3)),
				"re-registering a covered index must refuse - the entries built from its previous membership " +
					"could not be forgotten from the new membership alone"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.registerIndexAsResidual(INDEX_PK),
				"a covered index must not be silently downgraded to residual behind its own entries"
			);
		}

		@Test
		@DisplayName("re-registering a residual index raises")
		void reRegisteringAKnownIndexAsResidualRaises() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndexAsResidual(INDEX_PK);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.registerIndexAsResidual(INDEX_PK)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.registerIndex(INDEX_PK, new BaseBitmap(1))
			);
		}

		@Test
		@DisplayName("unregistering clears coverage, residual knowledge, and an index never known")
		void unregisterIndexClearsBothSets() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2));
			tested.registerIndexAsResidual(INDEX_PK + 1);

			tested.unregisterIndex(INDEX_PK, new BaseBitmap(1, 2));
			assertFalse(tested.isKnown(INDEX_PK));
			assertTrue(tested.getCoveredOwners().isEmpty());
			assertSame(EmptyBitmap.INSTANCE, tested.getIndexPrimaryKeys(1));

			tested.unregisterIndex(INDEX_PK + 1, EmptyBitmap.INSTANCE);
			assertFalse(tested.isKnown(INDEX_PK + 1));

			// an index that was never known is a silent no-op, not a raise - `ownerRemoved` reaches this for an
			// index the map declined to cover
			tested.unregisterIndex(INDEX_PK + 2, new BaseBitmap(9));
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("an owner belonging to no covered index reads back as the empty bitmap")
		void unknownOwnerReturnsEmptyBitmap() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			// the identity the sibling resolver iterates without a null check
			assertSame(EmptyBitmap.INSTANCE, tested.getIndexPrimaryKeys(42));
		}
	}

	/**
	 * Tests driving an index across the coverage boundary in both directions, which is where an entry can be left
	 * behind naming an index the walk will also visit — or one that no longer exists.
	 */
	@Nested
	@DisplayName("Crossings and hysteresis")
	class Crossings {

		@Test
		@DisplayName("promotion out of coverage leaves no entry naming the promoted index")
		void promotionKeepsNoEntriesBehind() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			final Set<Integer> members = new TreeSet<>();
			for (int owner = 1; owner <= SMALL_THRESHOLD + 1; owner++) {
				members.add(owner);
				tested.ownerAdded(INDEX_PK, owner, bitmapOf(members));
			}

			assertEquals(Set.of(INDEX_PK), toSet(tested.getResidualIndexPrimaryKeys()));
			assertEquals(Set.of(), toSet(tested.getCoveredIndexPrimaryKeys()));
			assertTrue(
				tested.getCoveredOwners().isEmpty(),
				"a promoted index must leave no owner behind - an entry naming an index the walk also visits " +
					"would apply the facet twice, and one naming a dropped index would fail the write"
			);
			for (int owner = 1; owner <= SMALL_THRESHOLD + 1; owner++) {
				assertTrue(tested.getIndexPrimaryKeys(owner).isEmpty());
			}
		}

		@Test
		@DisplayName("the hysteresis band is crossed once, not once per write")
		void hysteresisBandDoesNotOscillate() {
			// the shipped threshold, because the amortisation claim is made about this value
			final int threshold = ReducedIndexMembership.DEFAULT_COVERAGE_THRESHOLD;
			final int demotion = threshold / 2;
			final ReducedIndexMembership tested = new ReducedIndexMembership(threshold);
			final Set<Integer> members = new TreeSet<>();
			for (int owner = 1; owner <= threshold + 1; owner++) {
				members.add(owner);
				tested.ownerAdded(INDEX_PK, owner, bitmapOf(members));
			}
			assertTrue(tested.getResidualIndexPrimaryKeys().contains(INDEX_PK));

			// shrinking through the band must not re-cover the index until the demotion threshold is reached
			for (int owner = threshold + 1; owner > demotion + 1; owner--) {
				members.remove(owner);
				tested.ownerRemoved(INDEX_PK, owner, bitmapOf(members));
				assertTrue(
					tested.getResidualIndexPrimaryKeys().contains(INDEX_PK),
					"index re-covered at " + members.size() + " owners, above the demotion threshold of " + demotion
				);
				assertTrue(tested.getCoveredOwners().isEmpty());
			}

			// exactly at the demotion threshold it comes back into coverage, with every owner it holds
			members.remove(demotion + 1);
			tested.ownerRemoved(INDEX_PK, demotion + 1, bitmapOf(members));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getCoveredIndexPrimaryKeys()));
			assertEquals(Set.of(), toSet(tested.getResidualIndexPrimaryKeys()));
			assertEquals(members, toSet(tested.getCoveredOwners()));

			// and growing back needs the full band again, so the crossing cost is amortised over `T/2` writes
			for (int owner = demotion + 1; owner <= threshold; owner++) {
				members.add(owner);
				tested.ownerAdded(INDEX_PK, owner, bitmapOf(members));
				assertTrue(
					tested.getCoveredIndexPrimaryKeys().contains(INDEX_PK),
					"index promoted at " + members.size() + " owners, at or below the threshold of " + threshold
				);
			}
			members.add(threshold + 1);
			tested.ownerAdded(INDEX_PK, threshold + 1, bitmapOf(members));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getResidualIndexPrimaryKeys()));
		}

		@Test
		@DisplayName("a threshold of one still demotes at one owner rather than at zero")
		void demotionThresholdFloorIsOneForThresholdOne() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(1);
			tested.ownerAdded(INDEX_PK, 1, new BaseBitmap(1));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getCoveredIndexPrimaryKeys()));

			tested.ownerAdded(INDEX_PK, 2, new BaseBitmap(1, 2));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getResidualIndexPrimaryKeys()));
			assertTrue(tested.getCoveredOwners().isEmpty());

			// `Math.max(1, 1 / 2)` is what keeps this reachable at all - a floor of zero would demote only on the
			// removal that also unregisters the index, so a threshold of one would never cover anything again
			tested.ownerRemoved(INDEX_PK, 2, new BaseBitmap(1));
			assertEquals(Set.of(INDEX_PK), toSet(tested.getCoveredIndexPrimaryKeys()));
			assertEquals(Set.of(1), toSet(tested.getCoveredOwners()));
		}

		@Test
		@DisplayName("the last owner leaving forgets the index entirely")
		void lastOwnerRemovalForgetsTheIndexEntirely() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2));
			tested.registerIndex(INDEX_PK + 1, new BaseBitmap(1));

			tested.ownerRemoved(INDEX_PK + 1, 1, EmptyBitmap.INSTANCE);
			assertFalse(tested.isKnown(INDEX_PK + 1));
			assertTrue(
				tested.getCoveredOwners().contains(1),
				"owner 1 still belongs to a covered index, so it must stay in the covered-owner union"
			);
			assertEquals(Set.of(INDEX_PK), toSet(tested.getIndexPrimaryKeys(1)));

			tested.ownerRemoved(INDEX_PK, 1, new BaseBitmap(2));
			assertFalse(tested.getCoveredOwners().contains(1));
			assertSame(EmptyBitmap.INSTANCE, tested.getIndexPrimaryKeys(1));

			tested.ownerRemoved(INDEX_PK, 2, EmptyBitmap.INSTANCE);
			assertFalse(tested.isKnown(INDEX_PK));
			assertTrue(tested.isEmpty());
		}
	}

	/**
	 * Tests verifying that all four sub-structures merge on commit and rewind on rollback together. A partial
	 * rewind is the one way this structure can claim coverage it cannot honour.
	 */
	@Nested
	@DisplayName("Transactional lifecycle")
	class TransactionalLifecycle {

		@Test
		@DisplayName("commit carries every sub-structure forward together")
		void commitCarriesEveryStructureForward() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2));

			assertStateAfterCommit(
				tested,
				original -> {
					original.ownerAdded(INDEX_PK, 3, new BaseBitmap(1, 2, 3));
					original.ownerRemoved(INDEX_PK, 1, new BaseBitmap(2, 3));
					original.registerIndexAsResidual(INDEX_PK + 1);
				},
				(original, committed) -> {
					// the original instance keeps the pre-transaction state, as every transactional structure does
					assertEquals(Set.of(1, 2), toSet(original.getCoveredOwners()));
					assertEquals(Set.of(), toSet(original.getResidualIndexPrimaryKeys()));

					assertEquals(Set.of(2, 3), toSet(committed.getCoveredOwners()));
					assertEquals(Set.of(INDEX_PK), toSet(committed.getCoveredIndexPrimaryKeys()));
					assertEquals(Set.of(INDEX_PK + 1), toSet(committed.getResidualIndexPrimaryKeys()));
					assertEquals(Set.of(INDEX_PK), toSet(committed.getIndexPrimaryKeys(3)));
					assertSame(EmptyBitmap.INSTANCE, committed.getIndexPrimaryKeys(1));
				}
			);
		}

		@Test
		@DisplayName("rollback rewinds every sub-structure together")
		void rollbackLeavesNothingBehind() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2));

			assertStateAfterRollback(
				tested,
				original -> {
					original.ownerAdded(INDEX_PK, 3, new BaseBitmap(1, 2, 3));
					original.ownerAdded(INDEX_PK, 4, new BaseBitmap(1, 2, 3, 4));
					// pushed over the threshold inside the transaction, so the rewind has to restore coverage too
					original.ownerAdded(INDEX_PK, 5, new BaseBitmap(1, 2, 3, 4, 5));
					original.registerIndexAsResidual(INDEX_PK + 1);
				},
				(original, notCommitted) -> {
					// a partial rewind here is the one way this structure claims coverage it cannot honour, so all
					// four sub-structures are checked rather than the two the promotion happened to touch
					assertEquals(Set.of(INDEX_PK), toSet(original.getCoveredIndexPrimaryKeys()));
					assertEquals(Set.of(), toSet(original.getResidualIndexPrimaryKeys()));
					assertEquals(Set.of(1, 2), toSet(original.getCoveredOwners()));
					assertEquals(Set.of(INDEX_PK), toSet(original.getIndexPrimaryKeys(1)));
					assertSame(EmptyBitmap.INSTANCE, original.getIndexPrimaryKeys(5));
				}
			);
		}
	}

	/**
	 * Tests guarding the heap estimate this structure contributes to the owning index's own reporting.
	 */
	@Nested
	@DisplayName("Memory accounting")
	class MemoryAccounting {

		@Test
		@DisplayName("the reported heap size grows with coverage")
		void heapSizeGrowsWithCoverage() {
			final ReducedIndexMembership tested = new ReducedIndexMembership(SMALL_THRESHOLD);
			final long empty = tested.getHeapSizeInBytes();
			assertTrue(empty > 0, "an empty map still occupies its own header and four sub-structures");

			tested.registerIndex(INDEX_PK, new BaseBitmap(1, 2, 3, 4));
			assertTrue(
				tested.getHeapSizeInBytes() > empty,
				"coverage entries must be accounted for - this figure feeds GlobalEntityIndex#getHeapSizeInBytes " +
					"and hence the engine's own memory reporting"
			);
		}
	}

	/**
	 * Materialises a bitmap as a set, so an assertion failure prints something a reader can act on.
	 *
	 * @param bitmap the bitmap to materialise
	 * @return its contents
	 */
	@Nonnull
	private static Set<Integer> toSet(@Nonnull Bitmap bitmap) {
		final Set<Integer> result = new TreeSet<>();
		final OfInt it = bitmap.iterator();
		while (it.hasNext()) {
			result.add(it.nextInt());
		}
		return result;
	}

	/**
	 * Builds the member bitmap a maintenance call would pass in, from the owners the caller is tracking.
	 *
	 * @param members the owners the reduced index holds after the operation
	 * @return the members as a bitmap
	 */
	@Nonnull
	private static Bitmap bitmapOf(@Nonnull Set<Integer> members) {
		final int[] values = new int[members.size()];
		int index = 0;
		for (final Integer member : members) {
			values[index++] = member;
		}
		return new BaseBitmap(values);
	}

}
