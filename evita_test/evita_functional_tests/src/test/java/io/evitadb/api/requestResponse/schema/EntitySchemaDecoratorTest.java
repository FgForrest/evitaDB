/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntitySchemaDecorator} verifying that it correctly
 * delegates to the underlying {@link EntitySchema} and provides
 * seal-breaking operations that return writable builders.
 *
 * @author evitaDB
 */
@DisplayName("EntitySchemaDecorator")
class EntitySchemaDecoratorTest {

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	private static final EntitySchema ENTITY_SCHEMA =
		EntitySchema._internalBuild(Entities.PRODUCT);

	@Nested
	@DisplayName("Construction and delegation")
	class ConstructionAndDelegationTest {

		@Test
		@DisplayName("returns the underlying delegate via getDelegate()")
		void shouldReturnDelegate() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertSame(ENTITY_SCHEMA, decorator.getDelegate());
		}

		@Test
		@DisplayName("delegates getName() to underlying schema")
		void shouldDelegateGetName() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertEquals(Entities.PRODUCT, decorator.getName());
		}

		@Test
		@DisplayName("delegates version() to underlying schema")
		void shouldDelegateVersion() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertEquals(ENTITY_SCHEMA.version(), decorator.version());
		}

		@Test
		@DisplayName("delegates getAttributes() to underlying schema")
		void shouldDelegateGetAttributes() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertTrue(decorator.getAttributes().isEmpty());
		}

		@Test
		@DisplayName("delegates getReferences() to underlying schema")
		void shouldDelegateGetReferences() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertTrue(decorator.getReferences().isEmpty());
		}

		@Test
		@DisplayName("delegates getEvolutionMode() to underlying schema")
		void shouldDelegateGetEvolutionMode() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			assertEquals(
				EnumSet.allOf(EvolutionMode.class),
				decorator.getEvolutionMode()
			);
		}
	}

	@Nested
	@DisplayName("Seal-breaking operations")
	class SealBreakingOperationsTest {

		@Test
		@DisplayName("openForWrite() returns a non-null builder")
		void shouldReturnBuilderFromOpenForWrite() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder = decorator.openForWrite();

			assertNotNull(builder);
		}

		@Test
		@DisplayName("openForWrite() builder reflects the original schema name")
		void shouldReturnBuilderWithOriginalSchemaName() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder = decorator.openForWrite();

			assertEquals(Entities.PRODUCT, builder.getName());
		}

		@Test
		@DisplayName("withMutations(array) returns builder with mutations applied")
		void shouldReturnBuilderWithMutationsFromArray() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder = decorator.withMutations(
				new ModifyEntitySchemaDescriptionMutation("Test description")
			);

			assertNotNull(builder);
			assertEquals("Test description", builder.getDescription());
		}

		@Test
		@DisplayName(
			"withMutations(collection) returns builder with mutations applied"
		)
		void shouldReturnBuilderWithMutationsFromCollection() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder = decorator.withMutations(
				List.of(
					new ModifyEntitySchemaDescriptionMutation("Collection description")
				)
			);

			assertNotNull(builder);
			assertEquals("Collection description", builder.getDescription());
		}

		@Test
		@DisplayName("withMutations with empty array returns clean builder")
		void shouldReturnCleanBuilderWithEmptyMutationArray() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder = decorator.withMutations();

			assertNotNull(builder);
			assertEquals(Entities.PRODUCT, builder.getName());
		}

		@Test
		@DisplayName(
			"withMutations with empty collection returns clean builder"
		)
		void shouldReturnCleanBuilderWithEmptyMutationCollection() {
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(() -> CATALOG_SCHEMA, ENTITY_SCHEMA);

			final EntitySchemaBuilder builder =
				decorator.withMutations(Collections.emptyList());

			assertNotNull(builder);
			assertEquals(Entities.PRODUCT, builder.getName());
		}

		@Test
		@DisplayName(
			"catalog schema supplier is invoked on each seal-breaking call"
		)
		void shouldInvokeCatalogSchemaSupplierOnEachCall() {
			final int[] callCount = {0};
			final EntitySchemaDecorator decorator =
				new EntitySchemaDecorator(
					() -> {
						callCount[0]++;
						return CATALOG_SCHEMA;
					},
					ENTITY_SCHEMA
				);

			decorator.openForWrite();
			decorator.openForWrite();

			assertEquals(2, callCount[0]);
		}
	}
}
