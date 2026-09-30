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
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.sort;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the prefetch-route classification of a traversal ordering by the referenced primary key: an owner belongs
 * to a block when it has the row of the block's key - whether that row carries attributes does not matter, the
 * referenced primary key is always there - and an owner without that row is passed on, even when it is alone.
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
	@DisplayName("should refuse a comparison of an owner without the row of the block")
	void shouldRefuseComparisonOfUnclassifiedOwner() {
		// owner 14 has no row of category 1, so it is never classified as sortable in its block
		final EntityPrimaryKeyNaturalTranslator.ReferencePrimaryKeyEntityComparator comparator = comparator();

		comparator.withReferencedEntityId(
			BLOCK_1,
			() -> assertThrows(EvitaInternalError.class, () -> comparator.compare(OWNERS.get(11), OWNERS.get(14)))
		);
	}

}
