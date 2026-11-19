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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.Fixture;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic witnesses for boundary selection, provenance, scope, lookup and fallback normalization. */
@DisplayName("Reference attribute boundary pick without a sorter")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundaryPickTest {

	/** Which index produced a candidate, and in which index its owner witness counts. */
	@Nested
	@DisplayName("Candidate provenance and owner witnesses")
	class ProvenanceAndWitnesses {

		/** An index-local miss must never compete with an eligible candidate from another index. */
		@Test
		@DisplayName("should fall back only after the whole context misses, in either index order")
		void shouldFallbackOnlyAfterTheWholeContextMissesInEitherIndexOrder() {
			assertPick(fixture(new int[][]{{1}, {9}}, new int[][]{{}, {9}}, 1, 9), 9, 1);
			assertPick(fixture(new int[][]{{9}, {1}}, new int[][]{{9}, {}}, 1, 9), 9, 1);
		}

		/** A failure in the first producing index must permit a successful later witness for the same PK. */
		@Test
		@DisplayName("should try a later producing index after an earlier one fails the owner check")
		void shouldTryLaterProducingIndexAfterAnEarlierFailure() {
			assertPick(fixture(new int[][]{{1, 2}, {2}}, new int[][]{{}, {2}}, 1, 2), 2, 1);
			assertPick(fixture(new int[][]{{2}, {1, 2}}, new int[][]{{2}, {}}, 1, 2), 2, 1);
		}

		/** Membership in an index's complete reference universe does not establish bound provenance. */
		@Test
		@DisplayName("should count an owner only in an index whose filter index produced the candidate")
		void shouldRequireBoundCandidateMembershipInTheWitnessIndex() {
			assertPick(fixture(new int[][]{{1, 9}, {2}}, new int[][]{{9}, {1}}, 1, 2, 9), 9, 1);
		}

		/** Unequal overlapping candidate sets preserve global PK order and independent owner membership. */
		@Test
		@DisplayName("should merge unequal overlapping candidate sets in one primary key order")
		void shouldMergeUnequalOverlappingCandidates() {
			assertPick(fixture(new int[][]{{1, 4, 9}, {2, 4}, {4, 7}}, new int[][]{{9}, {}, {4}},
				1, 2, 4, 7, 9), 4, 1);
		}

		/**
		 * Many producing heads keep global PK order across pops: the heads arrive in descending index order, so
		 * building the heap reorders them, and the whole first round lies outside the scope, so the alternative and
		 * the owned pick are both decided after many pops, the pick among several owned candidates of different
		 * indexes.
		 */
		@Test
		@DisplayName("should pick the lowest owned candidate after many pops across interleaved indexes")
		void shouldPickTheLowestOwnedCandidateAfterManyPopsAcrossInterleavedIndexes() {
			// index i holds 9 - i, 18 - i and 27 - i, so each round of the merge takes one candidate of every index
			final int[][] candidates = new int[9][];
			for (int i = 0; i < candidates.length; i++) {
				candidates[i] = new int[]{9 - i, 18 - i, 27 - i};
			}
			final int[] scope = new int[18];
			for (int i = 0; i < scope.length; i++) {
				scope[i] = 10 + i;
			}
			// owned: 3 out of scope in index 6, then 15 in index 3, 17 and 26 in index 1 and 22 in index 5
			final int[][] passing = new int[][]{{}, {17, 26}, {}, {15}, {}, {22}, {3}, {}, {}};

			assertPick(fixture(candidates, passing, scope), 15, 10);
		}

		/** All failures retain the first unrestricted PK and normalize its redundant alternative away. */
		@Test
		@DisplayName("should keep the lowest candidate without an alternative when every owner check misses")
		void shouldKeepUnrestrictedPickWhenEveryWitnessMisses() {
			assertPick(fixture(new int[][]{{1, 2}, {2, 9}}, new int[][]{{}, {}}, 1, 2, 9), 1, null);
		}

		/** Null filter slots preserve positional alignment of the later producing indexes. */
		@Test
		@DisplayName("should keep candidates aligned with their indexes across missing filter indexes")
		void shouldKeepCandidateAndIndexAlignmentAcrossNullFilters() {
			final Fixture fixture = fixture(new int[][]{{1}, {2}, {9}}, new int[][]{{1}, {}, {9}}, 1, 2, 9);
			fixture.filters()[0] = null;
			assertPick(fixture, 9, 2);
		}

		/** Approximate admission uses another-valued reference of the same index, without an exact value/owner join. */
		@Test
		@DisplayName("should admit a candidate a counted owner references with another value in the producing index")
		void shouldAdmitAnotherValuedReferenceOfACountedOwnerInTheProducingIndex() {
			final Fixture fixture = fixture(new int[][]{{1, 9}}, new int[][]{{1, 9}}, 1, 9);
			fixture.filters()[0].addRecord(1, 20);
			assertPick(fixture, 1, null);
			fixture.compare(20, null, null);
		}
	}

	/** Which candidates the scope leaves, which alternative is carried and when the bound falls back. */
	@Nested
	@DisplayName("Scope, alternative and fallback")
	class ScopeAlternativeAndFallback {

		/** The alternative remains available before fetching even if the selected owned entity cannot load. */
		@Test
		@DisplayName("should carry the lowest candidate in scope as the alternative of the owned pick")
		void shouldCarryTheUnrestrictedAlternative() {
			assertPick(fixture(new int[][]{{1, 9, 10}}, new int[][]{{9, 10}}, 1, 9, 10), 9, 1);
		}

		/** The alternative stands in only for a pick that did not load, and no other owned candidate ever does. */
		@Test
		@DisplayName("should resolve to the alternative when the pick did not load, never to a third candidate")
		void shouldResolveToTheAlternativeButNeverToAThirdCandidate() {
			final Fixture fixture = fixture(new int[][]{{1, 9, 10}}, new int[][]{{9, 10}}, 1, 9, 10);
			final SealedEntity alternative = new InitialEntityBuilder("brand", 1).toInstance();
			final SealedEntity third = new InitialEntityBuilder("brand", 10).toInstance();

			final ReferenceHistogramAccumulator.BoundaryPick pick =
				ReferenceHistogramAccumulator.pickBoundaryPkFromReferenceAttribute(
					fixture.filters(), fixture.indexes(), 10, fixture.owners(), null, fixture.scope(), null
				);

			assertSame(alternative, pick.resolve(Map.of(1, alternative, 10, third)));
			assertNull(pick.resolve(Map.of(10, third)));
		}

		/** Owned candidates outside the requested scope must not suppress the in-scope fallback. */
		@Test
		@DisplayName("should decide the owner check only on candidates in scope")
		void shouldDecideSuccessAfterScopeIntersection() {
			assertPick(fixture(new int[][]{{1, 9}}, new int[][]{{9}}, 1), 1, null);
		}

		/**
		 * An empty scope yields no pick, a single candidate in scope is picked without an alternative, and a bound that
		 * no record holds never reads the owner index.
		 */
		@Test
		@DisplayName("should handle an empty scope, a single candidate in scope and a bound no record holds")
		void shouldHandleEmptySingletonAndAbsentBounds() {
			final Fixture empty = fixture(new int[][]{{1, 9}}, new int[][]{{9}});
			assertPick(empty, null, null);
			final Fixture singleton = fixture(new int[][]{{1, 9}}, new int[][]{{9}}, 9);
			assertPick(singleton, 9, null);
			final OwnerFilterIndex owners = spy(filter(Integer.class));
			final Fixture absent = new Fixture(empty.filters(), empty.indexes(), owners, null, empty.scope());
			assertEquals(new ReferenceAttributeBoundaryOracle.Pair(null, null), absent.actual(99, null));
			verifyNoInteractions(owners);
		}

		/** Null owner filters retain every candidate; empty owners and baselines fall back globally. */
		@Test
		@DisplayName("should keep every candidate without owners and confine owners to the baseline")
		void shouldPreserveNullOwnersAndBaselineIntersection() {
			final Fixture original = fixture(new int[][]{{1, 9}}, new int[][]{{9}}, 1, 9);
			assertPick(new Fixture(original.filters(), original.indexes(), null, null, original.scope()), 1, null);
			assertPick(new Fixture(original.filters(), original.indexes(), filter(Integer.class), null,
				original.scope()), 1, null);
			assertPick(new Fixture(original.filters(), original.indexes(), original.owners(),
				new ConstantFormula(new BaseBitmap(200)), original.scope()), 1, null);
			assertPick(new Fixture(original.filters(), original.indexes(), original.owners(),
				EmptyFormula.INSTANCE, original.scope()), 1, null);
			assertPick(new Fixture(original.filters(), original.indexes(), original.owners(),
				new ConstantFormula(new BaseBitmap(100)), original.scope()), 9, 1);
		}

		/** An absent explicit candidate is not invented from defaults or an owner's membership alone. */
		@Test
		@DisplayName("should return no pick when a counted owner references a PK no filter index holds")
		void shouldReturnNoPickWhenOwnersExistButNoCandidatesDo() {
			assertPick(fixture(new int[][]{{}}, new int[][]{{9}}, 9), null, null);
		}
	}

	/** How the bound is looked up in the filter index and mapped to referenced entities. */
	@Nested
	@DisplayName("Filter keys and index kinds")
	class FilterKeysAndIndexKinds {

		/** Roaring order crosses container boundaries and retains unsigned ordering of high keys. */
		@Test
		@DisplayName("should merge producing candidates in unsigned order across bitmap container boundaries")
		void shouldPickAcrossRoaringHighKeys() {
			assertPick(fixture(new int[][]{{65535, 65536, 65537, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}},
				new int[][]{{65536, Integer.MIN_VALUE}}, 65535, 65536, 65537, Integer.MAX_VALUE,
				Integer.MIN_VALUE, -1), 65536, 65535);
			assertPick(fixture(new int[][]{{Integer.MIN_VALUE, -1, 1}}, new int[][]{{Integer.MIN_VALUE, 1}},
				Integer.MIN_VALUE, -1, 1), 1, null);
			assertPick(fixture(new int[][]{{Integer.MIN_VALUE, -1}, {1}},
				new int[][]{{Integer.MIN_VALUE}, {1}}, Integer.MIN_VALUE, -1, 1), 1, null);
		}

		/** Scalar decimal keys are normalized once; raw bucket keys remain valid inputs to the picker. */
		@Test
		@DisplayName("should find decimal values by their normalized key, given raw or as a decimal")
		void shouldUseNormalizedDecimalKeys() {
			final ReducedGroupEntityIndex index = index(0);
			index.insertPrimaryKeyIfMissing(200, 1);
			index.insertPrimaryKeyIfMissing(100, 9);
			final OwnerFilterIndex candidates = filter(BigDecimal.class);
			candidates.addRecord(1, new BigDecimal("1.00"));
			candidates.addRecord(9, new BigDecimal("1.0"));
			final OwnerFilterIndex owners = filter(BigDecimal.class);
			owners.addRecord(100, new BigDecimal("1.000"));
			final Fixture fixture = new Fixture(new FilterIndex[]{candidates}, new ReducedGroupEntityIndex[]{index},
				owners, null, new BaseBitmap(1, 9));
			fixture.compare(100, null, null);
			assertEquals(new ReferenceAttributeBoundaryOracle.Pair(9, 1), fixture.actual(100, null));
			fixture.compare(new BigDecimal("1.00"), null, null);
		}

		/** Closed range lookup includes endpoints and never substitutes scalar equality on the range objects. */
		@Test
		@DisplayName("should look up ranges as closed intervals at every threshold")
		void shouldResolveEveryRangeThreshold() {
			final ReducedGroupEntityIndex index = index(0);
			index.insertPrimaryKeyIfMissing(200, 1);
			index.insertPrimaryKeyIfMissing(100, 9);
			final OwnerFilterIndex candidates = filter(IntegerNumberRange.class);
			candidates.addRecord(1, IntegerNumberRange.between(2, 8));
			candidates.addRecord(9, IntegerNumberRange.between(4, 6));
			final OwnerFilterIndex owners = filter(IntegerNumberRange.class);
			owners.addRecord(100, IntegerNumberRange.between(4, 6));
			final Fixture fixture = new Fixture(new FilterIndex[]{candidates}, new ReducedGroupEntityIndex[]{index},
				owners, null, new BaseBitmap(1, 9));
			for (int value = 1; value <= 9; value++) {
				fixture.compare(value, null, null);
			}
			assertEquals(new ReferenceAttributeBoundaryOracle.Pair(9, 1), fixture.actual(4, null));
			assertEquals(new ReferenceAttributeBoundaryOracle.Pair(9, 1), fixture.actual(6, null));
		}

		/** A type index maps its internal record IDs to different referenced PKs without owner confinement. */
		@Test
		@DisplayName("should map the records of a type index to referenced PKs without owner confinement")
		void shouldRetainTypeIndexMappingWithoutOwnerFilter() {
			final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
				1, "owner", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand")
			);
			index.insertPrimaryKeyIfMissing(101, 9);
			index.insertPrimaryKeyIfMissing(102, 1);
			final OwnerFilterIndex candidates = filter(Integer.class);
			candidates.addRecord(101, 10);
			candidates.addRecord(102, 10);
			assertPick(new Fixture(new FilterIndex[]{candidates}, new EntityIndex[]{index},
				null, null, new BaseBitmap(1, 9)), 1, null);
		}

		/**
		 * A type index cannot tell the owners of a referenced entity, so a context confined to counted owners refuses
		 * it once its candidate reaches the owner check, rather than admitting or dropping the candidate.
		 */
		@Test
		@DisplayName("should refuse a type index in a context confined to counted owners")
		void shouldRefuseTypeIndexInOwnerConfinedContext() {
			final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
				1, "owner", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand")
			);
			index.insertPrimaryKeyIfMissing(101, 9);
			final OwnerFilterIndex candidates = filter(Integer.class);
			candidates.addRecord(101, 10);
			final OwnerFilterIndex owners = filter(Integer.class);
			owners.addRecord(100, 10);
			final Fixture fixture = new Fixture(new FilterIndex[]{candidates}, new EntityIndex[]{index},
				owners, null, new BaseBitmap(9));

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> fixture.actual(10, null));

			assertTrue(error.getMessage().contains(ReferencedTypeEntityIndex.class.getName()), error.getMessage());
		}
	}
}
