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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering the static helpers on `EntityDecorator`. Lives in the same package so
 * package-protected members can be exercised directly without reflection.
 */
@DisplayName("EntityDecorator")
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
	 * - report a non-zero `getNonSortedReferenceCount()` so the chain-advance loop in
	 *   `sortAndFilterSubList` moves on to the next link.
	 */
	private static final class HeadFakeComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -1761005013461764433L;

		@Nullable private final ReferenceComparator next;
		private final int nonSortedCount;

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
			return this.nonSortedCount;
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
		 * Regression for a latent bug exposed by the issue #1177 follow-up analysis.
		 *
		 * `sortAndFilterSubList` walks the comparator chain via `getNextComparator()` after each sort
		 * pass, but the `instanceof EntityPrimaryKeyAwareComparator` check that wires the source
		 * entity's primary key into the comparator only fires ONCE — before the loop — and therefore
		 * only ever targets the chain HEAD. Any EPK-aware link further down the chain is left without
		 * a primary key, which (depending on the implementation) yields either an NPE when its lambdas
		 * auto-unbox a null `Integer` or a silently wrong sort scoped to entity `null`.
		 *
		 * A query like
		 * `orderBy(attributeNatural("plain", ASC), attributeNatural("predecessor_attr", ASC))`
		 * produces exactly this shape: the plain-attribute comparator lands at the head and the
		 * EPK-aware `ReferencePredecessorComparator` becomes link #1.
		 *
		 * This test fails on the current `sortAndFilterSubList` and would pass once the
		 * `instanceof` check is moved inside the chain-advance loop (mirroring the fix already
		 * applied in `BitmapSlicer.sortReferencesByComparatorChain`).
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
					"setEntityPrimaryKey(entityPrimaryKey). Currently sortAndFilterSubList only " +
					"applies the instanceof check to the chain head, so the link silently retains " +
					"a null entity scope — fix the loop to perform the check inside the do/while."
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
	}
}
