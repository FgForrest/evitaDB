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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies functionalities of methods in {@link EntitySchemaConverter} class.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class EntitySchemaConverterTest {

	private static final CatalogSchemaContract CATALOG_SCHEMA = CatalogSchema._internalBuild(
		TestConstants.TEST_CATALOG,
		NamingConvention.generate(TestConstants.TEST_CATALOG),
		s -> null
	);

	@Test
	void shouldConvertSimpleEntitySchema() {
		final EntitySchema entitySchema = EntitySchema._internalBuild(
			"product"
		);
		assertEntitySchema(
			entitySchema,
			EntitySchemaConverter.convert(EntitySchemaConverter.convert(entitySchema))
		);
	}

	@Test
	void shouldConvertComplexEntitySchema() {
		final EntitySchema entitySchema = createComplexEntitySchema();
		assertEntitySchema(
			entitySchema,
			EntitySchemaConverter.convert(EntitySchemaConverter.convert(entitySchema))
		);
	}

	@Nonnull
	private static EntitySchema createComplexEntitySchema() {
		return EntitySchema._internalBuild(
			1,
			Entities.PRODUCT,
			"Lorem ipsum dolor sit amet.",
			"Alert! Deprecated!",
			true,
			false,
			true,
			2,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(Currency.getInstance("EUR"), Currency.getInstance("USD")),
			Map.of(
				"test1", AttributeSchema._internalBuild("test1", LocalDateTime.class, true),
				"test2", GlobalAttributeSchema._internalBuild(
					"test2",
					"description",
					"depr",
					true,
					true,
					true,
					true,
					true,
					true,
					String.class,
					null,
					0
				)
			),
			Map.of(
				"test1", AssociatedDataSchema._internalBuild("test1", "Lorem ipsum", "Alert", Integer.class, false, true),
				"test2", AssociatedDataSchema._internalBuild("test2", "Lorem ipsum", "Alert", String[].class, true, true)
			),
			Map.of(
				"test1", ReferenceSchema._internalBuild(
					"test1",
					Entities.PARAMETER,
					true,
					Cardinality.ZERO_OR_MORE,
					Entities.PARAMETER_GROUP,
					false,
					true,
					true
				),
				"test2", ReferenceSchema._internalBuild(
					"test2",
					"desc",
					"depr",
					Entities.CATEGORY,
					false,
					Cardinality.ONE_OR_MORE,
					null,
					false,
					true,
					true,
					Map.of(
						"code", AttributeSchema._internalBuild(
							"code",
							"description",
							"depr",
							true,
							true,
							true,
							true,
							true,
							String.class,
							null,
							0
						),
						"priority", AttributeSchema._internalBuild(
							"code",
							Long[].class,
							false
						)
					)
				)
			),
			Set.of(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_ATTRIBUTES)
		);
	}

	private static void assertEntitySchema(@Nonnull EntitySchemaContract expected, @Nonnull EntitySchemaContract actual) {
		assertEquals(expected.getVersion(), actual.getVersion());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.isBlank(), actual.isBlank());
		assertEquals(expected.isWithGeneratedPrimaryKey(), actual.isWithGeneratedPrimaryKey());
		assertEquals(expected.isWithHierarchy(), actual.isWithHierarchy());
		assertEquals(expected.isWithPrice(), actual.isWithPrice());
		assertEquals(expected.getIndexedPricePlaces(), actual.getIndexedPricePlaces());
		assertEquals(expected.getLocales(), actual.getLocales());
		assertEquals(expected.getCurrencies(), actual.getCurrencies());
		assertEquals(expected.getEvolutionMode(), actual.getEvolutionMode());

		assertEquals(expected.getAttributes().size(), actual.getAttributes().size());
		expected.getAttributes().forEach((attributeName, attribute) ->
			assertAttributeSchema(attribute, actual.getAttribute(attributeName).orElseThrow()));

		assertEquals(expected.getAssociatedData().size(), actual.getAssociatedData().size());
		expected.getAssociatedData().forEach((associatedDataName, associatedData) ->
			assertAssociatedDataSchema(associatedData, actual.getAssociatedData(associatedDataName).orElseThrow()));

		assertEquals(expected.getReferences().size(), actual.getReferences().size());
		expected.getReferences().forEach((referenceName, reference) ->
			assertReferenceSchema(reference, actual.getReference(referenceName).orElseThrow()));
	}

	private static void assertAttributeSchema(@Nonnull AttributeSchemaContract expected, @Nonnull AttributeSchemaContract actual) {
		assertEquals(expected.getClass(), actual.getClass());
		if (expected instanceof GlobalAttributeSchemaContract expectedGlobal) {
			assertEquals(expectedGlobal.isUniqueGlobally(), ((GlobalAttributeSchemaContract) actual).isUniqueGlobally());
		}

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

	private static void assertAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract expected, @Nonnull AssociatedDataSchemaContract actual) {
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.isLocalized(), actual.isLocalized());
		assertEquals(expected.isNullable(), actual.isNullable());
		assertEquals(expected.getType(), actual.getType());
	}

	private static void assertReferenceSchema(@Nonnull ReferenceSchemaContract expected, @Nonnull ReferenceSchemaContract actual) {
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getDescription(), actual.getDescription());
		assertEquals(expected.getNameVariants(), actual.getNameVariants());
		assertEquals(expected.getDeprecationNotice(), actual.getDeprecationNotice());
		assertEquals(expected.getCardinality(), actual.getCardinality());
		assertEquals(expected.getReferencedEntityType(), actual.getReferencedEntityType());
		assertEquals(expected.isReferencedEntityTypeManaged(), actual.isReferencedEntityTypeManaged());
		assertEquals(expected.getReferencedGroupType(), actual.getReferencedGroupType());
		assertEquals(expected.isReferencedGroupTypeManaged(), actual.isReferencedGroupTypeManaged());
		assertEquals(expected.isFilterable(), actual.isFilterable());
		assertEquals(expected.isFaceted(), actual.isFaceted());

		assertEquals(expected.getAttributes().size(), actual.getAttributes().size());
		expected.getAttributes().forEach((attributeName, attribute) ->
			assertAttributeSchema(attribute, actual.getAttribute(attributeName).orElseThrow()));
	}
}