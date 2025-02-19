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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.dataType.Scope;
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
		assertEquals(require(entityFetch(attributeContentAll())), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("require (  entityFetch(  attributeContentAll() )  )");
		assertEquals(require(entityFetch(attributeContentAll())), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("require()");
		assertEquals(require(), constraint4);
	}

	@Test
	void shouldNotParseRequireContainerConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("require"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("require('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("require(attributeEquals('a',1))"));
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
	void shouldParsePageConstraintWithSpacing() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("page(10,20,spacing(gap(1,'true')))");
		assertEquals(page(10, 20, spacing(gap(1, "true"))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("page (  10 ,20 , spacing ( gap ( 1 , 'true' ) ) )");
		assertEquals(page(10, 20, spacing(gap(1, "true"))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("page(?,?,spacing(gap(?,?)))", 10, 20, 1, "true");
		assertEquals(page(10, 20, spacing(gap(1, "true"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint(
			"page(@page,@size,spacing(gap(@gapSize,@expression)))",
			Map.of("page", 10, "size", 20, "gapSize", 1, "expression", "true")
		);
		assertEquals(page(10, 20, spacing(gap(1, "true"))), constraint4);
	}

	@Test
	void shouldNotParsePageConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("page(10,20)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("page(?,?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("page(@page,@size)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("page"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("page()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("page(1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("page('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("page(1,'a')"));
	}

	@Test
	void shouldNotParseSpacing() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("spacing(gap(10,20))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("spacing(gap(?,?))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("spacing(gap(@page,@size))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("spacing"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("gap"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("spacing(page(1, 12))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("spacing(gap(1))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("spacing(gap('a', 1))"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("strip(10,20)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("strip(?,?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("strip(@off,@size)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("strip"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("strip()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("strip(1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("strip('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("strip(1,'a')"));
	}

	@Test
	void shouldParseEntityFetchConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("entityFetch()");
		assertEquals(entityFetch(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("entityFetch (  )");
		assertEquals(entityFetch(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("entityFetch(attributeContentAll(),associatedDataContentAll())");
		assertEquals(entityFetch(attributeContentAll(), associatedDataContentAll()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityFetch(  attributeContentAll  (   ) ,   associatedDataContentAll   ( ) )");
		assertEquals(entityFetch(attributeContentAll(), associatedDataContentAll()), constraint4);
	}

	@Test
	void shouldNotParseEntityFetchConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("entityFetch"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("entityFetch('a')"));
	}

	@Test
	void shouldParseEntityGroupFetchConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("entityGroupFetch()");
		assertEquals(entityGroupFetch(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("entityGroupFetch (  )");
		assertEquals(entityGroupFetch(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("entityGroupFetch(attributeContentAll(),associatedDataContentAll())");
		assertEquals(entityGroupFetch(attributeContentAll(), associatedDataContentAll()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityGroupFetch(  attributeContentAll  (   ) ,   associatedDataContentAll   ( ) )");
		assertEquals(entityGroupFetch(attributeContentAll(), associatedDataContentAll()), constraint4);
	}

	@Test
	void shouldNotParseEntityGroupFetchConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("entityGroupFetch"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("entityGroupFetch('a')"));
	}

	@Test
	void shouldParseAttributeContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("attributeContentAll()");
		assertEquals(attributeContentAll(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("attributeContentAll (  )");
		assertEquals(attributeContentAll(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("attributeContent('a')");
		assertEquals(attributeContent("a"), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("attributeContent('a','b','c')");
		assertEquals(attributeContent("a", "b", "c"), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraintUnsafe("attributeContent (  'a' ,'b'  ,  'c' )");
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeContentAll"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeContentAll('a',1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeContent('a',1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeContent()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeContent('a')"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceContent(ALL)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceContent(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceContent(@mode)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceContent('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceContent(ALL, 'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContent"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContent()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContent(AA)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContent(ALL,1)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContentAll"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceContentAll('a')"));
	}

	@Test
	void shouldParseAssociatedDataContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("associatedDataContent('a')");
		assertEquals(associatedDataContent("a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("associatedDataContent(?,?,?)", "a", "b", "c");
		assertEquals(associatedDataContent("a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("associatedDataContent (  ? , ?  , ? )", "a", "b", "c");
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
		assertEquals(associatedDataContentAll(), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("associatedDataContentAll (  )");
		assertEquals(associatedDataContentAll(), constraint13);
	}

	@Test
	void shouldNotParseAssociatedDataContentConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("associatedDataContent"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("associatedDataContent(1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("associatedDataContent('a',1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("associatedDataContent()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("associatedDataContent('a')"));
	}

	@Test
	void shouldParseReferenceContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("referenceContent('a')");
		assertEquals(referenceContent("a"), constraint1);

		final RequireConstraint constraint1aa = parseRequireConstraint("referenceContent(?)", "a");
		assertEquals(referenceContent("a"), constraint1aa);

		final RequireConstraint constraint1a = parseRequireConstraint("referenceContentWithAttributes(?, attributeContent(?))", "a", "order");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				attributeContent("order")
			),
			constraint1a
		);

		final RequireConstraint constraint1b = parseRequireConstraint("referenceContentWithAttributes(?)", "a");
		assertEquals(referenceContentWithAttributes("a"), constraint1b);

		final RequireConstraint constraint1c = parseRequireConstraint("referenceContentAllWithAttributes()");
		assertEquals(referenceContentAllWithAttributes(), constraint1c);

		final RequireConstraint constraint1d = parseRequireConstraint("referenceContentAllWithAttributes(attributeContent(?))", "a");
		assertEquals(referenceContentAllWithAttributes(attributeContent("a")), constraint1d);

		final RequireConstraint constraint2 = parseRequireConstraint("referenceContent(?,?,?)", "a", "b", "c");
		assertEquals(referenceContent("a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("referenceContent (  ? , ?  , ? )", "a", "b", "c");
		assertEquals(referenceContent("a", "b", "c"), constraint3);

		final RequireConstraint constraint6 = parseRequireConstraint("referenceContent(?,?,?)", "BRAND", "CATEGORY", "stock");
		assertEquals(referenceContent("BRAND", "CATEGORY", "stock"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll())
			),
			constraint7
		);

		final RequireConstraint constraint7a = parseRequireConstraint("referenceContent(?, filterBy(attributeEqualsTrue(?)), entityFetch(attributeContentAll()))", "a", "code");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll())
			),
			constraint7a
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
			"referenceContent(?,filterBy(attributeEquals(?,?)),entityFetch(attributeContent(?)))",
			"a",
			"code",
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

		final RequireConstraint constraint17 = parseRequireConstraint("referenceContent(?, entityFetch(attributeContentAll()))", "a");
		assertEquals(
			referenceContent(
				"a",
				entityFetch(attributeContentAll())
			),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraintUnsafe("referenceContent('a', 'b', entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityFetch(attributeContentAll())
			),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraint("referenceContent(?, ?, entityFetch(attributeContentAll()), entityGroupFetch())", "a", "b");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint19
		);

		final RequireConstraint constraint20 = parseRequireConstraint("referenceContent(?, ?, entityGroupFetch())", "a", "b");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityGroupFetch()
			),
			constraint20
		);

		final RequireConstraint constraint21 = parseRequireConstraint("referenceContent(?, filterBy(attributeEqualsTrue(?)), entityFetch(attributeContentAll()), entityGroupFetch())", "a", "code");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21
		);

		final RequireConstraint constraint21a = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), attributeContent(?), entityFetch(attributeContentAll()), entityGroupFetch())", "a", "code", "order");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEqualsTrue("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21a
		);

		final RequireConstraint constraint21b = parseRequireConstraintUnsafe("referenceContentWithAttributes('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21b
		);

		final RequireConstraint constraint21c = parseRequireConstraint("referenceContentAllWithAttributes(entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentAllWithAttributes(
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21c
		);

		final RequireConstraint constraint21d = parseRequireConstraint("referenceContentAllWithAttributes(attributeContent(?), entityFetch(attributeContentAll()), entityGroupFetch())", "a");
		assertEquals(
			referenceContentAllWithAttributes(
				attributeContent("a"),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21d
		);

		final RequireConstraint constraint22 = parseRequireConstraint("referenceContent(?, filterBy(attributeEqualsTrue(?)), entityGroupFetch(attributeContentAll()))", "a", "code");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContentAll())
			),
			constraint22
		);

		final RequireConstraint constraint22a = parseRequireConstraintUnsafe("referenceContentWithAttributes('a', filterBy(attributeEqualsTrue('code')), attributeContent('order'), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEqualsTrue("code")),
				attributeContent("order"),
				entityGroupFetch(attributeContentAll())
			),
			constraint22a
		);

		final RequireConstraint constraint22b = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), entityGroupFetch(attributeContentAll()))", "a", "code");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContentAll())
			),
			constraint22b
		);

		final RequireConstraint constraint22c = parseRequireConstraint("referenceContentAllWithAttributes(entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				entityGroupFetch(attributeContentAll())
			),
			constraint22c
		);

		final RequireConstraint constraint22d = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(attributeContent('a'), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				attributeContent("a"),
				entityGroupFetch(attributeContentAll())
			),
			constraint22d
		);

		final RequireConstraint constraint23 = parseRequireConstraint("referenceContentAll(entityFetch(priceContentRespectingFilter()), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				entityFetch(priceContentRespectingFilter()),
				entityGroupFetch(attributeContentAll())
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
			"referenceContent(?,filterBy(attributeEquals(?,?)),entityFetch(attributeContent(?)),entityGroupFetch(attributeContent(?)))",
			"a",
			"code",
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

		final RequireConstraint constraint28 = parseRequireConstraintUnsafe("referenceContent('a', orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint28
		);

		final RequireConstraint constraint28a = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), attributeContent(?), entityFetch(attributeContentAll()))", "a", "code", "order");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll())
			),
			constraint28a
		);

		final RequireConstraint constraint28b = parseRequireConstraintUnsafe("referenceContentWithAttributes('a', orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint28b
		);

		final RequireConstraint constraint28c = parseRequireConstraint("referenceContentAllWithAttributes(entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				entityFetch(attributeContentAll())
			),
			constraint28c
		);

		final RequireConstraint constraint28d = parseRequireConstraint("referenceContentAllWithAttributes(attributeContent(?), entityFetch(attributeContentAll()))", "a");
		assertEquals(
			referenceContentAllWithAttributes(
				attributeContent("a"),
				entityFetch(attributeContentAll())
			),
			constraint28d
		);

		final RequireConstraint constraint29 = parseRequireConstraintUnsafe("referenceContent('a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint29
		);

		final RequireConstraint constraint29a = parseRequireConstraint(
			"referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), orderBy(attributeNatural(?)), attributeContent(?), entityFetch(attributeContentAll()))",
			"a", "code", "code", "order"
		);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll())
			),
			constraint29a
		);

		final RequireConstraint constraint29b = parseRequireConstraint(
			"referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), orderBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			"a", "code", "code"
		);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint29b
		);

		final RequireConstraint constraint30 = parseRequireConstraint(
			"referenceContent(?, filterBy(attributeEqualsTrue(?)), orderBy(attributeNatural(?)))",
			"a", "code", "code"
		);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code"))
			),
			constraint30
		);

		final RequireConstraint constraint30a = parseRequireConstraint(
			"referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), orderBy(attributeNatural(?)), attributeContent(?))",
			"a", "code", "code", "order"
		);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				attributeContent("order")
			),
			constraint30a
		);

		final RequireConstraint constraint30b = parseRequireConstraint(
			"referenceContentWithAttributes(?, filterBy(attributeEqualsTrue(?)), orderBy(attributeNatural(?)))",
			"a", "code", "code"
		);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code"))
			),
			constraint30b
		);

		final RequireConstraint constraint31 = parseRequireConstraint("referenceContent(?, orderBy(attributeNatural(?)))", "a", "code");
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code"))
			),
			constraint31
		);

		final RequireConstraint constraint31a = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), attributeContent(?))", "a", "code", "order");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order")
			),
			constraint31a
		);

		final RequireConstraint constraint31b = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)))", "a", "code");
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code"))
			),
			constraint31b
		);

		final RequireConstraint constraint32 = parseRequireConstraint("referenceContent(?, orderBy(attributeNatural(?)), page(?, ?))", "a", "code", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code")),
				page(2, 40)
			),
			constraint32
		);

		final RequireConstraint constraint32a = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), attributeContent(?), page(?, ?))", "a", "code", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				page(2, 40)
			),
			constraint32a
		);

		final RequireConstraint constraint32b = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), page(?, ?))", "a", "code", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				page(2, 40)
			),
			constraint32b
		);

		final RequireConstraint constraint33 = parseRequireConstraint("referenceContent(?, orderBy(attributeNatural(?)), strip(?, ?))", "a", "code", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code")),
				strip(2, 40)
			),
			constraint33
		);

		final RequireConstraint constraint33a = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), attributeContent(?), strip(?, ?))", "a", "code", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				strip(2, 40)
			),
			constraint33a
		);

		final RequireConstraint constraint33b = parseRequireConstraint("referenceContentWithAttributes(?, orderBy(attributeNatural(?)), strip(?, ?))", "a", "code", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				orderBy(attributeNatural("code")),
				strip(2, 40)
			),
			constraint33b
		);

		final RequireConstraint constraint34 = parseRequireConstraint("referenceContent(?, filterBy(attributeEquals(?, ?)), page(?, ?))", "a", "code", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				page(2, 40)
			),
			constraint34
		);

		final RequireConstraint constraint34a = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), attributeContent(?), page(?, ?))", "a", "code", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				attributeContent("order"),
				page(2, 40)
			),
			constraint34a
		);

		final RequireConstraint constraint34b = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), page(?, ?))", "a", "code", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				page(2, 40)
			),
			constraint34b
		);

		final RequireConstraint constraint35 = parseRequireConstraint("referenceContent(?, filterBy(attributeEquals(?, ?)), strip(?, ?))", "a", "code", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				strip(2, 40)
			),
			constraint35
		);

		final RequireConstraint constraint35a = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), attributeContent(?), strip(?, ?))", "a", "code", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				attributeContent("order"),
				strip(2, 40)
			),
			constraint35a
		);

		final RequireConstraint constraint35b = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), strip(?, ?))", "a", "code", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				strip(2, 40)
			),
			constraint35b
		);

		final RequireConstraint constraint36 = parseRequireConstraint("referenceContent(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), page(?, ?))", "a", "code", "a", "code", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				page(2, 40)
			),
			constraint36
		);

		final RequireConstraint constraint36a = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), attributeContent(?), page(?, ?))", "a", "code", "a", "code", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				page(2, 40)
			),
			constraint36a
		);

		final RequireConstraint constraint36b = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), page(?, ?))", "a", "code", "a", "code", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				page(2, 40)
			),
			constraint36b
		);

		final RequireConstraint constraint37 = parseRequireConstraint("referenceContent(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), strip(?, ?))", "a", "code", "a", "code", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				strip(2, 40)
			),
			constraint37
		);

		final RequireConstraint constraint37a = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), attributeContent(?), strip(?, ?))", "a", "code", "a", "code", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				strip(2, 40)
			),
			constraint37a
		);

		final RequireConstraint constraint37b = parseRequireConstraint("referenceContentWithAttributes(?, filterBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), strip(?, ?))", "a", "code", "a", "code", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				filterBy(attributeEquals("code", "a")),
				orderBy(attributeNatural("code")),
				strip(2, 40)
			),
			constraint37b
		);

		final RequireConstraint constraint38 = parseRequireConstraint("referenceContent(?, entityFetch(), page(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				entityFetch(),
				page(2, 40)
			),
			constraint38
		);

		final RequireConstraint constraint38a = parseRequireConstraint("referenceContentWithAttributes(?, entityFetch(), page(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				entityFetch(),
				page(2, 40)
			),
			constraint38a
		);

		final RequireConstraint constraint38b = parseRequireConstraint("referenceContentWithAttributes(?, attributeContent(?), entityFetch(), page(?, ?))", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				attributeContent("order"),
				entityFetch(),
				page(2, 40)
			),
			constraint38b
		);

		final RequireConstraint constraint39 = parseRequireConstraint("referenceContent(?, entityFetch(), strip(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				entityFetch(),
				strip(2, 40)
			),
			constraint39
		);

		final RequireConstraint constraint39a = parseRequireConstraint("referenceContentWithAttributes(?, entityFetch(), strip(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				entityFetch(),
				strip(2, 40)
			),
			constraint39a
		);

		final RequireConstraint constraint39b = parseRequireConstraint("referenceContentWithAttributes(?, attributeContent(?), entityFetch(), strip(?, ?))", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				attributeContent("order"),
				entityFetch(),
				strip(2, 40)
			),
			constraint39b
		);

		final RequireConstraint constraint40 = parseRequireConstraint("referenceContent(?, entityGroupFetch(), page(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				entityGroupFetch(),
				page(2, 40)
			),
			constraint40
		);

		final RequireConstraint constraint40a = parseRequireConstraint("referenceContentWithAttributes(?, entityGroupFetch(), page(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				entityGroupFetch(),
				page(2, 40)
			),
			constraint40a
		);

		final RequireConstraint constraint40b = parseRequireConstraint("referenceContentWithAttributes(?, attributeContent(?), entityGroupFetch(), page(?, ?))", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				attributeContent("order"),
				entityGroupFetch(),
				page(2, 40)
			),
			constraint40b
		);

		final RequireConstraint constraint41 = parseRequireConstraint("referenceContent(?, entityGroupFetch(), strip(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContent(
				"a",
				entityGroupFetch(),
				strip(2, 40)
			),
			constraint41
		);

		final RequireConstraint constraint41a = parseRequireConstraint("referenceContentWithAttributes(?, entityGroupFetch(), strip(?, ?))", "a", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				entityGroupFetch(),
				strip(2, 40)
			),
			constraint41a
		);

		final RequireConstraint constraint41b = parseRequireConstraint("referenceContentWithAttributes(?, attributeContent(?), entityGroupFetch(), strip(?, ?))", "a", "order", 2, 40);
		assertEquals(
			referenceContentWithAttributes(
				"a",
				attributeContent("order"),
				entityGroupFetch(),
				strip(2, 40)
			),
			constraint41b
		);

		final RequireConstraint constraint42 = parseRequireConstraint("referenceContentAll(?, strip(?, ?))", ManagedReferencesBehaviour.EXISTING, 2, 40);
		assertEquals(
			referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				strip(2, 40)
			),
			constraint42
		);

		final RequireConstraint constraint43 = parseRequireConstraint("referenceContentAll(?, entityGroupFetch(), strip(?, ?))", ManagedReferencesBehaviour.EXISTING, 2, 40);
		assertEquals(
			referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch(),
				strip(2, 40)
			),
			constraint43
		);

		final RequireConstraint constraint44 = parseRequireConstraint("referenceContentAll(?, entityGroupFetch())", ManagedReferencesBehaviour.EXISTING);
		assertEquals(
			referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch()
			),
			constraint44
		);

		final RequireConstraint constraint45 = parseRequireConstraint("referenceContentAllWithAttributes(?, strip(?, ?))", ManagedReferencesBehaviour.EXISTING, 2, 40);
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				strip(2, 40)
			),
			constraint45
		);

		final RequireConstraint constraint46 = parseRequireConstraint("referenceContentAllWithAttributes(?, entityGroupFetch(), strip(?, ?))", ManagedReferencesBehaviour.EXISTING, 2, 40);
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch(),
				strip(2, 40)
			),
			constraint46
		);

		final RequireConstraint constraint47 = parseRequireConstraint("referenceContentAllWithAttributes(?, entityGroupFetch())", ManagedReferencesBehaviour.EXISTING);
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch()
			),
			constraint47
		);

		final RequireConstraint constraint48 = parseRequireConstraint(
			"require(" +
					"entityFetch(" +
						"referenceContent(" +
							"?," +
							"entityFetch()," +
							"entityGroupFetch()," +
							"page(?, ?)" +
						")" +
					")" +
				")", "a", 2, 40);
		assertEquals(
			require(
				entityFetch(
					referenceContent(
						"a", entityFetch(), entityGroupFetch(),
						page(2, 40)
					)
				)
			),
			constraint48
		);
	}

	@Test
	void shouldParseReferenceContentConstraintWithExistingBehaviour() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a')");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a"), constraint1);

		final RequireConstraint constraint1a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', attributeContent('order'))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING, "a",
				attributeContent("order")
			),
			constraint1a
		);

		final RequireConstraint constraint1b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a')");
		assertEquals(referenceContentWithAttributes(ManagedReferencesBehaviour.EXISTING, "a"), constraint1b);

		final RequireConstraint constraint1c = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING)");
		assertEquals(referenceContentAllWithAttributes(ManagedReferencesBehaviour.EXISTING), constraint1c);

		final RequireConstraint constraint1d = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, attributeContent('a'))");
		assertEquals(referenceContentAllWithAttributes(ManagedReferencesBehaviour.EXISTING, attributeContent("a")), constraint1d);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a','b','c')");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("referenceContent (  EXISTING,   'a' , 'b'  , 'c' )");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b", "c"), constraint3);

		final RequireConstraint constraint6 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'BRAND', 'CATEGORY','stock')");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "BRAND", "CATEGORY", "stock"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll())
			),
			constraint7
		);

		final RequireConstraint constraint8 = parseRequireConstraint("referenceContent(?, ?)", ManagedReferencesBehaviour.EXISTING, "a");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("referenceContent(@mrb, @et)", Map.of("mrb", ManagedReferencesBehaviour.EXISTING, "et", "a"));
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("referenceContent(?, ?)", ManagedReferencesBehaviour.EXISTING, List.of("a", "b"));
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraint("referenceContent(@mrb, @et)", Map.of("mrb", ManagedReferencesBehaviour.EXISTING, "et", List.of("a", "b")));
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b"), constraint11);

		final RequireConstraint constraint12 = parseRequireConstraint("referenceContent(?,?,?)", ManagedReferencesBehaviour.EXISTING, "a", "b");
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b"), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("referenceContent(@mrb,@et1,@et2)", Map.of("mrb", ManagedReferencesBehaviour.EXISTING, "et1", "a", "et2", "b"));
		assertEquals(referenceContent(ManagedReferencesBehaviour.EXISTING, "a", "b"), constraint13);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"referenceContent(?, ?,filterBy(attributeEquals(?,?)),entityFetch(attributeContent(?)))",
			ManagedReferencesBehaviour.EXISTING,
			"a",
			"code",
			"some",
			"code"
		);
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING, "a",
				filterBy(attributeEquals("code", "some")),
				entityFetch(attributeContent("code"))
			),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraintUnsafe("referenceContentAll(EXISTING)");
		assertEquals(referenceContentAll(ManagedReferencesBehaviour.EXISTING), constraint15);

		final RequireConstraint constraint16 = parseRequireConstraintUnsafe("referenceContentAll ( EXISTING )");
		assertEquals(referenceContentAll(ManagedReferencesBehaviour.EXISTING), constraint16);

		final RequireConstraint constraint17 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				entityFetch(attributeContentAll())
			),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', 'b', entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				new String[] {"a", "b"},
				entityFetch(attributeContentAll())
			),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', 'b', entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				new String[] {"a", "b"},
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint19
		);

		final RequireConstraint constraint20 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', 'b', entityGroupFetch())");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				new String[] {"a", "b"},
				entityGroupFetch()
			),
			constraint20
		);

		final RequireConstraint constraint21 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21
		);

		final RequireConstraint constraint21a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), attributeContent('order'), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21a
		);

		final RequireConstraint constraint21b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21b
		);

		final RequireConstraint constraint21c = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21c
		);

		final RequireConstraint constraint21d = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, attributeContent('a'), entityFetch(attributeContentAll()), entityGroupFetch())");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				attributeContent("a"),
				entityFetch(attributeContentAll()),
				entityGroupFetch()
			),
			constraint21d
		);

		final RequireConstraint constraint22 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContentAll())
			),
			constraint22
		);

		final RequireConstraint constraint22a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), attributeContent('order'), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				attributeContent("order"),
				entityGroupFetch(attributeContentAll())
			),
			constraint22a
		);

		final RequireConstraint constraint22b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContentAll())
			),
			constraint22b
		);

		final RequireConstraint constraint22c = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch(attributeContentAll())
			),
			constraint22c
		);

		final RequireConstraint constraint22d = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, attributeContent('a'), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				attributeContent("a"),
				entityGroupFetch(attributeContentAll())
			),
			constraint22d
		);

		final RequireConstraint constraint23 = parseRequireConstraintUnsafe("referenceContentAll(EXISTING, entityFetch(priceContentRespectingFilter()), entityGroupFetch(attributeContentAll()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				entityFetch(priceContentRespectingFilter()),
				entityGroupFetch(attributeContentAll())
			),
			constraint23
		);

		final RequireConstraint constraint24 = parseRequireConstraintUnsafe("referenceContentAll(EXISTING, entityFetch(priceContentRespectingFilter()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				entityFetch(priceContentRespectingFilter())
			),
			constraint24
		);

		final RequireConstraint constraint25 = parseRequireConstraintUnsafe("referenceContentAll(EXISTING, entityGroupFetch(priceContentRespectingFilter()))");
		assertEquals(
			QueryConstraints.referenceContentAll(
				ManagedReferencesBehaviour.EXISTING,
				entityGroupFetch(priceContentRespectingFilter())
			),
			constraint25
		);

		final RequireConstraint constraint26 = parseRequireConstraint(
			"referenceContent(?,?,filterBy(attributeEquals(?,?)),entityFetch(attributeContent(?)),entityGroupFetch(attributeContent(?)))",
			ManagedReferencesBehaviour.EXISTING,
			"a",
			"code",
			"some",
			"code",
			"name"
		);
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", "some")),
				entityFetch(attributeContent("code")),
				entityGroupFetch(attributeContent("name"))
			),
			constraint26
		);

		final RequireConstraint constraint27 = parseRequireConstraint("referenceContent(?, ?, entityFetch(attributeContent(?)))", ManagedReferencesBehaviour.EXISTING, "a", "b");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				entityFetch(attributeContent("b"))
			),
			constraint27
		);

		final RequireConstraint constraint28 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint28
		);

		final RequireConstraint constraint28a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', orderBy(attributeNatural('code')), attributeContent('order'), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll())
			),
			constraint28a
		);

		final RequireConstraint constraint28b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint28b
		);

		final RequireConstraint constraint28c = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				entityFetch(attributeContentAll())
			),
			constraint28c
		);

		final RequireConstraint constraint28d = parseRequireConstraintUnsafe("referenceContentAllWithAttributes(EXISTING, attributeContent('a'), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentAllWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				attributeContent("a"),
				entityFetch(attributeContentAll())
			),
			constraint28d
		);

		final RequireConstraint constraint29 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint29
		);

		final RequireConstraint constraint29a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), attributeContent('order'), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				attributeContent("order"),
				entityFetch(attributeContentAll())
			),
			constraint29a
		);

		final RequireConstraint constraint29b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), entityFetch(attributeContentAll()))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				entityFetch(attributeContentAll())
			),
			constraint29b
		);

		final RequireConstraint constraint30 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code"))
			),
			constraint30
		);

		final RequireConstraint constraint30a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), attributeContent('order'))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code")),
				attributeContent("order")
			),
			constraint30a
		);

		final RequireConstraint constraint30b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				filterBy(attributeEquals("code", true)),
				orderBy(attributeNatural("code"))
			),
			constraint30b
		);

		final RequireConstraint constraint31 = parseRequireConstraintUnsafe("referenceContent(EXISTING, 'a', orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContent(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code"))
			),
			constraint31
		);

		final RequireConstraint constraint31a = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', orderBy(attributeNatural('code')), attributeContent('order'))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code")),
				attributeContent("order")
			),
			constraint31a
		);

		final RequireConstraint constraint31b = parseRequireConstraintUnsafe("referenceContentWithAttributes(EXISTING, 'a', orderBy(attributeNatural('code')))");
		assertEquals(
			referenceContentWithAttributes(
				ManagedReferencesBehaviour.EXISTING,
				"a",
				orderBy(attributeNatural("code"))
			),
			constraint31b
		);
	}

	@Test
	void shouldNotParseReferenceContentConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(attributeContentAll(),'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(attributeContentAll(),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(attributeNatural('a',ASC))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent('a', 'b', filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContentAll()))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent('a', attributeContentAll())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(entityFetch(),entityFetch())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("referenceContent(entityGroupFetch(),entityFetch)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("hierarchyContent"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("hierarchyContent(stopAt(distance(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyContent(attributeContent('code'))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyContent(entityFetch(attributeContent('code')), stopAt(distance(1)))"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceType(WITH_TAX)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceType(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceType(@mode)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceType"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceType()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceType('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceType(WITH_TAX,WITH_TAX)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceType(TAX)"));
	}

	@Test
	void shouldParseDataInLocalesAllConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("dataInLocalesAll()");
		assertEquals(dataInLocalesAll(), constraint1);
	}

	@Test
	void shouldNotParseDataInLocalesAllConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("dataInLocalesAll('cs')"));
	}

	@Test
	void shouldParseDataInLocalesConstraint() {
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("dataInLocales()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("dataInLocales('cs')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("dataInLocales(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("dataInLocales(@mode)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("dataInLocales"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("dataInLocales('cs',2)"));
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

		final RequireConstraint constraint6_5 = parseRequireConstraintUnsafe("facetSummary(entityFetch(  attributeContentAll() ))");
		assertEquals(facetSummary(COUNTS, entityFetch(attributeContent())), constraint6_5);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContentAll() ))");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent())), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContentAll() ), entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContentAll())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummary(IMPACT, entityGroupFetch(associatedDataContentAll())), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint10
		);

		final RequireConstraint constraint11 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint11
		);

		final RequireConstraint constraint12 = parseRequireConstraint(
			"facetSummary(?, orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			COUNTS, "e"
		);
		assertEquals(
			facetSummary(COUNTS, null, null, orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint12
		);

		final RequireConstraint constraint13 = parseRequireConstraint(
			"facetSummary(?, filterGroupBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			COUNTS, "c", "d", "e"
		);
		assertEquals(
			facetSummary(COUNTS, null, filterGroupBy(attributeEquals("c", "d")), null, orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint13
		);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()))",
			COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random())),
			constraint15
		);

		final RequireConstraint constraint16 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)))",
			COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d"))),
			constraint16
		);

		final RequireConstraint constraint17 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)))",
			COUNTS, "a", "b"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b"))),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), orderBy(random()))",
			COUNTS, "a", "b"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), orderBy(random())),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)))",
			COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), null, null, orderGroupBy(attributeNatural("e"))),
			constraint19
		);

		final RequireConstraint constraint20 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint20
		);

		final RequireConstraint constraint21 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint21
		);

		final RequireConstraint constraint22 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint22
		);

		final RequireConstraint constraint23 = parseRequireConstraint(
			"facetSummary(?, filterBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterBy(attributeEquals("a", "b")), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint23
		);

		final RequireConstraint constraint24 = parseRequireConstraint(
			"facetSummary(?, filterGroupBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummary(COUNTS, filterGroupBy(attributeEquals("a", "b")), orderBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint24
		);

		final RequireConstraint constraint25 = parseRequireConstraint(
			"facetSummary(entityFetch(attributeContentAll()))"
		);
		assertEquals(
			facetSummary(COUNTS, entityFetch(attributeContentAll())),
			constraint25
		);
	}

	@Test
	void shouldNotParseFacetSummaryConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummary(COUNTS)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummary(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummary(@mode)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary(NONE)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary(COUNTS,IMPACT)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary(COUNTS,attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummary(attributeContent())"));
	}

	@Test
	void shouldParseFacetSummaryOfReferenceConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter')");
		assertEquals(facetSummaryOfReference("parameter"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetSummaryOfReference (   'parameter'  )");
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

		final RequireConstraint constraint7_1 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', entityFetch(  attributeContentAll() ))");
		assertEquals(facetSummaryOfReference("parameter", COUNTS, entityFetch(attributeContent())), constraint7_1);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT, entityFetch(  attributeContentAll() ), entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContentAll())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT,   entityGroupFetch  ( associatedDataContentAll (  ) ) )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityGroupFetch(associatedDataContentAll())), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			"parameter", COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint10
		);

		final RequireConstraint constraint10_1 = parseRequireConstraint(
			"facetSummaryOfReference(?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			"parameter", "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint10_1
		);

		final RequireConstraint constraint11 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			"parameter", COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint11
		);

		final RequireConstraint constraint12 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			"parameter", COUNTS, "e"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, null, null, orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint12
		);

		final RequireConstraint constraint13 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterGroupBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()))",
			"parameter", COUNTS, "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, null, filterGroupBy(attributeEquals("c", "d")), null, orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())),
			constraint13
		);

		final RequireConstraint constraint14 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			"parameter", COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("parameter", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint14
		);

		final RequireConstraint constraint15 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()))",
			"x", COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random())),
			constraint15
		);

		final RequireConstraint constraint16 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)))",
			"x", COUNTS, "a", "b", "c", "d"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d"))),
			constraint16
		);

		final RequireConstraint constraint17 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)))",
			"x", COUNTS, "a", "b"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b"))),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), orderBy(random()))",
			"x", COUNTS, "a", "b"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), orderBy(random())),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)))",
			"x", COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), null, null, orderGroupBy(attributeNatural("e"))),
			constraint19
		);

		final RequireConstraint constraint20 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			"x", COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint20
		);

		final RequireConstraint constraint21 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)))",
			"x", COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e"))),
			constraint21
		);

		final RequireConstraint constraint22 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), filterGroupBy(attributeEquals(?, ?)), orderBy(random()), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			"x", COUNTS, "a", "b", "c", "d", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), orderBy(random()), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint22
		);

		final RequireConstraint constraint23 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterBy(attributeEquals(?, ?)), orderGroupBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			"x", COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterBy(attributeEquals("a", "b")), orderGroupBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint23
		);

		final RequireConstraint constraint24 = parseRequireConstraint(
			"facetSummaryOfReference(?, ?, filterGroupBy(attributeEquals(?, ?)), orderBy(attributeNatural(?)), entityFetch(attributeContentAll()))",
			"x", COUNTS, "a", "b", "e"
		);
		assertEquals(
			facetSummaryOfReference("x", COUNTS, filterGroupBy(attributeEquals("a", "b")), orderBy(attributeNatural("e")), entityFetch(attributeContentAll())),
			constraint24
		);
	}

	@Test
	void shouldNotParseFacetSummaryOfReferenceConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummaryOfReference(COUNTS)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummaryOfReference(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummaryOfReference(@mode)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetSummaryOfReference('parameter')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',NONE)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,IMPACT)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',COUNTS,attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetSummaryOfReference('parameter',attributeContent())"));
	}

	@Test
	void shouldParseFacetGroupsConjunctionConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsConjunction (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6)) )");
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsConjunction(?,filterBy(entityPrimaryKeyInSet(?)))", "a", 1);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsConjunction(?,filterBy(entityPrimaryKeyInSet(?)))", "a", List.of(1, 2));
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsConjunction(?,filterBy(entityPrimaryKeyInSet(?,?)))", "a", 1, 2);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsConjunction(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsConjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("facetGroupsConjunction('a')");
		assertEquals(facetGroupsConjunction("a"), constraint10);
	}

	@Test
	void shouldNotParseFacetGroupsConjunctionConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(?)))", 1));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsConjunction('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction('a','b','c')"));
	}

	@Test
	void shouldParseFacetGroupsDisjunctionConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsDisjunction (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6) ))");
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsDisjunction(?,filterBy(entityPrimaryKeyInSet(?)))", "a", 1);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsDisjunction(?,filterBy(entityPrimaryKeyInSet(?)))", "a", List.of(1, 2));
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsDisjunction(?,filterBy(entityPrimaryKeyInSet(?,?)))", "a", 1, 2);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsDisjunction("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("facetGroupsDisjunction(?)", "a");
		assertEquals(facetGroupsDisjunction("a"), constraint10);
	}

	@Test
	void shouldNotParseFacetGroupsDisjunctionConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction('a','b','c')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',filterBy(entityPrimaryKeyInSet(?)))", 1));
	}

	@Test
	void shouldParseFacetGroupsNegationConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1)))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1,5,6)))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsNegation (  'a' , filterBy(entityPrimaryKeyInSet( 1 , 5, 6) ))");
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 5, 6))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsNegation(?,filterBy(entityPrimaryKeyInSet(?)))", "a", 1);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1))), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsNegation(?,filterBy(entityPrimaryKeyInSet(?)))", "a", List.of(1, 2));
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk)))",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsNegation(?,filterBy(entityPrimaryKeyInSet(?,?)))", "a", 1, 2);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsNegation(@name,filterBy(entityPrimaryKeyInSet(@pk1,@pk2)))",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsNegation("a", filterBy(entityPrimaryKeyInSet(1, 2))), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("facetGroupsNegation('a')");
		assertEquals(facetGroupsNegation("a"), constraint10);
	}

	@Test
	void shouldNotParseFacetGroupsNegationConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(@pk)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation('a','b','c')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("facetGroupsNegation('a',filterBy(entityPrimaryKeyInSet(?)))", 1));
	}

	@Test
	void shouldParseAttributeHistogramConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("attributeHistogram(20, 'a')");
		assertEquals(attributeHistogram(20, "a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("attributeHistogram(20, 'a','b','c')");
		assertEquals(attributeHistogram(20, "a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("attributeHistogram ( 20  , 'a' ,  'b' ,'c' )");
		assertEquals(attributeHistogram(20, "a", "b", "c"), constraint3);

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

		final RequireConstraint constraint12 = parseRequireConstraintUnsafe("attributeHistogram (20, OPTIMIZED, 'a' ,  'b' ,'c' )");
		assertEquals(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b", "c"), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("attributeHistogram (?, ?, ?, ?, ?)", 20, HistogramBehavior.OPTIMIZED, "a", "b", "c");
		assertEquals(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b", "c"), constraint13);

		final RequireConstraint constraint15 = parseRequireConstraint(
			"attributeHistogram (@count, @beh, @attr1, @attr2, @attr3)",
			Map.of("count", 20, "beh", HistogramBehavior.OPTIMIZED, "attr1", "a", "attr2", "b", "attr3", "c")
		);
		assertEquals(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b", "c"), constraint15);

		final RequireConstraint constraint14 = parseRequireConstraint("attributeHistogram (?, ?, ?)", 20, HistogramBehavior.OPTIMIZED, List.of("a", "b", "c"));
		assertEquals(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b", "c"), constraint14);

		final RequireConstraint constraint16 = parseRequireConstraint(
			"attributeHistogram (@count, @beh, @attrs)",
			Map.of("count", 20, "beh", HistogramBehavior.OPTIMIZED, "attrs", List.of("a", "b", "c"))
		);
		assertEquals(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b", "c"), constraint16);
	}

	@Test
	void shouldNotParseAttributeHistogramConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeHistogram(20,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeHistogram(?,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeHistogram(@buckets,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeHistogram(20,WHATEVER,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("attributeHistogram(20,OPTIMIZED,WHATEVER,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram(1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram('a',1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram('a','b')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram(20,OPTIMIZED,WHATEVER,'a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("attributeHistogram(20,'a',OPTIMIZED)"));
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

		final RequireConstraint constraint5 = parseRequireConstraintUnsafe("priceHistogram(20, OPTIMIZED)");
		assertEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("priceHistogram(?, ?)", 20, HistogramBehavior.OPTIMIZED);
		assertEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"priceHistogram(@count, @beh)",
			Map.of("count", 20, "beh", HistogramBehavior.OPTIMIZED)
		);
		assertEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED), constraint7);
	}

	@Test
	void shouldNotParsePriceHistogramConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceHistogram(20)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceHistogram(?)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceHistogram(@buckets)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceHistogram(10,WHATEVER)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("priceHistogram(10,STANDARD,WHATEVER)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceHistogram"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceHistogram('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("priceHistogram(10,STANDARD,WHATEVER)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("distance"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("distance('str')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("distance(1)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("level"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("level('str')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("level(1)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("node"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("node(1)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("node(entityPrimaryKeyInSet(1))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("node(filterBy(entityPrimaryKeyInSet(1)))"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("stopAt"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("stopAt(stopAt(distance(1)))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("stopAt(level(1),distance(1))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("stopAt(level(1))"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("statistics"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("statistics('CHILDREN_COUNT')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("statistics(COMPLETE_FILTER,COMPLETE_FILTER)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("statistics(CHILDREN_COUNT)"));
	}

	@Test
	void shouldParseHierarchyFromRootConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("fromRoot(?)", "megaMenu");
		assertEquals(fromRoot("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("fromRoot('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(fromRoot("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("fromRoot('megaMenu', entityFetch(attributeContent('code')))");
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromRoot"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromRoot(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromRoot(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromRoot('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("fromRoot('megaMenu')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("fromRoot('megaMenu', statistics(COMPLETE_FILTER))"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode(node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode(node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', entityFetch(attributeContent('code')), node(filterBy(entityPrimaryKeyInSet(1))), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("fromNode('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("fromNode('megaMenu', node(filterBy(entityPrimaryKeyInSet(1))), entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyChildrenConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("children('megaMenu')");
		assertEquals(children("megaMenu"), constraint1);

		final RequireConstraint constraint1a = parseRequireConstraint("children(?)", "megaMenu");
		assertEquals(children("megaMenu"), constraint1a);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("children('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(children("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("children('megaMenu', entityFetch(attributeContent('code')))");
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("children"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("children(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("children(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("children('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("children('megaMenu', statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("children('megaMenu')"));
	}

	@Test
	void shouldParseHierarchySiblingsConstraint() {
		final RequireConstraint constraint9 = parseRequireConstraint("siblings()");
		assertEquals(siblings(), constraint9);

		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("siblings('megaMenu')");
		assertEquals(siblings("megaMenu"), constraint1);

		final RequireConstraint constraint1a = parseRequireConstraint("siblings(?)", "megaMenu");
		assertEquals(siblings("megaMenu"), constraint1a);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("siblings('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(siblings("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("siblings(statistics(COMPLETE_FILTER))");
		assertEquals(siblings(statistics(StatisticsBase.COMPLETE_FILTER)), constraint10);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("siblings('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(siblings("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint11 = parseRequireConstraintUnsafe("siblings(entityFetch(attributeContent('code')))");
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("siblings"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("siblings('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("siblings('megaMenu', statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("siblings('megaMenu')"));
	}

	@Test
	void shouldParseHierarchyParentsConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("parents(?)", "megaMenu");
		assertEquals(parents("megaMenu"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("parents('megaMenu', statistics(COMPLETE_FILTER))");
		assertEquals(parents("megaMenu", statistics(StatisticsBase.COMPLETE_FILTER)), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("parents('megaMenu', entityFetch(attributeContent('code')))");
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code"))), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe("parents('megaMenu', entityFetch(attributeContent('code')), statistics(COMPLETE_FILTER))");
		assertEquals(parents("megaMenu", entityFetch(attributeContent("code")), statistics(StatisticsBase.COMPLETE_FILTER)), constraint4);

		final RequireConstraint constraint9 = parseRequireConstraint("parents(?, siblings())", "megaMenu");
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

		final RequireConstraint constraint10 = parseRequireConstraintUnsafe("parents('megaMenu', entityFetch(attributeContent('code')), siblings())");
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("parents"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("parents(statistics(COMPLETE_FILTER))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("parents(entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("parents('megaMenu', statistics(COMPLETE_FILTER), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("parents('megaMenu', siblings(), entityFetch(attributeContent('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("parents('megaMenu')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("parents('megaMenu', statistics(COMPLETE_FILTER))"));
	}

	@Test
	void shouldParseHierarchyOfSelfConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("hierarchyOfSelf(fromRoot('megaMenu'))");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu")), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("hierarchyOfSelf(fromRoot('megaMenu'), parents('parents', siblings()))");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu"), parents("parents", siblings())), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe(
			"hierarchyOfSelf(orderBy(attributeNatural('code')), fromRoot('megaMenu'))"
		);
		assertEquals(
			hierarchyOfSelf(orderBy(attributeNatural("code")), fromRoot("megaMenu")),
			constraint3
		);

		final RequireConstraint constraint4 = parseRequireConstraintUnsafe(
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf()"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(orderBy(random()))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(fromRoot('megaMenu'), orderBy(attributeNatural('code')))"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("hierarchyOfSelf(fromRoot('megaMenu'))"));
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

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
//		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("hierarchyOfReference('a', 'b', LEAVE_EMPTY, fromRoot('megaMenu'))");
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint2
//		);

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
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

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
//		final RequireConstraint constraint5 = parseRequireConstraint(
//			"hierarchyOfReference(?, ?, fromRoot(?))",
//			new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "megaMenu"
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint5
//		);

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
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

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
//		final RequireConstraint constraint8 = parseRequireConstraint(
//			"hierarchyOfReference(@refs, @beh, fromRoot(@out))",
//			Map.of("refs", new String[] {"a", "b"}, "beh", EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, "out", "megaMenu")
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY, fromRoot("megaMenu")),
//			constraint8
//		);

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
//		final RequireConstraint constraint13 = parseRequireConstraint(
//			"hierarchyOfReference(@refs, orderBy(random()), fromRoot(@out))",
//			Map.of("refs", new String[] {"a", "b"}, "out", "megaMenu")
//		);
//		assertEquals(
//			hierarchyOfReference(new String[] {"a", "b"}, orderBy(random()), fromRoot("megaMenu")),
//			constraint13
//		);

		// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("hierarchyOfReference"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference(attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a',attributeContent())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a')"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a', LEAVE_EMPTY)"));
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
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("queryTelemetry"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("queryTelemetry('a','b')"));
	}

	@Test
	void shouldParseInScopeConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("inScope(LIVE, facetSummary())");
		assertEquals(inScope(Scope.LIVE, facetSummary()), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("inScope ( LIVE , facetSummary())");
		assertEquals(inScope(Scope.LIVE, facetSummary()), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("inScope ( LIVE , facetSummary(), attributeHistogram(10, 'weight'))");
		assertEquals(inScope(Scope.LIVE, facetSummary(), attributeHistogram(10, "weight")), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("inScope ( ?,    facetSummary(  ) )", Scope.ARCHIVED);
		assertEquals(inScope(Scope.ARCHIVED, facetSummary()), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("inScope ( ?,    facetSummary(), attributeHistogram(?, ?) )", Scope.ARCHIVED, 10, "weight");
		assertEquals(inScope(Scope.ARCHIVED, facetSummary(), attributeHistogram(10, "weight")), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("inScope ( @a,  facetSummary() )", Map.of("a", Scope.ARCHIVED));
		assertEquals(inScope(Scope.ARCHIVED, facetSummary()), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("inScope ( @a,   facetSummary( ), attributeHistogram(@b,   @c) )", Map.of("a", Scope.ARCHIVED, "b", 10, "c", "weight"));
		assertEquals(inScope(Scope.ARCHIVED, facetSummary(), attributeHistogram(10, "weight")), constraint7);
	}

	@Test
	void shouldNotParseInScopeConstraint() {
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("inScope"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraint("inScope(LIVE)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("inScope(LIVE)"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("inScope('LIVE', facetSummary())"));
		assertThrows(EvitaSyntaxException.class, () -> parseRequireConstraintUnsafe("inScope('a','b')"));
	}

	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
	 *
	 * @param string string to parse
	 * @param positionalArguments positional arguments to substitute
	 * @return parsed constraint
	 */
	private static RequireConstraint parseRequireConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
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
	private static RequireConstraint parseRequireConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
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
	private static RequireConstraint parseRequireConstraintUnsafe(@Nonnull String string) {
		final ParseContext context = new ParseContext();
		context.setMode(ParseMode.UNSAFE);
		return ParserExecutor.execute(
			context,
			() -> ParserFactory.getParser(string).requireConstraint().accept(new EvitaQLRequireConstraintVisitor())
		);
	}
}
