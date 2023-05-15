/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.visitor;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.filter.AttributeSpecialValue.NOT_NULL;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
import static io.evitadb.api.query.require.StatisticsBase.WITHOUT_USER_FILTER;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies expected behaviour of {@link PrettyPrintingVisitor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PrettyPrintingVisitorTest {

	@Test
	void shouldPrettyPrintSimpleConstraint() {
		assertEquals("entityFetch()", PrettyPrintingVisitor.toString(new EntityFetch(), ""));
	}

	@Test
	void shouldPrettyPrintContainerWithAdditionalChildren() {
		assertEquals(
			"""
				referenceContent(
				\t'stock',
				\tfilterBy(
				\t\tentityPrimaryKeyInSet(10)
				\t),
				\tentityFetch(
				\t\tattributeContent('code')
				\t)
				)""",
			PrettyPrintingVisitor.toString(
				referenceContent(
					"stock",
					filterBy(
						entityPrimaryKeyInSet(10)
					),
					entityFetch(
						attributeContent("code")
					)
				),
				"\t"
			)
		);
	}

	@Test
	void shouldPrettyPrintContainerWithAdditionalChildrenAndParameters() {
		final StringWithParameters result = PrettyPrintingVisitor.toStringWithParameterExtraction(
			"\t", referenceContent(
				"stock",
				filterBy(
					entityPrimaryKeyInSet(10)
				),
				entityFetch(
					attributeContent("code")
				)
			)
		);
		assertEquals(
			"""
				referenceContent(
				\t?,
				\tfilterBy(
				\t\tentityPrimaryKeyInSet(?)
				\t),
				\tentityFetch(
				\t\tattributeContent(?)
				\t)
				)""",
			result.query()
		);
		assertArrayEquals(
			new Serializable[]{"stock", 10, "code"},
			result.parameters().toArray(new Serializable[0])
		);
	}

	@Test
	void shouldPrettyPrintComplexConstraint() {
		assertEquals(
			"""
			and(
				attributeEquals('a', 'b'),
				or(
					attributeIs('def', NOT_NULL),
					attributeBetween('c', 1, 78),
					not(
						attributeEquals('utr', true)
					),
					hierarchyWithin(
						'd',
						entityPrimaryKeyInSet(1),
						directRelation()
					),
					hierarchyWithin(
						'e',
						entityPrimaryKeyInSet(1)
					)
				)
			)""",
			PrettyPrintingVisitor.toString(
				requireNonNull(
					and(
						attributeEquals("a", "b"),
						or(
							attributeIsNotNull("def"),
							attributeBetween("c", 1, 78),
							not(
								attributeEqualsTrue("utr")
							),
							hierarchyWithin("d", entityPrimaryKeyInSet(1), directRelation()),
							hierarchyWithin("e", entityPrimaryKeyInSet(1))
						)
					)
				),
				"\t"
			)
		);
	}

	@Test
	void shouldPrettyPrintComplexConstraintWithParameterExtraction() {
		final StringWithParameters result = PrettyPrintingVisitor.toStringWithParameterExtraction(
			"\t", requireNonNull(
				and(
					attributeEquals("a", "b"),
					or(
						attributeIsNotNull("def"),
						attributeBetween("c", 1, 78),
						not(
							attributeEqualsTrue("utr")
						),
						hierarchyWithin("d", entityPrimaryKeyInSet(1), directRelation()),
						hierarchyWithin("e", entityPrimaryKeyInSet(1))
					)
				)
			)
		);
		assertEquals(
			"""				
			and(
				attributeEquals(?, ?),
				or(
					attributeIs(?, ?),
					attributeBetween(?, ?, ?),
					not(
						attributeEquals(?, ?)
					),
					hierarchyWithin(
						?,
						entityPrimaryKeyInSet(?),
						directRelation()
					),
					hierarchyWithin(
						?,
						entityPrimaryKeyInSet(?)
					)
				)
			)""",
			result.query()
		);
		assertArrayEquals(
			new Serializable[]{"a", "b", "def", NOT_NULL, "c", 1, 78, "utr", true, "d", 1, "e", 1},
			result.parameters().toArray(new Serializable[0])
		);
	}

	@Test
	void shouldPrettyPrintEntireQuery() {
		final StringWithParameters result = PrettyPrintingVisitor.toStringWithParameterExtraction(
			Query.query(
				collection("PRODUCT"),
				filterBy(
					and(
						attributeEquals("a", "b"),
						or(
							attributeIsNotNull("def"),
							attributeBetween("c", 1, 78),
							not(
								attributeEqualsTrue("utr")
							),
							hierarchyWithin("d", entityPrimaryKeyInSet(1), directRelation()),
							hierarchyWithin("e", entityPrimaryKeyInSet(1))
						)
					)
				),
				orderBy(
					attributeNatural("x")
				),
				require(
					hierarchyOfReference(
						"CATEGORY",
						fromRoot(
							"megaMenu",
							entityFetch(attributeContent()),
							statistics()
						)
					),
					page(1, 100)
				)
			),
			"\t"
		);
		assertEquals(
			"""
				query(
					collection(?),
					filterBy(
						and(
							attributeEquals(?, ?),
							or(
								attributeIs(?, ?),
								attributeBetween(?, ?, ?),
								not(
									attributeEquals(?, ?)
								),
								hierarchyWithin(
									?,
									entityPrimaryKeyInSet(?),
									directRelation()
								),
								hierarchyWithin(
									?,
									entityPrimaryKeyInSet(?)
								)
							)
						)
					),
					orderBy(
						attributeNatural(?, ?)
					),
					require(
						hierarchyOfReference(
							?,
							?,
							fromRoot(
								?,
								entityFetch(
									attributeContent()
								),
								statistics(?)
							)
						),
						page(?, ?)
					)
				)""",
			result.query()
		);
		assertArrayEquals(
			new Serializable[]{
				"PRODUCT",
				"a", "b", "def", NOT_NULL, "c", 1, 78, "utr", true, "d", 1, "e", 1,
				"x", ASC,
				"CATEGORY", REMOVE_EMPTY, "megaMenu", WITHOUT_USER_FILTER, 1, 100
			},
			result.parameters().toArray(new Serializable[0])
		);
	}

	@Test
	void shouldPrettyPrintEntireQueryWithMissingParts() {
		final StringWithParameters result = PrettyPrintingVisitor.toStringWithParameterExtraction(
			Query.query(
				filterBy(
					and(
						attributeEquals("a", "b"),
						or(
							attributeIsNotNull("def"),
							attributeBetween("c", 1, 78),
							not(
								attributeEqualsTrue("utr")
							),
							hierarchyWithin("d", entityPrimaryKeyInSet(1), directRelation()),
							hierarchyWithin("e", entityPrimaryKeyInSet(1))
						)
					)
				)
			),
			"\t"
		);
		assertEquals(
			"""
			query(
				filterBy(
					and(
						attributeEquals(?, ?),
						or(
							attributeIs(?, ?),
							attributeBetween(?, ?, ?),
							not(
								attributeEquals(?, ?)
							),
							hierarchyWithin(
								?,
								entityPrimaryKeyInSet(?),
								directRelation()
							),
							hierarchyWithin(
								?,
								entityPrimaryKeyInSet(?)
							)
						)
					)
				)
			)""",
			result.query()
		);
		assertArrayEquals(
			new Serializable[]{
				"a", "b", "def", NOT_NULL, "c", 1, 78, "utr", true, "d", 1, "e", 1
			},
			result.parameters().toArray(new Serializable[0])
		);
	}

}