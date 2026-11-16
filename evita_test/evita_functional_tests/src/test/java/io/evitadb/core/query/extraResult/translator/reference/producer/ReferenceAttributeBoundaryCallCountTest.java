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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.Fixture;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Counts real index owner lookups through package-local spies, without production counters or clock assertions.
 *
 * The owner spies observe owner-map lookups, not the total work of selection. The many-index fixture also observes
 * producing-set membership probes through real mapped-bitmap spies. No spy stubs index or bitmap behavior.
 */
@DisplayName("Owner lookups of the reference attribute boundary pick without a sorter")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundaryCallCountTest {
	/** The first successful witness stops both the producing-index checks and the global candidate merge. */
	@Test
	@DisplayName("should stop at the first candidate whose owner check passes")
	void shouldStopAfterTheFirstSuccessfulWitness() {
		final Fixture fixture = fixture(
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}},
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}}, 1, 2, 3
		);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), pick);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(1);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[1], never()).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[2], never()).getOwnerPKsForReferencedEntity(anyInt());
	}

	/** A first-index failure still reaches the later producing-index witness before stopping. */
	@Test
	@DisplayName("should check the later producing index after an earlier one fails, then stop")
	void shouldCheckTheLateWitnessAfterEarlierFailures() {
		final Fixture fixture = fixture(new int[][]{{1, 2}, {2}}, new int[][]{{}, {2}}, 1, 2);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(2, 1), pick);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(1);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(2);
		verify(indexes[0], times(2)).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[1], times(1)).getOwnerPKsForReferencedEntity(2);
		verify(indexes[1], times(1)).getOwnerPKsForReferencedEntity(anyInt());
		final InOrder order = inOrder((Object[]) indexes);
		order.verify(indexes[0]).getOwnerPKsForReferencedEntity(1);
		order.verify(indexes[0]).getOwnerPKsForReferencedEntity(2);
		order.verify(indexes[1]).getOwnerPKsForReferencedEntity(2);
	}

	/**
	 * Whole-context fallback must exhaust every producing-index witness when all candidates fail. Exhausting is the
	 * contract: with no passing owner the bound keeps every candidate, so each index is asked about each candidate it
	 * produced exactly once and about nothing else.
	 */
	@Test
	@DisplayName("should ask every producing index about each of its candidates once before falling back")
	void shouldExhaustEveryWitnessBeforeAllMissFallback() {
		final Fixture fixture = fixture(
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}}, new int[][]{{}, {}, {}}, 1, 2, 3
		);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), pick);
		for (final ReducedGroupEntityIndex index : indexes) {
			for (final int pk : new int[]{1, 2, 3}) {
				verify(index, times(1)).getOwnerPKsForReferencedEntity(pk);
			}
			verify(index, times(3)).getOwnerPKsForReferencedEntity(anyInt());
		}
	}

	/** Disjoint all-miss candidates must not cause a candidate-by-index membership cross product. */
	@Test
	@DisplayName("should merge many disjoint producing indexes without probing their candidate membership")
	void shouldMergeManyDisjointIndexesWithoutMembershipProbes() {
		final MappingObservedIndex[] indexes = new MappingObservedIndex[200];
		final FilterIndex[] filters = new FilterIndex[indexes.length];
		final int[] scope = new int[indexes.length * 5];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = spy(new MappingObservedIndex(i));
			filters[i] = filter(Integer.class);
			for (int j = 0; j < 5; j++) {
				final int pk = i * 5 + j + 1;
				scope[pk - 1] = pk;
				indexes[i].insertPrimaryKeyIfMissing(200, pk);
				filters[i].addRecord(pk, 10);
			}
		}
		final OwnerFilterIndex owners = filter(Integer.class);
		owners.addRecord(100, 10);
		final Fixture fixture = new Fixture(filters, indexes, owners, null, new BaseBitmap(scope));

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), fixture.actual(10, null));
		long membershipProbes = 0;
		for (final MappingObservedIndex index : indexes) {
			verify(index, times(5)).getOwnerPKsForReferencedEntity(anyInt());
			for (final Invocation invocation : mockingDetails(index.mappedCandidates).getInvocations()) {
				if (invocation.getMethod().getName().equals("contains")) {
					membershipProbes++;
				}
			}
		}
		assertEquals(0L, membershipProbes);
	}

	/** A real reduced index that exposes its real mapped bitmap through an unstubbed spy. */
	private static class MappingObservedIndex extends ReducedGroupEntityIndex {
		/** The bitmap returned by the latest real candidate mapping. */
		private Bitmap mappedCandidates;

		/** Creates a representative-attribute partition with no fabricated mapping behavior. */
		private MappingObservedIndex(int rank) {
			super(rank + 1, "owner", new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey("brand", 1), new Serializable[]{rank})
			));
		}

		/** Delegates all mapping to the real index and observes calls on its result without stubbing them. */
		@Nonnull
		@Override
		public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
			this.mappedCandidates = spy(super.getReferencedPrimaryKeysForIndexPks(indexPrimaryKeys));
			return this.mappedCandidates;
		}
	}

	/** Wraps the existing real indexes without sharing an oracle invocation with the measured picker call. */
	@Nonnull
	private static ReducedGroupEntityIndex[] spyIndexes(@Nonnull Fixture fixture) {
		final ReducedGroupEntityIndex[] indexes = new ReducedGroupEntityIndex[fixture.indexes().length];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = spy((ReducedGroupEntityIndex) fixture.indexes()[i]);
			fixture.indexes()[i] = indexes[i];
		}
		return indexes;
	}
}
