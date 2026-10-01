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

package io.evitadb.core.query.sort.primaryKey.translator;

import io.evitadb.comparator.IntComparator.IntAscendingComparator;
import io.evitadb.comparator.IntComparator.IntDescendingComparator;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.SortResult;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_1;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.BLOCK_2;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.OWNERS;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.sort;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.variantsBlockKey;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the prefetch-route classification of a traversal ordering by the referenced primary key: an owner belongs
 * to a block when it has a row to the block's referenced entity - whether that row carries attributes or which
 * representative values it has does not matter, the referenced primary key is always there - and an owner without
 * such a row is passed on, even when it is alone. The owners of one block share its value and follow their own
 * primary keys in the direction of the ordering.
 *
 * Runs {@link PrefetchedRecordsSorter} over the real owners of {@link PrefetchedOwnersFixture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Traversal comparator of the referenced primary key")
@Tag(ENGINE)
@Tag(ORDER)
class ReferencePrimaryKeyEntityComparatorTest {

	/**
	 * Creates the comparator in the descending direction.
	 */
	@Nonnull
	private static EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator descendingComparator() {
		return new EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator(
			REFERENCE_SCHEMA, IntDescendingComparator.INSTANCE
		);
	}

	/**
	 * Creates the comparator in the ascending direction.
	 */
	@Nonnull
	private static EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator comparator() {
		return new EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator(
			REFERENCE_SCHEMA, IntAscendingComparator.INSTANCE
		);
	}

	@Test
	@DisplayName("should claim a lone owner that has the row of the block")
	void shouldClaimLoneOwnerWithRowOfBlock() {
		// owner 12's row of category 1 carries no attribute - the referenced primary key is its value all the same
		final SortResult sorted = sort(comparator(), BLOCK_1, 0, 10, 12);

		assertAll(
			() -> assertArrayEquals(new int[]{12}, sorted.written()),
			() -> assertArrayEquals(new int[0], sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should pass on a lone owner without the row of the block")
	void shouldPassOnLoneOwnerWithoutRowOfBlock() {
		final SortResult sorted = sort(comparator(), BLOCK_1, 0, 10, 14);

		assertAll(
			() -> assertArrayEquals(new int[0], sorted.written()),
			() -> assertArrayEquals(new int[]{14}, sorted.passedOn())
		);
	}

	@Test
	@DisplayName("should claim the owners with the row of the block and pass on the rest, block by block")
	void shouldClassifyEachBlockByItsRow() {
		// one comparator instance sorts the block of category 1 and then the block of category 2
		final EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator comparator = comparator();
		final SortResult first = sort(comparator, BLOCK_1, 0, 10, 11, 12, 13, 14, 17);
		final SortResult second = sort(comparator, BLOCK_2, 0, 10, 14, 17);

		assertAll(
			() -> assertArrayEquals(new int[]{11, 12, 13}, first.written(), "owners with a row of category 1"),
			() -> assertArrayEquals(new int[]{14, 17}, first.passedOn(), "14 and 17 have no row of category 1"),
			() -> assertArrayEquals(new int[]{14}, second.written(), "the block of category 2"),
			() -> assertArrayEquals(new int[]{17}, second.passedOn(), "17 has a row of category 3 only")
		);
	}

	@Test
	@DisplayName("should refuse to classify an owner when no block is bound")
	void shouldRefuseClassificationWithoutBoundBlock() {
		assertThrows(EvitaInternalError.class, () -> comparator().canSort(OWNERS.get(11)));
	}

	@Test
	@DisplayName("should order the owners of a block by their primary key in the direction of the ordering")
	void shouldOrderOwnersOfBlockByPrimaryKeyInDirection() {
		// owners 11, 12, 13 all have a row of category 1, so they share the value 1 and the tie decides their order
		final SortResult ascending = sort(comparator(), BLOCK_1, 0, 10, 11, 12, 13);
		final SortResult descending = sort(descendingComparator(), BLOCK_1, 0, 10, 11, 12, 13);

		assertAll(
			() -> assertArrayEquals(new int[]{11, 12, 13}, ascending.written(), "ascending"),
			() -> assertArrayEquals(new int[]{13, 12, 11}, descending.written(), "descending")
		);
	}

	@Test
	@DisplayName("should claim every owner with any row to the brand of the block, whatever its representative value")
	void shouldClaimOwnerWithAnyRowOfDuplicateReference() {
		// the block of brand 2 is the whole brand: 70 (z), 71 (a), 72 (a, b) and 73 (b, c) all have a row to it, none
		// of them with the representative values of the block key (it has none), and all share the value 2
		final SortResult sorted = sort(
			new EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator(
				VARIANTS_REFERENCE_SCHEMA, IntDescendingComparator.INSTANCE
			),
			variantsBlockKey(2), 0, 10, 70, 71, 72, 73, 74
		);

		assertAll(
			() -> assertArrayEquals(new int[]{73, 72, 71, 70}, sorted.written(), "descending by owner"),
			() -> assertArrayEquals(new int[]{74}, sorted.passedOn(), "74 has a row of brand 1 only")
		);
	}

	@Test
	@DisplayName("should refuse a comparison when no block is bound")
	void shouldRefuseComparisonWithoutBoundBlock() {
		assertThrows(EvitaInternalError.class, () -> comparator().compare(OWNERS.get(11), OWNERS.get(13)));
	}

}
