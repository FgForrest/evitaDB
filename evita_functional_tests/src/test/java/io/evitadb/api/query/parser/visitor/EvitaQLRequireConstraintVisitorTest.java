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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
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

		final RequireConstraint constraint2 = parseRequireConstraint("require(entityFetch(attributeContent()))");
		assertEquals(require(entityFetch(attributeContent())), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("require (  entityFetch(  attributeContent() )  )");
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

		final RequireConstraint constraint3 = parseRequireConstraint("entityFetch(attributeContent(),associatedDataContent())");
		assertEquals(entityFetch(attributeContent(), associatedDataContent()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityFetch(  attributeContent  (   ) ,   associatedDataContent   ( ) )");
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

		final RequireConstraint constraint3 = parseRequireConstraint("entityGroupFetch(attributeContent(),associatedDataContent())");
		assertEquals(entityGroupFetch(attributeContent(), associatedDataContent()), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("entityGroupFetch(  attributeContent  (   ) ,   associatedDataContent   ( ) )");
		assertEquals(entityGroupFetch(attributeContent(), associatedDataContent()), constraint4);
	}

	@Test
	void shouldNotParseEntityGroupFetchConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityGroupFetch"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("entityGroupFetch('a')"));
	}

	@Test
	void shouldParseAttributeContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("attributeContent()");
		assertEquals(attributeContent(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("attributeContent (  )");
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
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("attributeContent('a',1)"));
	}

	@Test
	void shouldParsePriceContentConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("priceContent()");
		assertEquals(priceContent(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("priceContent(ALL)");
		assertEquals(priceContentAll(), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("priceContent (  ALL )");
		assertEquals(priceContentAll(), constraint3);

		final RequireConstraint constraint5 = parseRequireConstraint("priceContent(?)", PriceContentMode.ALL);
		assertEquals(priceContentAll(), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("priceContent(@mode)", Map.of("mode", PriceContentMode.ALL));
		assertEquals(priceContentAll(), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("priceContent('vip')");
		assertEquals(priceContent("vip"), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("priceContent (  'vip' )");
		assertEquals(priceContent("vip"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("priceContent(?)", "vip");
		assertEquals(priceContent("vip"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("priceContent(@pl)", Map.of("pl", "vip"));
		assertEquals(priceContent("vip"), constraint10);

		final RequireConstraint constraint11 = parseRequireConstraintUnsafe("priceContent(NONE, 'vip')");
		assertEquals(new PriceContent(PriceContentMode.NONE, "vip"), constraint11);

		final RequireConstraint constraint12 = parseRequireConstraint("priceContent(?)", List.of(PriceContentMode.NONE, "vip"));
		assertEquals(new PriceContent(PriceContentMode.NONE, "vip"), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("priceContent(?, ?)", PriceContentMode.NONE, "vip");
		assertEquals(new PriceContent(PriceContentMode.NONE, "vip"), constraint13);
	}

	@Test
	void shouldNotParsePriceContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(ALL)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(@mode)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("priceContent(ALL, 'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("priceContent"));
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

		final RequireConstraint constraint12 = parseRequireConstraint("associatedDataContent()");
		assertEquals(associatedDataContent(), constraint12);

		final RequireConstraint constraint13 = parseRequireConstraint("associatedDataContent (  )");
		assertEquals(associatedDataContent(), constraint13);
	}

	@Test
	void shouldNotParseAssociatedDataContentConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent(1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("associatedDataContent('a',1)"));
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

		final RequireConstraint constraint7 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContent()))");
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

		final RequireConstraint constraint15 = parseRequireConstraint("referenceContent()");
		assertEquals(referenceContent(), constraint15);

		final RequireConstraint constraint16 = parseRequireConstraint("referenceContent (  )");
		assertEquals(referenceContent(), constraint16);

		final RequireConstraint constraint17 = parseRequireConstraint("referenceContent('a', entityFetch(attributeContent()))");
		assertEquals(
			referenceContent(
				"a",
				entityFetch(attributeContent())
			),
			constraint17
		);

		final RequireConstraint constraint18 = parseRequireConstraint("referenceContent('a', 'b', entityFetch(attributeContent()))");
		assertEquals(
			referenceContent(
				new String[] {"a", "b"},
				entityFetch(attributeContent())
			),
			constraint18
		);

		final RequireConstraint constraint19 = parseRequireConstraint("referenceContent('a', 'b', entityFetch(attributeContent()), entityGroupFetch())");
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

		final RequireConstraint constraint21 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityFetch(attributeContent()), entityGroupFetch())");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityFetch(attributeContent()),
				entityGroupFetch()
			),
			constraint21
		);

		final RequireConstraint constraint22 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), entityGroupFetch(attributeContent()))");
		assertEquals(
			referenceContent(
				"a",
				filterBy(attributeEqualsTrue("code")),
				entityGroupFetch(attributeContent())
			),
			constraint22
		);

		final RequireConstraint constraint23 = parseRequireConstraint("referenceContent(entityFetch(priceContent()), entityGroupFetch(attributeContent()))");
		assertEquals(
			referenceContent(
				entityFetch(priceContent()),
				entityGroupFetch(attributeContent())
			),
			constraint23
		);

		final RequireConstraint constraint24 = parseRequireConstraint("referenceContent(entityFetch(priceContent()))");
		assertEquals(
			referenceContent(
				entityFetch(priceContent())
			),
			constraint24
		);

		final RequireConstraint constraint25 = parseRequireConstraint("referenceContent(entityGroupFetch(priceContent()))");
		assertEquals(
			referenceContent(
				entityGroupFetch(priceContent())
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

		final RequireConstraint constraint28 = parseRequireConstraint("referenceContent('a', orderBy(attributeNatural('code')), entityFetch(attributeContent()))");
		assertEquals(
			referenceContent(
				"a",
				orderBy(attributeNatural("code")),
				entityFetch(attributeContent())
			),
			constraint28
		);

		final RequireConstraint constraint29 = parseRequireConstraint("referenceContent('a', filterBy(attributeEqualsTrue('code')), orderBy(attributeNatural('code')), entityFetch(attributeContent()))");
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
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeContent(),'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a',filterBy(entityPrimaryKeyInSet(1)),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeContent(),filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(attributeNatural('a',ASC))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a', 'b', filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent()))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)), entityFetch(attributeContent()))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(filterBy(entityPrimaryKeyInSet(1)))"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent('a', attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(entityFetch(),entityFetch())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("referenceContent(entityGroupFetch(),entityFetch)"));
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
	void shouldParseHierarchyParentsOfSelfConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("hierarchyParentsOfSelf()");
		assertEquals(hierarchyParentsOfSelf(), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("hierarchyParentsOfSelf(entityFetch())");
		assertEquals(hierarchyParentsOfSelf(entityFetch()), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("hierarchyParentsOfSelf(entityFetch(attributeContent()))");
		assertEquals(hierarchyParentsOfSelf(entityFetch(attributeContent())), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("hierarchyParentsOfSelf (  entityFetch(  attributeContent()  )  )");
		assertEquals(hierarchyParentsOfSelf(entityFetch(attributeContent())), constraint4);
	}

	@Test
	void shouldNotParseHierarchyParentsOfSelfConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfSelf"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfSelf('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfSelf('a',SOME_ENUM_A)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfSelf(attributeContent())"));
	}

	@Test
	void shouldParseHierarchyParentsOfReferenceConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("hierarchyParentsOfReference('a')");
		assertEquals(hierarchyParentsOfReference("a"), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraint("hierarchyParentsOfReference('a','b','c')");
		assertEquals(hierarchyParentsOfReference("a", "b", "c"), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraint("hierarchyParentsOfReference('a','b',entityFetch(attributeContent()))");
		assertEquals(
				hierarchyParentsOfReference(
					new String[] {
						"a",
						"b"
					},
					entityFetch(attributeContent())
				),
				constraint3
		);

		final RequireConstraint constraint4 = parseRequireConstraint("hierarchyParentsOfReference (  'a' ,  'b' ,  entityFetch( attributeContent()   ) )");
		assertEquals(hierarchyParentsOfReference(new String[] {"a", "b"}, entityFetch(attributeContent())), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint("hierarchyParentsOfReference(?)", "a");
		assertEquals(hierarchyParentsOfReference("a"), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("hierarchyParentsOfReference(@et)", Map.of("et", "a"));
		assertEquals(hierarchyParentsOfReference("a"), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("hierarchyParentsOfReference(?)", List.of("a", "b"));
		assertEquals(hierarchyParentsOfReference("a", "b"), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("hierarchyParentsOfReference(@et)", Map.of("et", List.of("a", "b")));
		assertEquals(hierarchyParentsOfReference("a", "b"), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("hierarchyParentsOfReference(?,?)", "a", "b");
		assertEquals(hierarchyParentsOfReference("a", "b"), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint("hierarchyParentsOfReference(@et1,@et2)", Map.of("et1", "a", "et2", "b"));
		assertEquals(hierarchyParentsOfReference("a", "b"), constraint10);
	}

	@Test
	void shouldNotParseHierarchyParentsOfReferenceConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference(entityFetch())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference(entityFetch(),'a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference('a',entityFetch(),'b')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyParentsOfReference('a',attributeContent())"));
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

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContent() ))");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent())), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityFetch(  attributeContent() ), entityGroupFetch  ( associatedDataContent (  ) ) )");
		assertEquals(facetSummary(IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContent())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummary(IMPACT, entityGroupFetch  ( associatedDataContent (  ) ) )");
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

		final RequireConstraint constraint7 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT, entityFetch(  attributeContent() ))");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityFetch(attributeContent())), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT, entityFetch(  attributeContent() ), entityGroupFetch  ( associatedDataContent (  ) ) )");
		assertEquals(facetSummaryOfReference("parameter", IMPACT, entityFetch(attributeContent()), entityGroupFetch(associatedDataContent())), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraintUnsafe("facetSummaryOfReference('parameter', IMPACT,   entityGroupFetch  ( associatedDataContent (  ) ) )");
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
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',1)");
		assertEquals(facetGroupsConjunction("a", 1), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsConjunction('a',1,5,6)");
		assertEquals(facetGroupsConjunction("a", 1, 5, 6), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsConjunction (  'a' ,  1 , 5, 6)");
		assertEquals(facetGroupsConjunction("a", 1, 5, 6), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsConjunction('a',?)", 1);
		assertEquals(facetGroupsConjunction("a", 1), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsConjunction(@name,@pk)",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsConjunction("a", 1), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsConjunction('a',?)", List.of(1, 2));
		assertEquals(facetGroupsConjunction("a", 1, 2), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsConjunction(@name,@pk)",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsConjunction("a", 1, 2), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsConjunction('a',?,?)", 1, 2);
		assertEquals(facetGroupsConjunction("a", 1, 2), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsConjunction(@name,@pk1,@pk2)",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsConjunction("a", 1, 2), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsConjunctionConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsConjunction('a',@pk)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("facetGroupsConjunction('a','b','c')"));
	}

	@Test
	void shouldParseFacetGroupsDisjunctionConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',1)");
		assertEquals(facetGroupsDisjunction("a", 1), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsDisjunction('a',1,5,6)");
		assertEquals(facetGroupsDisjunction("a", 1, 5, 6), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsDisjunction (  'a' ,  1 , 5, 6)");
		assertEquals(facetGroupsDisjunction("a", 1, 5, 6), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsDisjunction('a',?)", 1);
		assertEquals(facetGroupsDisjunction("a", 1), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,@pk)",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsDisjunction("a", 1), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsDisjunction('a',?)", List.of(1, 2));
		assertEquals(facetGroupsDisjunction("a", 1, 2), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,@pk)",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsDisjunction("a", 1, 2), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsDisjunction('a',?,?)", 1, 2);
		assertEquals(facetGroupsDisjunction("a", 1, 2), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsDisjunction(@name,@pk1,@pk2)",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsDisjunction("a", 1, 2), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsDisjunctionConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a',@pk)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction()"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsDisjunction('a','b','c')"));
	}

	@Test
	void shouldParseFacetGroupsNegationConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraintUnsafe("facetGroupsNegation('a',1)");
		assertEquals(facetGroupsNegation("a", 1), constraint1);

		final RequireConstraint constraint2 = parseRequireConstraintUnsafe("facetGroupsNegation('a',1,5,6)");
		assertEquals(facetGroupsNegation("a", 1, 5, 6), constraint2);

		final RequireConstraint constraint3 = parseRequireConstraintUnsafe("facetGroupsNegation (  'a' ,  1 , 5, 6)");
		assertEquals(facetGroupsNegation("a", 1, 5, 6), constraint3);

		final RequireConstraint constraint4 = parseRequireConstraint("facetGroupsNegation('a',?)", 1);
		assertEquals(facetGroupsNegation("a", 1), constraint4);

		final RequireConstraint constraint5 = parseRequireConstraint(
			"facetGroupsNegation(@name,@pk)",
			Map.of("name", "a", "pk", 1)
		);
		assertEquals(facetGroupsNegation("a", 1), constraint5);

		final RequireConstraint constraint6 = parseRequireConstraint("facetGroupsNegation('a',?)", List.of(1, 2));
		assertEquals(facetGroupsNegation("a", 1, 2), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint(
			"facetGroupsNegation(@name,@pk)",
			Map.of("name", "a", "pk", List.of(1, 2))
		);
		assertEquals(facetGroupsNegation("a", 1, 2), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("facetGroupsNegation('a',?,?)", 1, 2);
		assertEquals(facetGroupsNegation("a", 1, 2), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint(
			"facetGroupsNegation(@name,@pk1,@pk2)",
			Map.of("name", "a", "pk1", 1, "pk2", 2)
		);
		assertEquals(facetGroupsNegation("a", 1, 2), constraint9);
	}

	@Test
	void shouldNotParseFacetGroupsNegationConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',1)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',?)"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("facetGroupsNegation('a',@pk)"));
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
	void shouldParseHierarchyOfSelfConstraint() {
		final RequireConstraint constraint6 = parseRequireConstraint("hierarchyOfSelf(entityFetch(priceContent()))");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu", entityFetch(priceContent()))), constraint6);

		final RequireConstraint constraint7 = parseRequireConstraint("hierarchyOfSelf (   entityFetch(   priceContent()  ) )");
		assertEquals(hierarchyOfSelf(fromRoot("megaMenu", entityFetch(priceContent()))), constraint7);

		final RequireConstraint constraint8 = parseRequireConstraint("hierarchyOfSelf()");
		assertEquals(hierarchyOfSelf(), constraint8);

		final RequireConstraint constraint9 = parseRequireConstraint("hierarchyOfSelf  (  )");
		assertEquals(hierarchyOfSelf(), constraint9);

		final RequireConstraint constraint10 = parseRequireConstraint(
			"hierarchyOfSelf(entityFetch(attributeContent(?)))",
			"code"
		);
		assertEquals(
			hierarchyOfSelf(fromRoot("megaMenu", entityFetch(attributeContent("code")))),
			constraint10
		);
	}

	@Test
	void shouldNotParseHierarchyOfSelfConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyOfSelf"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a','b')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a')"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a', attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf(attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfSelf('a',priceType(WITH_TAX))"));
	}

	@Test
	void shouldParseHierarchyOfReferenceConstraint() {
		final RequireConstraint constraint1 = parseRequireConstraint("hierarchyOfReference('a')");
		assertEquals(
			hierarchyOfReference("a"),
			constraint1
		);

		final RequireConstraint constraint2 = parseRequireConstraint("hierarchyOfReference( 'a' )");
		assertEquals(
			hierarchyOfReference("a"),
			constraint2
		);

		final RequireConstraint constraint3 = parseRequireConstraint("hierarchyOfReference('a',entityFetch(attributeContent()))");
		assertEquals(
			hierarchyOfReference(
				"a",
				fromRoot("megaMenu", entityFetch(attributeContent()))
			),
			constraint3
		);

		final RequireConstraint constraint4 = parseRequireConstraint("hierarchyOfReference('a',entityFetch(priceContent()))");
		assertEquals(
			hierarchyOfReference(
				"a",
				fromRoot("megaMenu", entityFetch(priceContent()))
			),
			constraint4
		);

		final RequireConstraint constraint5 = parseRequireConstraint("hierarchyOfReference (  'a'  , entityFetch(   priceContent()  ) )");
		assertEquals(
			hierarchyOfReference(
				"a",
				fromRoot("megaMenu", entityFetch(priceContent()))
			),
			constraint5
		);

		final RequireConstraint constraint6 = parseRequireConstraint("hierarchyOfReference('a','b')");
		assertEquals(
			hierarchyOfReference(new String[] {"a", "b"}),
			constraint6
		);

		final RequireConstraint constraint7 = parseRequireConstraint("hierarchyOfReference('a','b',entityFetch(priceContent()))");
		assertEquals(
			hierarchyOfReference(
				new String[] {"a", "b"},
				fromRoot("megaMenu", entityFetch(priceContent()))
			),
			constraint7
		);


		final RequireConstraint constraint10 = parseRequireConstraint(
			"hierarchyOfReference(?,entityFetch(attributeContent(?)))",
			"category",
			"code"
		);
		assertEquals(
			hierarchyOfReference(
				"category",
				fromRoot("megaMenu", entityFetch(attributeContent("code")))
			),
			constraint10
		);
	}

	@Test
	void shouldNotParseHierarchyOfReferenceConstraint() {
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraint("hierarchyOfReference"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference(attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a',attributeContent())"));
		assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintUnsafe("hierarchyOfReference('a',priceType(WITH_TAX))"));
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
