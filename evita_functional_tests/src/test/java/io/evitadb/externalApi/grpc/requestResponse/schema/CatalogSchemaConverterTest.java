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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies functionalities of methods in {@link CatalogSchemaConverter} class.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CatalogSchemaConverterTest {

	@Test
	void shouldConvertSimpleCatalogSchema() {
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			EnumSet.noneOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		assertCatalogSchema(
			catalogSchema,
			CatalogSchemaConverter.convert(
				CatalogSchemaConverter.convert(catalogSchema, true),
				EmptyEntitySchemaAccessor.INSTANCE
			)
		);
	}

	@Test
	void shouldConvertComplexCatalogSchema() {
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			1,
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			"description",
			EnumSet.allOf(CatalogEvolutionMode.class),
			Map.of(
				"code", GlobalAttributeSchema._internalBuild(
					"code",
					"description",
					"depr",
					new ScopedAttributeUniquenessType[] {
						new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
					},
					new ScopedGlobalAttributeUniquenessType[]{
						new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
					},
					new Scope[] { Scope.LIVE },
					new Scope[] { Scope.LIVE },
					true,
					true,
					false,
					String.class,
					null,
					0
				),
				"priority", GlobalAttributeSchema._internalBuild(
					"code",
					Long[].class,
					false
				)
			),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		assertCatalogSchema(
			catalogSchema,
			CatalogSchemaConverter.convert(
				CatalogSchemaConverter.convert(catalogSchema, true),
				EmptyEntitySchemaAccessor.INSTANCE
			)
		);
	}

	private static void assertCatalogSchema(@Nonnull CatalogSchemaContract expected, @Nonnull CatalogSchemaContract actual) {
		assertEquals(expected.version(), actual.version());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getAttributes().size(), actual.getAttributes().size());
		expected.getAttributes().forEach((attributeName, attribute) ->
			assertGlobalAttributeSchema(attribute, actual.getAttribute(attributeName).orElseThrow()));
	}

	private static void assertGlobalAttributeSchema(@Nonnull GlobalAttributeSchemaContract expected, @Nonnull GlobalAttributeSchemaContract actual) {
		assertEquals(expected.isUniqueGlobally(), actual.isUniqueGlobally());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.isLocalized(), actual.isLocalized());
		assertEquals(expected.isUnique(), actual.isUnique());
		assertEquals(expected.isFilterable(), actual.isFilterable());
		assertEquals(expected.isSortable(), actual.isSortable());
		assertEquals(expected.isNullable(), actual.isNullable());
		assertEquals(expected.getType(), actual.getType());
		assertEquals(expected.getPlainType(), actual.getPlainType());
		assertEquals(expected.getDefaultValue(), actual.getDefaultValue());
		assertEquals(expected.getIndexedDecimalPlaces(), actual.getIndexedDecimalPlaces());
	}
}
