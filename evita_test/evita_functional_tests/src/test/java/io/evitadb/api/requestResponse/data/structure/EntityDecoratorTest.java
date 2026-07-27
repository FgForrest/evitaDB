/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.EntityPrimaryKeyAwareComparator;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering the static helpers on `EntityDecorator`. Lives in the same package so
 * package-protected members can be exercised directly without reflection.
 */
@DisplayName("EntityDecorator")
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(COMPARATOR)
class EntityDecoratorTest {

	/**
	 * Builds a minimal `ReferenceDecorator` stub whose only behavioural surface is `exists()` and
	 * `getReferenceName()` — the only methods `ReferenceContractSerializablePredicate` calls during
	 * `sortAndFilterSubList`'s in-place filtering phase. The comparator fakes used below never touch the
	 * references themselves (they return 0 / record metadata), so no further stubbing is required.
	 */
	@Nonnull
	private static ReferenceDecorator stubReference(@Nonnull String name) {
		final ReferenceDecorator mock = Mockito.mock(ReferenceDecorator.class);
		when(mock.exists()).thenReturn(true);
		when(mock.getReferenceName()).thenReturn(name);
		return mock;
	}

	/**
	 * Comparator fake that is NOT `EntityPrimaryKeyAwareComparator` and whose only job is to:
	 * - return 0 for every pair (no actual reordering — `Arrays.sort` becomes a no-op);
	 * - simulate a real comparator that accumulates `nonSortedCount` additional unsorted
	 *   references during the just-finished sort pass, so the chain-advance loop in
	 *   `sortAndFilterSubList` sees a positive delta and moves on to the next link.
	 *
	 * The cumulative semantic mirrors concrete comparators such as
	 * `EntityNestedQueryComparator` whose `nonSortedReferences` set only grows. The first
	 * call (the "before sort" snapshot) returns 0; subsequent calls return `nonSortedCount`,
	 * so the per-pass delta equals `nonSortedCount`.
	 */
	private static final class HeadFakeComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -1761005013461764433L;

		@Nullable private final ReferenceComparator next;
		private final int nonSortedCount;
		private int invocationIndex;

		HeadFakeComparator(@Nullable ReferenceComparator next, int nonSortedCount) {
			this.next = next;
			this.nonSortedCount = nonSortedCount;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			// first call mimics the "before sort" snapshot (no accumulator entries yet),
			// subsequent calls mimic the cumulative reading after the sort pass populated
			// the comparator's `nonSortedReferences` set
			final int value = this.invocationIndex == 0 ? 0 : this.nonSortedCount;
			this.invocationIndex++;
			return value;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake whose `getNonSortedReferenceCount()` grows monotonically across calls,
	 * mimicking a real comparator (e.g. `EntityNestedQueryComparator`) that maintains a
	 * lazy-init `nonSortedReferences` set and never resets it between sort passes. Used to
	 * exercise the case where `sortAndFilterSubList` reads the comparator's counter as if it
	 * were a per-pass reading but the implementation keeps an absolute, cumulative value.
	 *
	 * The comparator chains to `next` so the chain-advance loop will iterate at least twice
	 * — that's where the absolute-vs-delta confusion shows up as out-of-range sort indices.
	 */
	private static final class GrowingNonSortedCountComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -8316411802194620181L;

		@Nullable private final ReferenceComparator next;
		private final int[] reportedCountsPerInvocation;
		private int invocationIndex;

		GrowingNonSortedCountComparator(
			@Nullable ReferenceComparator next,
			@Nonnull int[] reportedCountsPerInvocation
		) {
			this.next = next;
			this.reportedCountsPerInvocation = reportedCountsPerInvocation;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			final int index = Math.min(this.invocationIndex, this.reportedCountsPerInvocation.length - 1);
			final int value = this.reportedCountsPerInvocation[index];
			this.invocationIndex++;
			return value;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake whose `getNonSortedReferenceCount()` returns the same value on every call,
	 * representing a well-behaved comparator that does NOT accumulate state across sort passes.
	 * Used as the control case to confirm correctly-behaving comparators are not regressed.
	 */
	private static final class StableNonSortedCountComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -7193620345109248123L;

		@Nullable private final ReferenceComparator next;
		private final int stableCount;

		StableNonSortedCountComparator(@Nullable ReferenceComparator next, int stableCount) {
			this.next = next;
			this.stableCount = stableCount;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return this.stableCount;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake that IS `EntityPrimaryKeyAwareComparator` and records every
	 * `setEntityPrimaryKey(int)` invocation so the test can assert whether the caller honored its
	 * EPK-aware contract.
	 */
	private static final class RecordingEpkAwareComparator
		implements ReferenceComparator, EntityPrimaryKeyAwareComparator, Serializable {

		@Serial private static final long serialVersionUID = 5914405464059833314L;

		final List<Integer> setEntityPrimaryKeyCalls = new ArrayList<>();

		@Override
		public void setEntityPrimaryKey(int entityPrimaryKey) {
			this.setEntityPrimaryKeyCalls.add(entityPrimaryKey);
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}
	}

	@Nested
	@DisplayName("sortAndFilterSubList")
	class SortAndFilterSubListTest {

		/**
		 * Pins the contract that `sortAndFilterSubList` must invoke `setEntityPrimaryKey` on
		 * EVERY `EntityPrimaryKeyAwareComparator` it visits while walking the comparator chain
		 * via `getNextComparator()` — not only the chain head.
		 *
		 * A query like
		 * `orderBy(attributeNatural("plain", ASC), attributeNatural("predecessor_attr", ASC))`
		 * produces exactly the chain shape under test: the plain-attribute comparator lands at
		 * the head and the EPK-aware `ReferencePredecessorComparator` becomes link #1.
		 *
		 * Without this guarantee, a non-head EPK-aware link is left without an entity scope and
		 * either produces an NPE (auto-unboxed `null` primary key) or a silently wrong sort.
		 */
		@Test
		@DisplayName("Should propagate entity primary key to EPK-aware comparator located deeper in the chain")
		void shouldSetEntityPrimaryKeyOnEpkAwareLinkWhenItIsNotHeadOfChain() {
			final RecordingEpkAwareComparator epkAwareLink = new RecordingEpkAwareComparator();
			// head reports two unsorted references so the chain-advance loop hands off to the next link
			final HeadFakeComparator nonEpkAwareHead = new HeadFakeComparator(epkAwareLink, 2);

			final ReferenceDecorator[] references = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			final int entityPrimaryKey = 42;

			EntityDecorator.sortAndFilterSubList(
				entityPrimaryKey,
				references,
				new ReferenceContractSerializablePredicate(),
				null,
				nonEpkAwareHead,
				0,
				references.length
			);

			assertEquals(
				List.of(entityPrimaryKey),
				epkAwareLink.setEntityPrimaryKeyCalls,
				"EPK-aware comparator located as a non-head link in the chain must still receive " +
					"setEntityPrimaryKey(entityPrimaryKey)."
			);
		}

		/**
		 * Companion control: when the EPK-aware comparator IS at the head, `setEntityPrimaryKey`
		 * does get called — confirming the test fixture is wired correctly and isolating the bug to
		 * the non-head case.
		 */
		@Test
		@DisplayName("Should propagate entity primary key to EPK-aware comparator at the chain head")
		void shouldSetEntityPrimaryKeyOnEpkAwareHeadOfChain() {
			final RecordingEpkAwareComparator epkAwareHead = new RecordingEpkAwareComparator();

			final ReferenceDecorator[] references = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any")
			};

			final int entityPrimaryKey = 7;

			EntityDecorator.sortAndFilterSubList(
				entityPrimaryKey,
				references,
				new ReferenceContractSerializablePredicate(),
				null,
				epkAwareHead,
				0,
				references.length
			);

			assertEquals(
				List.of(entityPrimaryKey),
				epkAwareHead.setEntityPrimaryKeyCalls,
				"setEntityPrimaryKey must be invoked exactly once for the source entity when the " +
					"EPK-aware comparator is the chain head."
			);
		}

		/**
		 * `sortAndFilterSubList` reads `referenceComparator.getNonSortedReferenceCount()` against
		 * a per-pass snapshot taken before `Arrays.sort`. Concrete comparators (e.g.
		 * `EntityNestedQueryComparator`) maintain a lazy-init `nonSortedReferences` set that ONLY
		 * grows — it is never reset between sort passes. The chain-advance arithmetic must use
		 * the per-pass delta (and clamp it to the current window) so that reusing the same
		 * comparator instance across multiple `sortAndFilterSubList` invocations — the normal
		 * case when an earlier pre-sort step has already mutated the comparator — cannot drive
		 * `start` out of the valid `[0, sortEnd]` range and trip the next `Arrays.sort` with a
		 * backwards range.
		 */
		@Test
		@DisplayName("Should keep sort window within bounds when comparator non-sorted count accumulates across invocations")
		void shouldKeepSortWindowWithinBoundsWhenComparatorNonSortedCountAccumulatesAcrossInvocations() {
			// growing counts simulate a comparator whose getNonSortedReferenceCount() value keeps
			// climbing across sort passes; the second `sortAndFilterSubList` call sees a snapshot
			// (200) that is far larger than the sort window (3), so the implementation must clamp
			// the per-pass delta to keep `start` inside [0, sortEnd]
			final GrowingNonSortedCountComparator growingHead = new GrowingNonSortedCountComparator(
				new RecordingEpkAwareComparator(),
				new int[]{1, 200}
			);

			final ReferenceDecorator[] firstWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};
			final ReferenceDecorator[] secondWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			// First invocation establishes the comparator's accumulated counter; this call itself
			// must not throw — the per-pass delta is still small (1) so the window stays in range.
			EntityDecorator.sortAndFilterSubList(
				1,
				firstWindow,
				new ReferenceContractSerializablePredicate(),
				null,
				growingHead,
				0,
				firstWindow.length
			);

			// Second invocation reuses the same comparator instance — the absolute counter (200)
			// is now far larger than the sort window (3). The delta-snapshot pattern must clamp
			// the per-pass delta to `sortEnd - start` so the subsequent chain-link sort does not
			// receive a backwards range.
			assertDoesNotThrow(
				() -> EntityDecorator.sortAndFilterSubList(
					1,
					secondWindow,
					new ReferenceContractSerializablePredicate(),
					null,
					growingHead,
					0,
					secondWindow.length
				),
				"Per-pass delta of getNonSortedReferenceCount() must be clamped to the current " +
					"sort window so cumulative comparator counters cannot drive `start` out of " +
					"the [0, sortEnd] range."
			);
		}

		/**
		 * Control: a comparator whose `getNonSortedReferenceCount()` returns the same value on
		 * every call (i.e., it does NOT accumulate across sort passes) must let
		 * `sortAndFilterSubList` complete successfully across multiple invocations. Pinned so a
		 * future tightening of the delta-snapshot math cannot regress the common, well-behaved case.
		 */
		@Test
		@DisplayName("Should complete successfully when comparator non-sorted count is stable across invocations")
		void shouldCompleteSuccessfullyWhenComparatorNonSortedCountIsStableAcrossInvocations() {
			// stable counter (1) is well below the sort window (3) on every call; the math always
			// produces a valid `start` in [0, sortEnd] regardless of how many times the comparator
			// is reused.
			final StableNonSortedCountComparator stableHead = new StableNonSortedCountComparator(
				new RecordingEpkAwareComparator(),
				1
			);

			final ReferenceDecorator[] firstWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};
			final ReferenceDecorator[] secondWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			assertDoesNotThrow(
				() -> {
					EntityDecorator.sortAndFilterSubList(
						1,
						firstWindow,
						new ReferenceContractSerializablePredicate(),
						null,
						stableHead,
						0,
						firstWindow.length
					);
					EntityDecorator.sortAndFilterSubList(
						1,
						secondWindow,
						new ReferenceContractSerializablePredicate(),
						null,
						stableHead,
						0,
						secondWindow.length
					);
				},
				"A comparator that does not accumulate non-sorted count across calls must let " +
					"sortAndFilterSubList complete in the same way on every invocation."
			);
		}
	}
}
