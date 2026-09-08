/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Test verifies {@link Query} object creation, normalization and java equals and hash code contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
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
	void shouldReturnHeadConstraint() {
		final Query q = query(
			collection("product"),
			filterBy(attributeEquals("code", "abc"))
		);
		assertNotNull(q.getHead());
	}

	@Test
	void shouldReturnNullHeadWhenNotProvided() {
		final Query q = query(filterBy(attributeEquals("code", "abc")));
		assertNull(q.getHead());
	}

	@Test
	void shouldReturnCollectionFromHead() {
		final Query q = query(
			collection("product"),
			filterBy(attributeEquals("code", "abc"))
		);
		final Collection c = q.getCollection();
		assertNotNull(c);
		assertEquals("product", c.getEntityType());
	}

	@Test
	void shouldReturnNullCollectionWhenNoHead() {
		final Query q = query(filterBy(attributeEquals("code", "abc")));
		assertNull(q.getCollection());
	}

	@Test
	void shouldExtractParametersFromQuery() {
		final Query q = query(
			collection("product"),
			filterBy(attributeEquals("code", "samsung"))
		);
		final StringWithParameters result = q.toStringWithParameterExtraction();
		assertNotNull(result);
		assertNotNull(result.query());
		assertNotNull(result.parameters());
		assertFalse(result.parameters().isEmpty());
	}

	@Test
	void shouldNormalizeQueryIdempotently() {
		final Query q = query(
			collection("product"),
			filterBy(
				and(
					attributeEquals("code", "abc")
				)
			)
		);
		final Query first = q.normalizeQuery();
		final Query second = first.normalizeQuery();
		// second normalization should return the same instance (already normalized)
		assertSame(second, first);
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

	/**
	 * The EvitaQL string is the only channel the remote driver has — {@link io.evitadb.api.query.head.Label labels}
	 * that survive in the object model but not in the printed string never reach the server. A head therefore has to
	 * come back from the parser exactly as it went in.
	 *
	 * The emitted shape is additionally constrained by the grammar: `EvitaQLQueryVisitor#findHeadConstraint` accepts
	 * exactly one top-level head constraint, so several head constraints must be printed inside a `head(...)`
	 * container rather than as siblings of `filterBy`. These round-trips fail the moment that rule is broken.
	 *
	 * See issue #1507.
	 */
	@Test
	void shouldRoundTripHeadWithCollectionAndLabelThroughEvitaQlString() {
		final Query original = query(
			head(
				collection("product"),
				label("rest_method", "CartController.updateCartByOperation")
			),
			filterBy(attributeEquals("code", "samsung"))
		);

		assertEquals(
			original,
			DefaultQueryParser.getInstance().parseQueryUnsafe(original.toString())
		);
	}

	@Test
	void shouldRoundTripLabelOnlyHeadThroughEvitaQlString() {
		final Query original = query(
			label("rest_method", "CartController.updateCartByOperation"),
			filterBy(attributeEquals("code", "samsung"))
		);

		assertEquals(
			original,
			DefaultQueryParser.getInstance().parseQueryUnsafe(original.toString())
		);
	}

	/**
	 * Mirrors what the gRPC driver actually does: print the query with its scalar arguments extracted into
	 * positional parameters, then let the server parse the pair back.
	 */
	@Test
	void shouldRoundTripHeadLabelsThroughParameterExtraction() {
		final Query original = query(
			head(
				collection("product"),
				label("rest_method", "CartController.updateCartByOperation")
			),
			filterBy(attributeEquals("code", "samsung"))
		);

		final StringWithParameters printed = original.toStringWithParameterExtraction();

		assertEquals(
			original,
			DefaultQueryParser.getInstance().parseQuery(
				printed.query(),
				new ArrayList<Object>(printed.parameters())
			)
		);
	}

	/**
	 * Documents why the printed form must use the `head(...)` container: the grammar allows exactly one top-level
	 * head constraint, so printing the collection and the label as siblings would produce a string the server
	 * refuses to parse.
	 */
	@Test
	void shouldRejectSeveralTopLevelHeadConstraints() {
		assertThrows(
			EvitaSyntaxException.class,
			() -> DefaultQueryParser.getInstance().parseQueryUnsafe(
				"query(collection('product'),label('rest_method', 'CartController.updateCartByOperation')," +
					"filterBy(attributeEquals('code', 'samsung')))"
			)
		);
	}

}
