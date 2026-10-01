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

package io.evitadb.core.query.sort.reference.translator;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies how the planning of a reference ordering lines up the reduced indexes of one referenced entity
 * ({@link ReferencePropertyTranslator#appendGroupedByRepresentativeKey(List, boolean, ReducedEntityIndex[], int)}):
 * grouped by representative key, live before archived inside a key, the keys in the representative order for a
 * traversal and in the order they were met for the planning-time indexes of a `pickFirst` ordering. The indexes are
 * real {@link ReducedEntityIndex}es of one target, handed over in an order that is neither.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Planning-time grouping of the reduced indexes of one referenced entity")
@Tag(ENGINE)
@Tag(ORDER)
class ReferencePropertyTranslatorIndexGroupingTest {
	private static final String REFERENCE_NAME = "variants";
	private static final String OWNER_TYPE = "owner";

	@Test
	@DisplayName("should line up the keys of a traversal in representative order, live before archived in each")
	void shouldOrderTraversalKeysInRepresentativeOrder() {
		final ReducedEntityIndex liveZ = createIndex(1, Scope.LIVE, "z");
		final ReducedEntityIndex archivedA = createIndex(2, Scope.ARCHIVED, "a");
		final ReducedEntityIndex liveB = createIndex(3, Scope.LIVE, "b");
		final ReducedEntityIndex liveA = createIndex(4, Scope.LIVE, "a");
		final ReducedEntityIndex archivedZ = createIndex(5, Scope.ARCHIVED, "z");

		assertArrayEquals(
			new int[]{4, 2, 3, 1, 5},
			group(true, liveZ, archivedA, liveB, liveA, archivedZ)
		);
	}

	@Test
	@DisplayName("should keep the keys of the pick-first planning indexes in the order they were met")
	void shouldKeepPickFirstKeysInOrderMet() {
		final ReducedEntityIndex liveZ = createIndex(1, Scope.LIVE, "z");
		final ReducedEntityIndex archivedA = createIndex(2, Scope.ARCHIVED, "a");
		final ReducedEntityIndex liveB = createIndex(3, Scope.LIVE, "b");
		final ReducedEntityIndex liveA = createIndex(4, Scope.LIVE, "a");
		final ReducedEntityIndex archivedZ = createIndex(5, Scope.ARCHIVED, "z");

		// keys met z, a, b - each key's indexes together, live first
		assertArrayEquals(
			new int[]{1, 5, 4, 2, 3},
			group(false, liveZ, archivedA, liveB, liveA, archivedZ)
		);
	}

	@Test
	@DisplayName("should keep apart keys whose values compare equal but differ, each key contiguous")
	void shouldKeepApartKeysComparingEqual() {
		// 1.0 and 1.00 compare equal but are two keys with indexes of their own: neither may be taken for the other,
		// they keep the order they were met in, and the archived index of 1.0 stays next to its live one
		final ReducedEntityIndex liveScale2 = createIndex(1, Scope.LIVE, new BigDecimal("1.00"));
		final ReducedEntityIndex liveScale1 = createIndex(2, Scope.LIVE, new BigDecimal("1.0"));
		final ReducedEntityIndex archivedScale1 = createIndex(3, Scope.ARCHIVED, new BigDecimal("1.0"));
		final ReducedEntityIndex liveHalf = createIndex(4, Scope.LIVE, new BigDecimal("0.5"));

		assertAll(
			() -> assertArrayEquals(
				new int[]{4, 1, 2, 3}, group(true, liveScale2, liveScale1, archivedScale1, liveHalf), "traversal"
			),
			() -> assertArrayEquals(
				new int[]{1, 2, 3, 4}, group(false, liveScale2, liveScale1, archivedScale1, liveHalf), "pick-first"
			)
		);
	}

	@Test
	@DisplayName("should refuse two indexes of one key in one scope")
	void shouldRefuseTwoIndexesOfOneKeyInOneScope() {
		final ReducedEntityIndex first = createIndex(1, Scope.LIVE, "a");
		final ReducedEntityIndex second = createIndex(2, Scope.LIVE, "a");

		assertThrows(EvitaInternalError.class, () -> group(true, first, second));
	}

	@Test
	@DisplayName("should append after the indexes already in the result")
	void shouldAppendAfterPeak() {
		final ReducedEntityIndex liveB = createIndex(1, Scope.LIVE, "b");
		final ReducedEntityIndex liveA = createIndex(2, Scope.LIVE, "a");
		final ReducedEntityIndex earlier = createIndex(9, Scope.LIVE, "m");
		final ReducedEntityIndex[] result = new ReducedEntityIndex[3];
		result[0] = earlier;

		final int peak = ReferencePropertyTranslator.appendGroupedByRepresentativeKey(
			List.of(liveB, liveA), true, result, 1
		);

		assertAll(
			() -> assertEquals(3, peak),
			() -> assertArrayEquals(
				new int[]{9, 2, 1}, Arrays.stream(result).mapToInt(ReducedEntityIndex::getPrimaryKey).toArray()
			)
		);
	}

	/**
	 * Groups the indexes into a fresh array and returns their primary keys in the resulting order.
	 *
	 * @param inRepresentativeKeyOrder whether the keys follow the representative order
	 * @param indexes                  the indexes of one referenced entity in the order they were selected in
	 * @return the primary keys of the indexes in the order they were lined up
	 */
	@Nonnull
	private static int[] group(boolean inRepresentativeKeyOrder, @Nonnull ReducedEntityIndex... indexes) {
		final ReducedEntityIndex[] result = new ReducedEntityIndex[indexes.length];
		final int peak = ReferencePropertyTranslator.appendGroupedByRepresentativeKey(
			List.of(indexes), inRepresentativeKeyOrder, result, 0
		);
		return Arrays.stream(result, 0, peak).mapToInt(ReducedEntityIndex::getPrimaryKey).toArray();
	}

	/**
	 * Creates a real reduced index of referenced entity 2 with one representative attribute value.
	 *
	 * @param primaryKey          primary key of the index
	 * @param scope               the scope of the owners the index holds
	 * @param representativeValue the representative attribute value of the rows the index holds
	 * @return the index
	 */
	@Nonnull
	private static ReducedEntityIndex createIndex(
		int primaryKey,
		@Nonnull Scope scope,
		@Nonnull Serializable representativeValue
	) {
		return new ReducedEntityIndex(
			primaryKey, OWNER_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, scope,
				new RepresentativeReferenceKey(
					new ReferenceKey(REFERENCE_NAME, 2), new Serializable[]{representativeValue}
				)
			)
		);
	}

}
