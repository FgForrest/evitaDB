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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterByConstraintFromRequestQueryBuilder;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class FilterByQueryConstraintsBuilderTest {
	private static EntitySchemaContract entitySchema;

	@BeforeAll
	static void init() {
		entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute("code", String.class, AttributeSchemaEditor::unique)
			.withAttribute("non-unique", String.class)
			.toInstance();
	}

	@Test
	void shouldBuildFilterByPrimaryKey() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap(GetEntityEndpointHeaderDescriptor.PRICE_VALID_NOW.name(), Boolean.TRUE), entitySchema);
		assertEquals("filterBy(priceValidInNow())", filterBy.toString());
	}

	@Test
	void shouldBuildFilterByPriceCurrency() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), Currency.getInstance("CZK")), entitySchema);
		assertEquals("filterBy(priceInCurrency('CZK'))", filterBy.toString());
	}

	@Test
	void shouldBuildFilterByPriceList() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), new String[]{"cheapest"}), entitySchema);
		assertEquals("filterBy(priceInPriceLists('cheapest'))", filterBy.toString());
	}

	@Test
	void shouldBuildFilterByPriceValidIn() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap(GetEntityEndpointHeaderDescriptor.PRICE_VALID_IN.name(), OffsetDateTime.of(2022, 10, 21, 10, 9, 1, 0, ZoneOffset.ofHours(2))), entitySchema);
		assertEquals("filterBy(priceValidIn(2022-10-21T10:09:01+02:00))", filterBy.toString());
	}

	@Test
	void shouldBuildFilterByCode() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap("code", "someCode"), entitySchema);
		assertEquals("filterBy(attributeEquals('code','someCode'))", filterBy.toString());
	}

	@Test
	void shouldBuildFilterToSearchInCustomScope() {
		final FilterBy filterBy = FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap(ScopeAwareEndpointHeaderDescriptor.SCOPE.name(), new Scope[] { Scope.ARCHIVED }), entitySchema);
		assertEquals("filterBy(scope(ARCHIVED))", filterBy.toString());
	}

	@Test
	void shouldNotBuildFilterByNonUniqueCode() {
		assertNull(FilterByConstraintFromRequestQueryBuilder.buildFilterByForSingleEntity(Collections.singletonMap("non-unique", "someCode"), entitySchema));
	}
}
