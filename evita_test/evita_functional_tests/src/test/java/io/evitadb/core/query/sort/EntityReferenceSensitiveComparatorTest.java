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

package io.evitadb.core.query.sort;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Predicate;

import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_ORDER;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_SCALE;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_VARIANT;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.DECIMAL_VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.OWNERS;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link EntityReferenceSensitiveComparator#getFirstValuedReference}, which picks the row an owner is
 * represented by in the traversal block of one referenced entity: its first row, in the order of the representative
 * attribute values, that carries the sorted value. The assertions name the picked row, not the order of the owners it
 * produces, so a pick that happens to sort the same is still told apart.
 *
 * The owners and their rows are those of {@link PrefetchedOwnersFixture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("First valued row of an owner to a referenced entity")
@Tag(ENGINE)
@Tag(ORDER)
class EntityReferenceSensitiveComparatorTest {
	/**
	 * Accepts a row carrying the `order` attribute.
	 */
	private static final Predicate<ReferenceContract> ORDER_PRESENT = row -> row.getAttribute(ATTRIBUTE_ORDER) != null;
	/**
	 * Accepts every row.
	 */
	private static final Predicate<ReferenceContract> ANY = row -> true;
	/**
	 * Accepts no row.
	 */
	private static final Predicate<ReferenceContract> NONE = row -> false;

	/**
	 * Picks the row of the owner to the referenced entity with the predicate.
	 *
	 * @param owner            the primary key of the owner
	 * @param referenceSchema  the schema of the ordered reference
	 * @param referencedEntity the primary key of the referenced entity
	 * @param carriesValue     tells whether a row carries the sorted value
	 * @return the picked row
	 */
	@Nonnull
	private static Optional<ReferenceContract> pick(
		int owner,
		@Nonnull ReferenceSchema referenceSchema,
		int referencedEntity,
		@Nonnull Predicate<ReferenceContract> carriesValue
	) {
		return EntityReferenceSensitiveComparator.getFirstValuedReference(
			OWNERS.get(owner), referenceSchema, new ReferenceKey(referenceSchema.getName(), referencedEntity),
			carriesValue
		);
	}

	/**
	 * Picks the row of the owner to the brand of the `variants` reference and returns its `variant`.
	 *
	 * @param owner        the primary key of the owner
	 * @param brand        the primary key of the brand
	 * @param carriesValue tells whether a row carries the sorted value
	 * @return the `variant` of the picked row, empty when no row was picked
	 */
	@Nonnull
	private static Optional<Serializable> pickVariant(
		int owner,
		int brand,
		@Nonnull Predicate<ReferenceContract> carriesValue
	) {
		return pick(owner, VARIANTS_REFERENCE_SCHEMA, brand, carriesValue)
			.map(row -> row.getAttribute(ATTRIBUTE_VARIANT));
	}

	/**
	 * Picks the row of the owner to brand 2 of the `decimalVariants` reference that carries `order` and returns its
	 * `scale`.
	 *
	 * @param owner the primary key of the owner
	 * @return the `scale` of the picked row, empty when no row was picked
	 */
	@Nonnull
	private static Optional<Serializable> pickScale(int owner) {
		return pick(owner, DECIMAL_VARIANTS_REFERENCE_SCHEMA, 2, ORDER_PRESENT)
			.map(row -> row.getAttribute(ATTRIBUTE_SCALE));
	}

	@Nested
	@DisplayName("Rows of a reference allowing duplicates")
	class DuplicateRows {

		@Test
		@DisplayName("should pick the first valued row by representative value, not by the order rows were added in")
		void shouldPickFirstValuedRowByRepresentativeOrderWhateverTheInsertionOrder() {
			// 73 added its rows b (60), c (5) and 76 the same rows the other way round - b precedes c either way
			assertAll(
				() -> assertEquals(Optional.of("b"), pickVariant(73, 2, ORDER_PRESENT), "rows added b, c"),
				() -> assertEquals(Optional.of("b"), pickVariant(76, 2, ORDER_PRESENT), "rows added c, b")
			);
		}

		@Test
		@DisplayName("should skip a row without the value to a later valued row")
		void shouldSkipValueLessRowToLaterValuedRow() {
			// the predicate, not the key, decides whether 72's `a` row is skipped
			assertAll(
				() -> assertEquals(Optional.of("b"), pickVariant(72, 2, ORDER_PRESENT), "the a row has no order"),
				() -> assertEquals(Optional.of("a"), pickVariant(72, 2, ANY), "every row carries the value")
			);
		}

		@Test
		@DisplayName("should pick the only row of an owner with one row")
		void shouldPickTheOnlyRowOfOwnerWithOneRow() {
			assertAll(
				() -> assertEquals(Optional.of("a"), pickVariant(71, 2, ORDER_PRESENT)),
				() -> assertEquals(Optional.of("z"), pickVariant(70, 2, ORDER_PRESENT))
			);
		}

		@Test
		@DisplayName("should pick no row when no row of the owner to the referenced entity carries the value")
		void shouldReturnEmptyWhenNoRowCarriesValue() {
			assertAll(
				() -> assertEquals(Optional.empty(), pickVariant(73, 2, NONE), "no row carries the value"),
				() -> assertEquals(Optional.empty(), pickVariant(74, 2, ORDER_PRESENT), "74 has rows to brand 1 only")
			);
		}

		@Test
		@DisplayName("should pick the same row among rows whose representative values compare equal but differ")
		void shouldPickRowOfEqualComparingRepresentativeValues() {
			// 1.0 and 1.00 compare equal, and the representative order tells them apart by scale, so 1.0 goes first -
			// 80 and 81 hold the same two rows added in mirrored orders and both are represented by the 1.0 row
			assertAll(
				() -> assertEquals(Optional.of(new BigDecimal("1.0")), pickScale(80), "rows added 1.00, 1.0"),
				() -> assertEquals(Optional.of(new BigDecimal("1.0")), pickScale(81), "rows added 1.0, 1.00")
			);
		}
	}

	@Nested
	@DisplayName("Rows of a reference without duplicates")
	class PlainRows {

		@Test
		@DisplayName("should pick the single row of the owner when it carries the value")
		void shouldPickSingleRowCarryingValue() {
			assertAll(
				() -> assertEquals(Optional.empty(), pick(12, REFERENCE_SCHEMA, 1, ORDER_PRESENT), "row without order"),
				() -> assertTrue(pick(12, REFERENCE_SCHEMA, 1, ANY).isPresent(), "the same row, accepted"),
				() -> assertEquals(
					Optional.of(5),
					pick(12, REFERENCE_SCHEMA, 2, ORDER_PRESENT).map(row -> row.getAttribute(ATTRIBUTE_ORDER)),
					"row carrying order 5"
				),
				() -> assertEquals(Optional.empty(), pick(17, REFERENCE_SCHEMA, 1, ANY), "17 has no row of category 1")
			);
		}
	}

}
