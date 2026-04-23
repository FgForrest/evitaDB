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
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
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
 * Tests for {@link CatalogSchemaDecorator} verifying that it correctly
 * delegates to the underlying {@link CatalogSchema} and provides
 * seal-breaking operations that return writable builders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CatalogSchemaDecorator")
class CatalogSchemaDecoratorTest {

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	@Nested
	@DisplayName("Construction and delegation")
	class ConstructionAndDelegationTest {

		@Test
		@DisplayName("returns the underlying delegate via getDelegate()")
		void shouldReturnDelegate() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			assertSame(CATALOG_SCHEMA, decorator.getDelegate());
		}

		@Test
		@DisplayName("delegates getName() to underlying schema")
		void shouldDelegateGetName() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			assertEquals(
				APITestConstants.TEST_CATALOG, decorator.getName()
			);
		}

		@Test
		@DisplayName("delegates version() to underlying schema")
		void shouldDelegateVersion() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			assertEquals(CATALOG_SCHEMA.version(), decorator.version());
		}

		@Test
		@DisplayName("delegates getCatalogEvolutionMode() to underlying schema")
		void shouldDelegateGetCatalogEvolutionMode() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			assertEquals(
				EnumSet.allOf(CatalogEvolutionMode.class),
				decorator.getCatalogEvolutionMode()
			);
		}

		@Test
		@DisplayName("delegates getAttributes() to underlying schema")
		void shouldDelegateGetAttributes() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			assertTrue(decorator.getAttributes().isEmpty());
		}
	}

	@Nested
	@DisplayName("Seal-breaking operations")
	class SealBreakingOperationsTest {

		@Test
		@DisplayName("openForWrite() returns a non-null builder")
		void shouldReturnBuilderFromOpenForWrite() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder = decorator.openForWrite();

			assertNotNull(builder);
		}

		@Test
		@DisplayName(
			"openForWrite() builder reflects the original schema name"
		)
		void shouldReturnBuilderWithOriginalSchemaName() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder = decorator.openForWrite();

			assertEquals(APITestConstants.TEST_CATALOG, builder.getName());
		}

		@Test
		@DisplayName(
			"openForWriteWithMutations(array) returns builder with mutations applied"
		)
		void shouldReturnBuilderWithMutationsFromArray() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder =
				decorator.openForWriteWithMutations(
					new ModifyCatalogSchemaDescriptionMutation("Test description")
				);

			assertNotNull(builder);
			assertEquals(
				"Test description",
				builder.getDescription()
			);
		}

		@Test
		@DisplayName(
			"openForWriteWithMutations(collection) returns builder with mutations applied"
		)
		void shouldReturnBuilderWithMutationsFromCollection() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder =
				decorator.openForWriteWithMutations(
					List.of(
						new ModifyCatalogSchemaDescriptionMutation(
							"Collection description"
						)
					)
				);

			assertNotNull(builder);
			assertEquals(
				"Collection description",
				builder.getDescription()
			);
		}

		@Test
		@DisplayName(
			"openForWriteWithMutations with empty array returns clean builder"
		)
		void shouldReturnCleanBuilderWithEmptyMutationArray() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder =
				decorator.openForWriteWithMutations();

			assertNotNull(builder);
			assertEquals(APITestConstants.TEST_CATALOG, builder.getName());
		}

		@Test
		@DisplayName(
			"openForWriteWithMutations with empty collection returns clean builder"
		)
		void shouldReturnCleanBuilderWithEmptyMutationCollection() {
			final CatalogSchemaDecorator decorator =
				new CatalogSchemaDecorator(CATALOG_SCHEMA);

			final CatalogSchemaBuilder builder =
				decorator.openForWriteWithMutations(Collections.emptyList());

			assertNotNull(builder);
			assertEquals(APITestConstants.TEST_CATALOG, builder.getName());
		}
	}
}
