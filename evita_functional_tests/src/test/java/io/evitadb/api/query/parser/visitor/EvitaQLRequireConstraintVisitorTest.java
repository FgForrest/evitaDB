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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetStatisticsDepth.COUNTS;
import static io.evitadb.api.query.require.FacetStatisticsDepth.IMPACT;
import static io.evitadb.api.query.require.QueryPriceMode.WITH_TAX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLRequireConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLRequireConstraintVisitorTest {

	@Test
	void shouldParseRequireContainerConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("require(entityFetch())");
		assertEquals(require(entityFetch()), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("require(entityFetch(attributeContentAll()))");
		assertEquals(require(entityFetch(attributeContent())), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("require (  entityFetch(  attributeContentAll() )  )");
		assertEquals(require(entityFetch(attributeContent())), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("require()");
		assertEquals(require(), constraint4);
	}

	@Test
	void shouldNotParseRequireContainerConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("require"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("require('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("require(attributeEquals('a',1))"));
	}

	@Test
	void shouldParsePageConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("page(10,20)");
		assertEquals(page(10, 20), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("page (  10 ,20 )");
		assertEquals(page(10, 20), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("page(?,?)", 10, 20);
		assertEquals(page(10, 20), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("page(@page,@size)", Map.of("page", 10, "size", 20));
		assertEquals(page(10, 20), constraint4);
	}

	@Test
	void shouldNotParsePageConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("page(10,20)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("page(?,?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("page(@page,@size)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("page"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("page()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("page(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("page('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("page(1,'a')"));
	}

	@Test
	void shouldParseStripConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("strip(10,20)");
		assertEquals(strip(10, 20), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("strip(   5  , 30  )");
		assertEquals(strip(5, 30), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("strip(?,?)", 10, 20);
		assertEquals(strip(10, 20), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("strip(@off,@size)", Map.of("off", 10, "size", 20));
		assertEquals(strip(10, 20), constraint4);
	}

	@Test
	void shouldNotParseStripConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("strip(10,20)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("strip(?,?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("strip(@off,@size)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("strip"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("strip()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("strip(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("strip('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("strip(1,'a')"));
	}

	@Test
	void shouldParseEntityFetchConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("entityFetch()");
		assertEquals(entityFetch(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("entityFetch (  )");
		assertEquals(entityFetch(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("entityFetch(attributeContentAll(),associatedDataContentAll())");
		assertEquals(entityFetch(attributeContent(), associatedDataContent()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityFetch(  attributeContentAll  (   ) ,   associatedDataContentAll   ( ) )");
		assertEquals(entityFetch(attributeContent(), associatedDataContent()), constraint4);
	}

	@Test
	void shouldNotParseEntityFetchConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityFetch"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityFetch('a')"));
	}

	@Test
	void shouldParseEntityGroupFetchConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("entityGroupFetch()");
		assertEquals(entityGroupFetch(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("entityGroupFetch (  )");
		assertEquals(entityGroupFetch(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("entityGroupFetch(attributeContentAll(),associatedDataContentAll())");
		assertEquals(entityGroupFetch(attributeContent(), associatedDataContent()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityGroupFetch(  attributeContentAll  (   ) ,   associatedDataContentAll   ( ) )");
		assertEquals(entityGroupFetch(attributeContent(), associatedDataContent()), constraint4);
	}

	@Test
	void shouldNotParseEntityGroupFetchConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityGroupFetch"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityGroupFetch('a')"));
	}

	@Test
	void shouldParseAttributeContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("attributeContentAll()");
		assertEquals(attributeContent(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("attributeContentAll (  )");
		assertEquals(attributeContent(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("attributeContent('a')");
		assertEquals(attributeContent("a"), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("attributeContent('a','b','c')");
		assertEquals(attributeContent("a", "b", "c"), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("attributeContent (  'a' ,'b'  ,  'c' )");
		assertEquals(attributeContent("a", "b", "c"), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("attributeContent(?)", "a");
		assertEquals(attributeContent("a"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("attributeContent(@attr)", Map.of("attr", "a"));
		assertEquals(attributeContent("a"), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("attributeContent(?)", List.of("a", "b"));
		assertEquals(attributeContent("a", "b"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("attributeContent(@attr)", Map.of("attr", List.of("a", "b")));
		assertEquals(attributeContent("a", "b"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("attributeContent(?,?)", "a", "b");
		assertEquals(attributeContent("a", "b"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint("attributeContent(@attr1,@attr2)", Map.of("attr1", "a", "attr2", "b"));
		assertEquals(attributeContent("a", "b"), constraint11);
	}

	@Test
	void shouldNotParseAttributeContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContentAll"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContentAll('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContent('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContent()"));
	}

	@Test
	void shouldParsePriceContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("priceContentAll()");
		assertEquals(priceContentAll(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("priceContent(ALL)");
		assertEquals(priceContentAll(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("priceContent (  ALL )");
		assertEquals(priceContentAll(), constraint3);

		final RequireConstraint constraint5 = parseRequireConstraint("priceContent(?)", PriceContentMode.ALL);
		assertEquals(priceContentAll(), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("priceContent(@mode)", Map.of("mode", PriceContentMode.ALL));
		assertEquals(priceContentAll(), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("priceContentRespectingFilter()");
		assertEquals(priceContentRespectingFilter(), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("priceContent(RESPECTING_FILTER)");
		assertEquals(priceContentRespectingFilter(), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("priceContent (  RESPECTING_FILTER )");
		assertEquals(priceContentRespectingFilter(), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("priceContent(?)", PriceContentMode.RESPECTING_FILTER);
		assertEquals(priceContentRespectingFilter(), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint("priceContent(@mode)", Map.of("mode", PriceContentMode.RESPECTING_FILTER));
		assertEquals(priceContentRespectingFilter(), constraint11);

		final RequireConstraint constraint12 = parseRequireConstraintUnsafe("priceContentRespectingFilter('vip')");
		assertEquals(priceContentRespectingFilter("vip"), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraintUnsafe("priceContent(RESPECTING_FILTER, 'vip')");
		assertEquals(priceContentRespectingFilter("vip"), constraint13);

		final RequireConstraint constraint14 = parseRequireConstraintUnsafe("priceContentRespectingFilter (  'vip' )");
		assertEquals(priceContentRespectingFilter("vip"), constraint14);

		final RequireConstraint constraint15 = parseRequireConstraint("priceContentRespectingFilter(?)", "vip");
		assertEquals(priceContentRespectingFilter("vip"), constraint15);

		final RequireConstraint constraint16 = parseRequireConstraint("priceContentRespectingFilter(@pl)", Map.of("pl", "vip"));
		assertEquals(priceContentRespectingFilter("vip"), constraint16);

		final RequireConstraint constraint17 = parseRequireConstraintUnsafe("priceContent(NONE, 'vip')");
		assertEquals(new PriceContent(PriceContentMode.NONE, "vip"), constraint17);

		final RequireConstraint constraint18 = parseRequireConstraint("priceContent(?, ?)", PriceContentMode.NONE, "vip");
		assertEquals(new PriceContent(PriceContentMode.NONE, "vip"), constraint18);
	}

	@Test
	void shouldNotParsePriceContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(ALL)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(ALL, 'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContent()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContent(AA)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContent(ALL,1)"));
	}

	@Test
	void shouldParsePriceContentAllConstraint() {
		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("priceContentAll()");
		assertEquals(priceContentAll(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("priceContentAll (   )");
		assertEquals(priceContentAll(), constraint3);
	}

	@Test
	void shouldNotParsePriceContentAllConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContentAll"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContentAll('a')"));
	}

	@Test
	void shouldParseassociatedDataContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("associatedDataContent('a')");
		assertEquals(associatedDataContent("a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("associatedDataContent('a','b','c')");
		assertEquals(associatedDataContent("a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("associatedDataContent (  'a' , 'b'  , 'c' )");
		assertEquals(associatedDataContent("a", "b", "c"), constraint3);

		final RequireConstraint constraint6 = parseRequireConstraint("associatedDataContent(?)", "a");
		assertEquals(associatedDataContent("a"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("associatedDataContent(@ad)", Map.of("ad", "a"));
		assertEquals(associatedDataContent("a"), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("associatedDataContent(?)", List.of("a", "b"));
		assertEquals(associatedDataContent("a", "b"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("associatedDataContent(@ad)", Map.of("ad", List.of("a", "b")));
		assertEquals(associatedDataContent("a", "b"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("associatedDataContent(?,?)", "a", "b");
		assertEquals(associatedDataContent("a", "b"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint("associatedDataContent(@ad1,@ad2)", Map.of("ad1", "a", "ad2", "b"));
		assertEquals(associatedDataContent("a", "b"), constraint11);

		final RequireConstraint constraint12 = parseRequireConstraint("associatedDataContentAll()");
		assertEquals(associatedDataContent(), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("associatedDataContentAll (  )");
		assertEquals(associatedDataContent(), constraint13);
	}

	@Test
	void shouldNotParseAssociatedDataContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent()"));
	}

	@Test
	void shouldParseReferenceContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("referenceContent('a')");
		assertEquals(referenceContent("a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("referenceContent('a','b','c')");
		assertEquals(referenceContent("a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("referenceContent (  'a' , 'b'  , 'c' )");
		assertEquals(referenceContent("a", "b", "c"), constraint3);

		final RequireConstraint constraint6 = parseRequireConstraint("referenceContent('BRAND', 'CATEGORY','stock')");
		assertEquals(referenceContent("BRAND", "CATEGORY", "stock"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContent())
			),
			constraint7
		);

		final RequireConstraint constraint8 = parseRequireConstraint("referenceContent(?)", "a");
		assertEquals(referenceContent("a"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("referenceContent(@et)", Map.of("et", "a"));
		assertEquals(referenceContent("a"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("referenceContent(?)", List.of("a", "b"));
		assertEquals(referenceContent("a", "b"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint("referenceContent(@et)", Map.of("et", List.of("a", "b")));
		assertEquals(referenceContent("a", "b"), constraint11);

		final RequireConstraint constraint12 = parseRequireConstraint("referenceContent(?,?)", "a", "b");
		assertEquals(referenceContent("a", "b"), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("referenceContent(@et1,@et2)", Map.of("et1", "a", "et2", "b"));
		assertEquals(referenceContent("a", "b"), constraint13);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"referenceContent(?,filterBy(attributeEquals('code',?)),entityFetch(attributeContent(?)))",
			"a",
			"some",
			"code"
		);
		assertEquals(
			referenceContent("a", filterBy(attributeEquals("code", "some")), entityFetch(attributeContent("code"))),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraint("referenceContentAll()");
		assertEquals(referenceContentAll(), constraint15);

		final RequireConstraint constraint16 = parseRequireConstraint("referenceContentAll (  )");
		assertEquals(referenceContentAll(), constraint16);

		final RequireConstraint constraint17 = parseRequireConstraint("referenceContent('a', entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				entityFetch(attributeContent())
			),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint("referenceContent('a', 'b', entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityFetch(attributeContent())
			),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraint("referenceContent('a', 'b', entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityFetch(attributeContent()),
				entityGroupFetch()
			),
			constraint19
		);

		final RequireConstraint constraint20 = parseRequireConstraint("referenceContent('a', 'b', entityGroupFetch())");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityGroupFetch()
			),
			constraint20
		);

		final RequireConstraint constraint21 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContent()),
				entityGroupFetch()
			),
			constraint21
		);

		final RequireConstraint constraint22 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContent())
			),
			constraint22
		);

		final RequireConstraint constraint23 = parseRequireConstraint("referenceContentAll(entityFetch(priceContentRespectingFilter()), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				entityFetch(priceContentRespectingFilter()),
				entityGroupFetch(attributeContent())
			),
			constraint23
		);

		final RequireConstraint constraint24 = parseRequireConstraint("referenceContentAll(entityFetch(priceContentRespectingFilter()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				entityFetch(priceContentRespectingFilter())
			),
			constraint24
		);

		final RequireConstraint constraint25 = parseRequireConstraint("referenceContentAll(entityGroupFetch(priceContentRespectingFilter()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				entityGroupFetch(priceContentRespectingFilter())
			),
			constraint25
		);

		final RequireConstraint constraint26 = parseRequireConstraint(
			"referenceContent(?,filterBy(attributeEquals('code',?)),entityFetch(attributeContent(?)),entityGroupFetch(attributeContent(?)))",
			"a",
			"some",
			"code",
			"name"
		);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", "some")),
				entityFetch(attributeContent("code")),
				entityGroupFetch(attributeContent("name"))
			),
			constraint26
		);

		final RequireConstraint constraint27 = parseRequireConstraint("referenceContent(?, entityFetch(attributeContent(?)))", "a", "b");
		assertEquals(
			referenceContent(
				"a",
				entityFetch(attributeContent("b"))
			),
			constraint27
		);

		final RequireConstraint constraint28 = parseRequireConstraint("referenceContent('a', orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContent())
			),
			constraint28
		);

		final RequireConstraint constraint29 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				entityFetch(attributeContent())
			),
			constraint29
		);

		final RequireConstraint constraint30 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code"))
			),
			constraint30
		);

		final RequireConstraint constraint31 = parseRequireConstraint("referenceContent('a', orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code"))
			),
			constraint31
		);
	}

	@Test
	void shouldNotParseReferenceContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeContentAll(),'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeContentAll(),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeNatural('a',ASC))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a', 'b', filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a', attributeContentAll())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(entityFetch(),entityFetch())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(entityGroupFetch(),entityFetch)"));
	}

	@Test
	void shouldParseHierarchyContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("hierarchyContent()");
		assertEquals(hierarchyContent(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("hierarchyContent(stopAt(distance(1)))");
		assertEquals(hierarchyContent(stopAt(distance(1))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("hierarchyContent(entityFetch(attributeContent('code')))");
		assertEquals(hierarchyContent(entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent('code')))");
		assertEquals(
			hierarchyContent(
				stopAt(distance(1)),
				entityFetch(attributeContent("code"))
			),
			constraint4
		);

		final RequireConstraint constraint5 = parseRequireConstraint("hierarchyContent(stopAt(distance(?)))", 1);
		assertEquals(hierarchyContent(stopAt(distance(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"hierarchyContent(stopAt(distance(@dist)))",
			Map.of("dist", 1)
		);
		assertEquals(hierarchyContent(stopAt(distance(1))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("hierarchyContent(entityFetch(attributeContent(?)))", "code");
		assertEquals(hierarchyContent(entityFetch(attributeContent("code"))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"hierarchyContent(entityFetch(attributeContent(@name)))",
			Map.of("name", "code")
		);
		assertEquals(hierarchyContent(entityFetch(attributeContent("code"))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"hierarchyContent(stopAt(distance(?)), entityFetch(attributeContent(?)))",
			1, "code"
		);
		assertEquals(
			hierarchyContent(
				stopAt(distance(1)),
				entityFetch(attributeContent("code"))
			),
			constraint9
		);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"hierarchyContent(stopAt(distance(@dist)), entityFetch(attributeContent(@name)))",
			Map.of("dist", 1, "name", "code")
		);
		assertEquals(
			hierarchyContent(
				stopAt(distance(1)),
				entityFetch(attributeContent("code"))
			),
			constraint10
		);
	}

	@Test
	void shouldNotParseHierarchyContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyContent(stopAt(distance(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyContent(attributeContent('code'))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyContent(entityFetch(attributeContent('code')), stopAt(distance(1)))"));
	}

	@Test
	void shouldParsePriceTypeConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("priceType(WITH_TAX)");
		assertEquals(priceType(WITH_TAX), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("priceType(   WITHOUT_TAX )");
		assertEquals(priceType(QueryPriceMode.WITHOUT_TAX), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("priceType(?)", WITH_TAX);
		assertEquals(priceType(WITH_TAX), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("priceType(@mode)", Map.of("mode", WITH_TAX));
		assertEquals(priceType(WITH_TAX), constraint4);
	}

	@Test
	void shouldNotParsePriceTypeConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceType(WITH_TAX)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceType(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceType(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceType"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceType()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceType('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceType(WITH_TAX,WITH_TAX)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceType(TAX)"));
	}

	@Test
	void shouldParseDataInLocalesConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("dataInLocales()");
		assertEquals(dataInLocales(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("dataInLocales('cs')");
		assertEquals(dataInLocales(new Locale("cs")), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("dataInLocales('cs','en-US')");
		assertEquals(dataInLocales(new Locale("cs"), new Locale("en", "US")), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("dataInLocales (   'cs' ,    'en-US' )");
		assertEquals(dataInLocales(new Locale("cs"), new Locale("en", "US")), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("dataInLocales(?)", new Locale("cs"));
		assertEquals(dataInLocales(new Locale("cs")), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("dataInLocales(@loc)", Map.of("loc", new Locale("cs")));
		assertEquals(dataInLocales(new Locale("cs")), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("dataInLocales(?)", List.of(new Locale("cs"), Locale.US));
		assertEquals(dataInLocales(new Locale("cs"), Locale.US), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("dataInLocales(@loc)", Map.of("loc", List.of(new Locale("cs"), Locale.US)));
		assertEquals(dataInLocales(new Locale("cs"), Locale.US), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("dataInLocales(?,?)", new Locale("cs"), Locale.US);
		assertEquals(dataInLocales(new Locale("cs"), Locale.US), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("dataInLocales(@loc1,@loc2)", Map.of("loc1", new Locale("cs"), "loc2", Locale.US));
		assertEquals(dataInLocales(new Locale("cs"), Locale.US), constraint10);
	}

	@Test
	void shouldNotParseDataInLocalesConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("dataInLocales('cs')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("dataInLocales(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("dataInLocales(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("dataInLocales"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("dataInLocales('cs',2)"));
	}

	@Test
	void shouldParseFacetSummaryConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("facetSummary()");
		assertEquals(facetSummary(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("facetSummary (  )");
		assertEquals(facetSummary(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetSummary(COUNTS)");
		assertEquals(facetSummary(COUNTS), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("facetSummary( IMPACT   )");
		assertEquals(facetSummary(IMPACT), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("facetSummary(?)", COUNTS);
		assertEquals(facetSummary(COUNTS), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetSummary(@mode)", Map.of("mode", COUNTS));
		assertEquals(facetSummary(COUNTS), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContentAll() ))");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent())), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContentAll() ), entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContent())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummary(IMPACT, entityGroupFetch(associatedDataContent())), constraint9);
	}

	@Test
	void shouldNotParseFacetSummaryConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummary(COUNTS)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummary(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummary(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary(NONE)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary(COUNTS,IMPACT)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary(COUNTS,attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummary(entityFetch())"));
	}

	@Test
	void shouldParseFacetSummaryOfReferenceConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("facetSummaryOfReference('parameter')");
		assertEquals(facetSummaryOfReference("parameter"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("facetSummaryOfReference (   'parameter'  )");
		assertEquals(facetSummaryOfReference("parameter"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS)");
		assertEquals(facetSummaryOfReference("parameter", COUNTS), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("facetSummaryOfReference(  'parameter' ,   IMPACT   )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("facetSummaryOfReference(?,?)", "parameter", COUNTS);
		assertEquals(facetSummaryOfReference("parameter", COUNTS), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetSummaryOfReference(@ref,@mode)", Map.of("ref", "parameter", "mode", COUNTS));
		assertEquals(facetSummaryOfReference("parameter", COUNTS), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT, entityFetch(  attributeContentAll() ))");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityFetch(attributeContent())), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT, entityFetch(  attributeContentAll() ), entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContent())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT,   entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityGroupFetch(associatedDataContent())), constraint9);
	}

	@Test
	void shouldNotParseFacetSummaryOfReferenceConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummaryOfReference(COUNTS)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummaryOfReference(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetSummaryOfReference(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',NONE)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,IMPACT)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',entityFetch())"));
	}

	@Test
	void shouldParseFacetGroupsConjunctionConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsConjunction (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6)) )");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?)))", 1);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?)))", List.of(1, 2));
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?,?)))", 1, 2);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsConjunctionConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction('a','b','c')"));
	}

	@Test
	void shouldParseFacetGroupsDisjunctionConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsDisjunction (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6) ))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?)))", 1);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?)))", List.of(1, 2));
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?,?)))", 1, 2);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsDisjunctionConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a','b','c')"));
	}

	@Test
	void shouldParseFacetGroupsNegationConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsNegation (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6) ))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?)))", 1);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?)))", List.of(1, 2));
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?,?)))", 1, 2);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsNegationConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a','b','c')"));
	}

	@Test
	void shouldParseAttributeHistogramConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("attributeHistogram(20, 'a')");
		assertEquals(attributeHistogram(20, "a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("attributeHistogram(20, 'a','b','c')");
		assertEquals(attributeHistogram(20, "a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("attributeHistogram ( 20  , 'a' ,  'b' ,'c' )");
		assertEquals(attributeHistogram(20, "a", "b", "c"), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("attributeHistogram(?, 'a')", 20);
		assertEquals(attributeHistogram(20, "a"), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"attributeHistogram(@buckets, 'a')",
			Map.of("buckets", 20)
		);
		assertEquals(attributeHistogram(20, "a"), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("attributeHistogram(?, ?)", 20, "a");
		assertEquals(attributeHistogram(20, "a"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"attributeHistogram(@buckets, @attr)",
			Map.of("buckets", 20, "attr", "a")
		);
		assertEquals(attributeHistogram(20, "a"), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"attributeHistogram(?, ?)",
			20,
			List.of("a", "b")
		);
		assertEquals(attributeHistogram(20, "a", "b"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"attributeHistogram(@buckets, @attr)",
			Map.of("buckets", 20, "attr", List.of("a", "b"))
		);
		assertEquals(attributeHistogram(20, "a", "b"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"attributeHistogram(?, ?, ?)",
			20,
			"a",
			"b"
		);
		assertEquals(attributeHistogram(20, "a", "b"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint(
			"attributeHistogram(@buckets, @attr1, @attr2)",
			Map.of("buckets", 20, "attr1", "a", "attr2", "b")
		);
		assertEquals(attributeHistogram(20, "a", "b"), constraint11);
	}

	@Test
	void shouldNotParseAttributeHistogramConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeHistogram(20,'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeHistogram(?,'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeHistogram(@buckets,'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("attributeHistogram"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("attributeHistogram()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("attributeHistogram(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("attributeHistogram('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("attributeHistogram('a','b')"));
	}

	@Test
	void shouldParsePriceHistogramConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("priceHistogram(20)");
		assertEquals(priceHistogram(20), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("priceHistogram ( 20 )");
		assertEquals(priceHistogram(20), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("priceHistogram(?)", 20);
		assertEquals(priceHistogram(20), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("priceHistogram(@buckets)", Map.of("buckets", 20));
		assertEquals(priceHistogram(20), constraint4);
	}

	@Test
	void shouldNotParsePriceHistogramConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceHistogram(20)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceHistogram(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceHistogram(@buckets)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceHistogram"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceHistogram('a')"));
	}

	@Test
	void shouldParseHierarchyDistanceConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("distance(1)");
		assertEquals(distance(1), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("distance(?)", 1);
		assertEquals(distance(1), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("distance(@dist)", Map.of("dist", 1));
		assertEquals(distance(1), constraint3);
	}

	@Test
	void shouldNotParseHierarchyDistanceConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("distance"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("distance('str')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("distance(1)"));
	}

	@Test
	void shouldParseHierarchyLevelConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("level(1)");
		assertEquals(level(1), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("level(?)", 1);
		assertEquals(level(1), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("level(@lev)", Map.of("lev", 1));
		assertEquals(level(1), constraint3);
	}

	@Test
	void shouldNotParseHierarchyLevelConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("level"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("level('str')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("level(1)"));
	}

	@Test
	void shouldParseHierarchyNodeConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("node(filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(node(filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint(
			"node(filterBy(entityPrimaryKeyInSet(?)))",
			1
		);
		assertEquals(node(filterBy(entityPrimaryKeyInSet(1))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint(
			"node(filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("pk", 1)
		);
		assertEquals(node(filterBy(entityPrimaryKeyInSet(1))), constraint3);
	}

	@Test
	void shouldNotParseHierarchyNodeConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("node"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("node(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("node(entityPrimaryKeyInSet(1))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("node(filterBy(entityPrimaryKeyInSet(1)))"));
	}

	@Test
	void shouldParseHierarchyStopAtConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("stopAt(distance(1))");
		assertEquals(stopAt(distance(1)), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("stopAt(level(1))");
		assertEquals(stopAt(level(1)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("stopAt(node(filterBy(entityPrimaryKeyInSet(1))))");
		assertEquals(stopAt(node(filterBy(entityPrimaryKeyInSet(1)))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("stopAt(distance(?))", 1);
		assertEquals(stopAt(distance(1)), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("stopAt(level(?))", 1);
		assertEquals(stopAt(level(1)), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("stopAt(node(filterBy(entityPrimaryKeyInSet(?))))", 1);
		assertEquals(stopAt(node(filterBy(entityPrimaryKeyInSet(1)))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("stopAt(distance(@dist))", Map.of("dist", 1));
		assertEquals(stopAt(distance(1)), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("stopAt(level(@lev))", Map.of("lev", 1));
		assertEquals(stopAt(level(1)), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("stopAt(node(filterBy(entityPrimaryKeyInSet(@pk))))", Map.of("pk", 1));
		assertEquals(stopAt(node(filterBy(entityPrimaryKeyInSet(1)))), constraint9);
	}

	@Test
	void shouldNotParseHierarchyStopAtConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("stopAt"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("stopAt(stopAt(distance(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("stopAt(level(1),distance(1))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("stopAt(level(1))"));
	}

	@Test
	void shouldParseHierarchyStatisticsConstraint() {
		final RequireConstraint constraint0 = parseRequireConstraintUnsafe("statistics()");
		assertEquals(statistics(), constraint0);

		final RequireConstraint constraint11 = parseRequireConstraintUnsafe("statistics(CHILDREN_COUNT)");
		assertEquals(statistics(StatisticsType.CHILDREN_COUNT), constraint11);

		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("statistics(COMPLETE_FILTER)");
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("statistics(COMPLETE_FILTER, CHILDREN_COUNT)");
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("statistics(COMPLETE_FILTER, CHILDREN_COUNT, QUERIED_ENTITY_COUNT)");
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("statistics(?)", StatisticsBase.COMPLETE_FILTER);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER), constraint4);

		final RequireConstraint constraint12 = parseRequireConstraint("statistics(?)", StatisticsType.CHILDREN_COUNT);
		assertEquals(statistics(StatisticsType.CHILDREN_COUNT), constraint12);

		final RequireConstraint constraint5 = parseRequireConstraint("statistics(?, ?)", StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("statistics(?, ?, ?)", StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"statistics(@base)",
			Map.of("base", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER), constraint7);

		final RequireConstraint constraint13 = parseRequireConstraint(
			"statistics(@type)",
			Map.of("type", StatisticsType.CHILDREN_COUNT)
		);
		assertEquals(statistics(StatisticsType.CHILDREN_COUNT), constraint13);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"statistics(@base, @type)",
			Map.of(
				"base", StatisticsBase.COMPLETE_FILTER,
				"type", StatisticsType.CHILDREN_COUNT
			)
		);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"statistics(@base, @type, @type2)",
			Map.of(
				"base", StatisticsBase.COMPLETE_FILTER,
				"type", StatisticsType.CHILDREN_COUNT,
				"type2", StatisticsType.QUERIED_ENTITY_COUNT
			)
		);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"statistics(@settings)",
			Map.of(
				"settings", List.of(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
			)
		);
		assertEquals(statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT), constraint10);
	}

	@Test
	void shouldNotParseHierarchyStatisticsConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("statistics"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("statistics('CHILDREN_COUNT')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("statistics(COMPLETE_FILTER,COMPLETE_FILTER)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("statistics(CHILDREN_COUNT)"));
	}

	@Test
	void shouldParseHierarchyFromRootConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("fromRoot('megaMenu')");
		assertEquals(fromRoot("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("fromRoot('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(fromRoot("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("fromRoot('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(fromRoot("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("fromRoot('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(fromRoot("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"fromRoot(?, statistics(?))",
			"megaMenu", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(fromRoot("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"fromRoot(?, entityFetch(attributeContent(?)), statistics(?))",
			"megaMenu", "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(fromRoot("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"fromRoot(@out, statistics(@stat))",
			Map.of("out", "megaMenu", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(fromRoot("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"fromRoot(@out, entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("out", "megaMenu", "name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(fromRoot("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint8);
	}

	@Test
	void shouldNotParseHierarchyFromRootConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromRoot"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromRoot(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromRoot(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromRoot('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("fromRoot('megaMenu', statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyFromNodeConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe(
			"fromNode('megaMenu',node(filterBy(entityPrimaryKeyInSet(1))))"
		);
		assertEquals(fromNode("megaMenu", node(filterBy(entityPrimaryKeyInSet(1)))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe(
			"fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER))"
		);
		assertEquals(
			fromNode("megaMenu", node(filterBy(entityPrimaryKeyInSet(1))), statistics(StatisticsBase.COMPLETE_FILTER)),
			constraint2
		);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe(
			"fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')))"
		);
		assertEquals(
			fromNode("megaMenu", node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent("code"))),
			constraint3
		);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe(
			"fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))"
		);
		assertEquals(
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(1))),
				entityFetch(attributeContent("code")),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint4
		);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"fromNode(?, node(filterBy(entityPrimaryKeyInSet(?))), statistics(?))",
			"megaMenu", 1, StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(
			fromNode("megaMenu", node(filterBy(entityPrimaryKeyInSet(1))), statistics(StatisticsBase.COMPLETE_FILTER)),
			constraint5
		);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"fromNode(?, node(filterBy(entityPrimaryKeyInSet(?))), entityFetch(attributeContent(?)), statistics(?))",
			"megaMenu", 1, "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(1))),
				entityFetch(attributeContent("code")),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint6
		);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"fromNode(@out, node(filterBy(entityPrimaryKeyInSet(@pk))), statistics(@stat))",
			Map.of("out", "megaMenu", "pk", 1, "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(1))),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint7
		);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"fromNode(@out, node(filterBy(entityPrimaryKeyInSet(@pk))), entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("out", "megaMenu", "pk", 1, "name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(1))),
				entityFetch(attributeContent("code")),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint8
		);
	}

	@Test
	void shouldNotParseHierarchyFromNodeConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode(node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode(node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', entityFetch(attributeContent('code')), node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyChildrenConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("children('megaMenu')");
		assertEquals(children("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("children('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(children("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("children('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(children("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("children('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(children("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"children(?, statistics(?))",
			"megaMenu", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(children("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"children(?, entityFetch(attributeContent(?)), statistics(?))",
			"megaMenu", "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(children("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"children(@out, statistics(@stat))",
			Map.of("out", "megaMenu", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(children("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"children(@out, entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("out", "megaMenu", "name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(children("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint8);
	}

	@Test
	void shouldNotParseHierarchyChildrenConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("children"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("children(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("children(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("children('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("children('megaMenu', statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchySiblingsConstraint() {
		final RequireConstraint constraint9 = parseRequireConstraint("siblings()");
		assertEquals(siblings(), constraint9);

		final RequireConstraint constraint1 = parseRequireConstraint("siblings('megaMenu')");
		assertEquals(siblings("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("siblings('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(siblings("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("siblings(statistics(COMPLETE_FILTER))");
		assertEquals(siblings(statistics(StatisticsBase.COMPLETE_FILTER)), constraint10);

		final RequireConstraint constraint3 = parseRequireConstraint("siblings('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(siblings("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint11 = parseRequireConstraint("siblings(entityFetch(attributeContent('code')))");
		assertEquals(siblings(entityFetch(attributeContent("code"))), constraint11);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("siblings('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(siblings("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint4);

		final RequireConstraint constraint12 = parseRequireConstraintUnsafe("siblings(entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(siblings(entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint12);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"siblings(?, statistics(?))",
			"megaMenu", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(siblings("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint5);

		final RequireConstraint constraint13 = parseRequireConstraint("siblings(statistics(?))", StatisticsBase.COMPLETE_FILTER);
		assertEquals(siblings(statistics(StatisticsBase.COMPLETE_FILTER)), constraint13);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"siblings(?, entityFetch(attributeContent(?)), statistics(?))",
			"megaMenu", "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(siblings("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint6);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"siblings(entityFetch(attributeContent(?)), statistics(?))",
			"code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(siblings(entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint14);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"siblings(@out, statistics(@stat))",
			Map.of("out", "megaMenu", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(siblings("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint7);

		final RequireConstraint constraint15 = parseRequireConstraint(
			"siblings(statistics(@stat))",
			Map.of("stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(siblings(statistics(StatisticsBase.COMPLETE_FILTER)), constraint15);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"siblings(@out, entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("out", "megaMenu", "name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(siblings("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint8);

		final RequireConstraint constraint16 = parseRequireConstraint(
			"siblings(entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(siblings(entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint16);
	}

	@Test
	void shouldNotParseHierarchySiblingsConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("siblings"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("siblings('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("siblings('megaMenu', statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyParentsConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("parents('megaMenu')");
		assertEquals(parents("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("parents('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(parents("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("parents('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("parents('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint4);

		final RequireConstraint constraint9 = parseRequireConstraint("parents('megaMenu', siblings())");
		assertEquals(parents("megaMenu", siblings()), constraint9);

		final RequireConstraint constraint16 = parseRequireConstraintUnsafe(
			"parents('megaMenu', siblings(), statistics(COMPLETE_FILTER))"
		);
		assertEquals(
			parents(
				"megaMenu",
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint16
		);

		final RequireConstraint constraint10 = parseRequireConstraint("parents('megaMenu', entityFetch(attributeContent('code')), siblings())");
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code")), siblings()), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraintUnsafe(
			"parents('megaMenu', entityFetch(attributeContent('code')), siblings(), statistics(COMPLETE_FILTER))"
		);
		assertEquals(
			parents(
				"megaMenu",
				entityFetch(attributeContent("code")),
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint11
		);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"parents(?, statistics(?))",
			"megaMenu", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(parents("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"parents(?, entityFetch(attributeContent(?)), statistics(?))",
			"megaMenu", "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"parents(@out, statistics(@stat))",
			Map.of("out", "megaMenu", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(parents("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"parents(@out, entityFetch(attributeContent(@name)), statistics(@stat))",
			Map.of("out", "megaMenu", "name", "code", "stat", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint8);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"parents(?, entityFetch(attributeContent(?)), siblings(), statistics(?))",
			"megaMenu", "code", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(
			parents(
				"megaMenu",
				entityFetch(attributeContent("code")),
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraint(
			"parents(@out, entityFetch(attributeContent(@name)), siblings(), statistics(@base))",
			Map.of("out", "megaMenu", "name", "code", "base", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(
			parents(
				"megaMenu",
				entityFetch(attributeContent("code")),
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint15
		);

		final RequireConstraint constraint17 = parseRequireConstraint(
			"parents(?, siblings(), statistics(?))",
			"megaMenu", StatisticsBase.COMPLETE_FILTER
		);
		assertEquals(
			parents(
				"megaMenu",
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint(
			"parents(@out, siblings(), statistics(@base))",
			Map.of("out", "megaMenu", "base", StatisticsBase.COMPLETE_FILTER)
		);
		assertEquals(
			parents(
				"megaMenu",
				siblings(),
				statistics(StatisticsBase.COMPLETE_FILTER)
			),
			constraint18
		);
	}

	@Test
	void shouldNotParseHierarchyParentsConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("parents"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("parents(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("parents(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("parents('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("parents('megaMenu', siblings(), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("parents('megaMenu', statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyOfSelfConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("hierarchyOfSelf(fromRoot('megaMenu'))");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("hierarchyOfSelf(fromRoot('megaMenu'), parents('parents', siblings()))");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu"), parents("parents", siblings())), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint(
			"hierarchyOfSelf(orderBy(attributeNatural('code')), fromRoot('megaMenu'))"
		);
		assertEquals(
			hierarchyOfSelf(orderBy(attributeNatural("code")), fromRoot("megaMenu")),
			constraint3
		);

		final RequireConstraint constraint4 = parseRequireConstraint(
			"hierarchyOfSelf(orderBy(attributeNatural('code')), fromRoot('megaMenu'), parents('parents', siblings()))"
		);
		assertEquals(
			hierarchyOfSelf(
				orderBy(attributeNatural("code")),
				fromRoot("megaMenu"),
				parents("parents", siblings())
			),
			constraint4
		);

		final RequireConstraint constraint5 = parseRequireConstraint("hierarchyOfSelf(fromRoot(?))", "megaMenu");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint(
			"hierarchyOfSelf(fromRoot(?), parents(?, siblings()))",
			"megaMenu", "parents"
		);
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu"), parents("parents", siblings())), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"hierarchyOfSelf(orderBy(attributeNatural(?)), fromRoot(?))",
			"code", "megaMenu"
		);
		assertEquals(
			hierarchyOfSelf(orderBy(attributeNatural("code")), fromRoot("megaMenu")),
			constraint7
		);

		final RequireConstraint constraint8 = parseRequireConstraint(
			"hierarchyOfSelf(fromRoot(@out))",
			Map.of("out", "megaMenu")
		);
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"hierarchyOfSelf(fromRoot(@out1), parents(@out2, siblings()))",
			Map.of("out1", "megaMenu", "out2", "parents")
		);
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu"), parents("parents", siblings())), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"hierarchyOfSelf(orderBy(attributeNatural(@name)), fromRoot(@out))",
			Map.of("name", "code", "out", "megaMenu")
		);
		assertEquals(
			hierarchyOfSelf(orderBy(attributeNatural("code")), fromRoot("megaMenu")),
			constraint10
		);
	}

	@Test
	void shouldNotParseHierarchyOfSelfConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(orderBy(random()))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(fromRoot('megaMenu'), orderBy(attributeNatural('code')))"));
	}

	@Test
	void shouldParseHierarchyOfReferenceConstraint() {
		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("hierarchyOfReference('a', fromRoot('megaMenu'))");
		assertEquals(
			hierarchyOfReference("a", fromRoot("megaMenu")),
			constraint10
		);

		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("hierarchyOfReference('a', LEAVE_EMPTY, fromRoot('megaMenu'))");
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
			constraint1
		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("hierarchyOfReference('a', 'b', LEAVE_EMPTY, fromRoot('megaMenu'))");
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint2
//		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint3 = parseRequireConstraintUnsafe(
//			"hierarchyOfReference('a', 'b', LEAVE_EMPTY, orderBy(random()), fromRoot('megaMenu'))"
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
//			constraint3
//		);

		final RequireConstraint constraint11 = parseRequireConstraint(
			"hierarchyOfReference(?, fromRoot(?))",
			"a", "megaMenu"
		);
		assertEquals(
			hierarchyOfReference("a", fromRoot("megaMenu")),
			constraint11
		);

		final RequireConstraint constraint4 = parseRequireConstraint(
			"hierarchyOfReference(?, ?, fromRoot(?))",
			"a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "megaMenu"
		);
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
			constraint4
		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint5 = parseRequireConstraint(
//			"hierarchyOfReference(?, ?, fromRoot(?))",
//			new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "megaMenu"
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint5
//		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint6 = parseRequireConstraint(
//			"hierarchyOfReference(?, ?, orderBy(random()), fromRoot(?))",
//			new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "megaMenu"
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
//			constraint6
//		);

		final RequireConstraint constraint12 = parseRequireConstraint(
			"hierarchyOfReference(@ref, fromRoot(@out))",
			Map.of("ref", "a", "out", "megaMenu")
		);
		assertEquals(
			hierarchyOfReference("a", fromRoot("megaMenu")),
			constraint12
		);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"hierarchyOfReference(@ref, @beh, fromRoot(@out))",
			Map.of("ref", "a", "beh", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "out", "megaMenu")
		);
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
			constraint7
		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint8 = parseRequireConstraint(
//			"hierarchyOfReference(@refs, @beh, fromRoot(@out))",
//			Map.of("refs", new String[] {"a", "b"}, "beh", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "out", "megaMenu")
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint8
//		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint13 = parseRequireConstraint(
//			"hierarchyOfReference(@refs, orderBy(random()), fromRoot(@out))",
//			Map.of("refs", new String[] {"a", "b"}, "out", "megaMenu")
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, orderBy(random()), fromRoot("megaMenu")),
//			constraint13
//		);

		// todo lho support for multiple reference names
//		final RequireConstraint constraint9 = parseRequireConstraint(
//			"hierarchyOfReference(@refs, @beh, orderBy(random()), fromRoot(@out))",
//			Map.of("refs", new String[] {"a", "b"}, "beh", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "out", "megaMenu")
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
//			constraint9
//		);

		final RequireConstraint constraint13 = parseRequireConstraintUnsafe("hierarchyOfReference('a', orderBy(random()), fromRoot('megaMenu'))");
		assertEquals(
			hierarchyOfReference("a", orderBy(random()), fromRoot("megaMenu")),
			constraint13
		);

		final RequireConstraint constraint14 = parseRequireConstraintUnsafe("hierarchyOfReference('a', LEAVE_EMPTY, orderBy(random()), fromRoot('megaMenu'))");
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraint("hierarchyOfReference(?, orderBy(random()), fromRoot(?))", "a", "megaMenu");
		assertEquals(
			hierarchyOfReference("a", orderBy(random()), fromRoot("megaMenu")),
			constraint15
		);

		final RequireConstraint constraint16 = parseRequireConstraint(
			"hierarchyOfReference(?, ?, orderBy(random()), fromRoot(?))",
			"a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "megaMenu"
		);
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
			constraint16
		);

		final RequireConstraint constraint17 = parseRequireConstraint(
			"hierarchyOfReference(@ref, orderBy(random()), fromRoot(@out))",
			Map.of("ref", "a", "out", "megaMenu")
		);
		assertEquals(
			hierarchyOfReference("a", orderBy(random()), fromRoot("megaMenu")),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint(
			"hierarchyOfReference(@ref, @beh, orderBy(random()), fromRoot(@out))",
			Map.of("ref", "a", "beh", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "out", "megaMenu")
		);
		assertEquals(
			hierarchyOfReference("a", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, orderBy(random()), fromRoot("megaMenu")),
			constraint18
		);
	}

	@Test
	void shouldNotParseHierarchyOfReferenceConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyOfReference"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference(attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a',attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a', LEAVE_EMPTY)"));
	}

	@Test
	void shouldParseQueryTelemetryConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("queryTelemetry()");
		assertEquals(queryTelemetry(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("queryTelemetry (  )");
		assertEquals(queryTelemetry(), constraint2);
	}

	@Test
	void shouldNotParseQueryTelemetryConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("queryTelemetry"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("queryTelemetry('a','b')"));
	}


	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
	 *
	 * @param string string to parse
	 * @param positionalArguments positional arguments to substitute
	 * @return parsed constraint
	 */
	private RequireConstraint parseRequireConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
		return ParserExecutor.execute(
			new ParseContext(positionalArguments),
			() -> ParserFactory.getParser(string).requireConstraint().accept(new EvitaQLRequireConstraintVisitor())
 		);
    }

	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
	 *
	 * @param string string to parse
	 * @param namedArguments named arguments to substitute
	 * @return parsed constraint
	 */
	private RequireConstraint parseRequireConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
		return ParserExecutor.execute(
			new ParseContext(namedArguments),
			() -> ParserFactory.getParser(string).requireConstraint().accept(new EvitaQLRequireConstraintVisitor())
		);
	}

	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
	 *
	 * @param string string to parse
	 * @return parsed constraint
	 */
	private RequireConstraint parseRequireConstraintUnsafe(@Nonnull String string) {
		final ParseContext context = new ParseContext();
		context.setMode(ParseMode.UNSAFE);
		return ParserExecutor.execute(
			context,
			() -> ParserFactory.getParser(string).requireConstraint().accept(new EvitaQLRequireConstraintVisitor())
		);
	}
}
