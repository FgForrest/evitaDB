/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test verifies {@link Query} object creation, normalization and java equals and hash code contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class QueryTest {

	@Test
	void shouldCreateQueryAndPrettyPrintIt() {
		final Query query = query(
			collection("brand"),
			filterBy(
				and(
					attributeEquals("code", "samsung"),
					attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
				)
			),
			orderBy(
				attributeNatural("name")
			),
			require(
				page(1, 5)
			)
		);
		assertEquals(
			"query(\n" +
				"\tcollection('brand'),\n" +
				"\tfilterBy(\n" +
				"\t\tand(\n" +
				"\t\t\tattributeEquals('code', 'samsung'),\n" +
				"\t\t\tattributeInRange('validity', 2020-01-01T00:00:00Z)\n" +
				"\t\t)\n" +
				"\t),\n" +
				"\torderBy(\n" +
				"\t\tattributeNatural('name', ASC)\n" +
				"\t),\n" +
				"\trequire(\n" +
				"\t\tpage(1, 5)\n" +
				"\t)\n" +
				")",
			query.prettyPrint()
		);
	}

	@Test
	void shouldCreateIncompleteQueryAndPrettyPrintIt() {
		final Query query = query(
			collection("brand"),
			require(
				page(1, 5)
			)
		);
		assertEquals(
			"query(\n" +
				"\tcollection('brand'),\n" +
				"\trequire(\n" +
				"\t\tpage(1, 5)\n" +
				"\t)\n" +
				")",
			query.prettyPrint()
		);
	}

	@Test
	void shouldCreateQueryAndPrintIt() {
		final Query query = query(
			collection("brand"),
			filterBy(
				and(
					attributeEquals("code", "samsung"),
					attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
				)
			),
			orderBy(
				attributeNatural("name")
			),
			require(
				page(1, 5)
			)
		);
		assertEquals(
			"query(collection('brand'),filterBy(and(attributeEquals('code', 'samsung'),attributeInRange('validity', 2020-01-01T00:00:00Z))),orderBy(attributeNatural('name', ASC)),require(page(1, 5)))",
			query.toString()
		);
	}

	@Test
	void shouldVerifyEquals() {
		assertEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			),
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			)
		);
	}

	@Test
	void shouldVerifyNotEqualsByValueChange() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			),
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "nokia"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			)
		);
	}

	@Test
	void shouldVerifyNotEqualsByEntityChange() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			),
			query(
				collection("product"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			)
		);
	}

	@Test
	void shouldVerifyNotEqualsByDifferentStructure() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			),
			query(
				collection("product"),
				filterBy(
					or(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name", DESC)
				),
				require(
					page(1, 5)
				)
			)
		);
	}

	@Test
	void shouldVerifyHashCodeEquals() {
		assertEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode(),
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode()
		);
	}

	@Test
	void shouldVerifyHashCodeNotEqualsByValueChange() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode(),
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "nokia"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode()
		);
	}

	@Test
	void shouldVerifyHashCodeNotEqualsByEntityChange() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode(),
			query(
				collection("product"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode()
		);
	}

	@Test
	void shouldVerifyHashCodeNotEqualsByDifferentStructure() {
		assertNotEquals(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			).hashCode(),
			query(
				collection("product"),
				filterBy(
					or(
						attributeEquals("code", "samsung"),
						attributeInRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
					)
				),
				orderBy(
					attributeNatural("name", DESC)
				),
				require(
					page(1, 5)
				)
			).hashCode()
		);
	}

	@Test
	void shouldNormalizeQueryByRemovingInvalidConstraints() {
		assertEquals(
			query(
				collection("product")
			),
			query(
				collection("product"),
				filterBy(
					attributeEquals("code", null)
				)
			).normalizeQuery()
		);
	}

	@Test
	void shouldNormalizeQueryByFlatteningUnnecessaryConstraintContainers() {
		assertEquals(
			query(
				collection("product"),
				filterBy(
					attributeEquals("code", "abc")
				),
				orderBy(
					attributeNatural("name")
				)
			),
			query(
				collection("product"),
				filterBy(
					and(
						attributeEquals("code", "abc")
					)
				),
				orderBy(
					attributeNatural("name")
				)
			).normalizeQuery()
		);
	}

	@Test
	void shouldNormalizeQueryByRemovingInvalidAndFlatteningUnnecessaryConstraintContainers() {
		assertEquals(
			query(
				collection("product"),
				orderBy(
					attributeNatural("name")
				)
			),
			query(
				collection("product"),
				filterBy(
					and(attributeEquals("code", null))
				),
				orderBy(
					attributeNatural("name")
				)
			).normalizeQuery()
		);
	}

	@Test
	void shouldNormalizeQueryByRemovingInvalidAndFlatteningUnnecessaryConstraintContainersInComplexScenario() {
		assertEquals(
			query(
				collection("product"),
				filterBy(
					attributeIsNotNull("valid")
				),
				orderBy(
					attributeNatural("name")
				),
				require(
					page(1, 5)
				)
			),
			query(
				collection("product"),
				filterBy(
					and(
						or(
							attributeEquals("code", null),
							attributeIsNull(null)
						),
						and(
							attributeIsNotNull("valid")
						)
					)
				),
				orderBy(
					attributeNatural("name"),
					attributeNatural(null, DESC)
				),
				require(
					page(1, 5)
				)
			).normalizeQuery()
		);
	}

	@Test
	void shouldNormalizeQueryByRemovingInvalidAndFlatteningUnnecessaryConstraintContainersInComplexDeepScenario() {
		assertEquals(
			query(
				collection("product"),
				filterBy(
					attributeIsNotNull("valid")
				)
			),
			query(
				collection("product"),
				filterBy(
					and(
						or(
							attributeEquals("code", null),
							attributeIsNull(null),
							null,
							or(
								attributeEqualsTrue(null),
								null
							)
						),
						and(
							attributeIsNotNull("valid")
						),
						or(
							and(
								not(null)
							)
						)
					)
				)
			).normalizeQuery()
		);
	}

}
