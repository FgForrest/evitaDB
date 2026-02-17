/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ReflectedReferenceSchemaEditor} default methods that throw
 * exceptions to prevent unsupported operations on reflected references.
 *
 * @author evitaDB
 */
@DisplayName("ReflectedReferenceSchemaEditor default methods")
class ReflectedReferenceSchemaEditorTest {

	private static final EntitySchema PRODUCT_SCHEMA =
		EntitySchema._internalBuild(Entities.PRODUCT);
	private static final EntitySchema CATEGORY_SCHEMA =
		EntitySchema._internalBuild(Entities.CATEGORY);
	private static final CatalogSchema CATALOG_SCHEMA =
		CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(PRODUCT_SCHEMA, CATEGORY_SCHEMA);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(PRODUCT_SCHEMA.getName())) {
						return Optional.of(PRODUCT_SCHEMA);
					} else if (entityType.equals(CATEGORY_SCHEMA.getName())) {
						return Optional.of(CATEGORY_SCHEMA);
					}
					return Optional.empty();
				}
			}
		);

	/**
	 * Creates a reflected reference schema builder by first setting up
	 * a base reference on the product schema and then creating a reflected
	 * reference on the category schema pointing back to it.
	 *
	 * @return the captured {@link ReflectedReferenceSchemaBuilder}
	 */
	@Nonnull
	private static ReflectedReferenceSchemaBuilder createReflectedBuilder() {
		// first, create the base reference on product -> category
		final EntitySchemaBuilder productBuilder =
			new InternalEntitySchemaBuilder(CATALOG_SCHEMA, PRODUCT_SCHEMA);
		productBuilder.withReferenceToEntity(
			"productCategory", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
			ReferenceSchemaEditor::indexed
		);

		// now create reflected reference on category -> product
		final AtomicReference<ReflectedReferenceSchemaBuilder> captured =
			new AtomicReference<>();
		final EntitySchemaBuilder categoryBuilder =
			new InternalEntitySchemaBuilder(CATALOG_SCHEMA, CATEGORY_SCHEMA);
		categoryBuilder.withReflectedReferenceToEntity(
			"categoryProducts", Entities.PRODUCT, "productCategory",
			captured::set
		);

		final ReflectedReferenceSchemaBuilder result = captured.get();
		assertNotNull(result, "Builder should have been captured");
		return result;
	}

	@Nested
	@DisplayName("Prohibited operations")
	class ProhibitedOperationsTest {

		@Test
		@DisplayName("nonIndexed() throws InvalidSchemaMutationException")
		void shouldThrowOnNonIndexed() {
			final ReflectedReferenceSchemaBuilder builder =
				createReflectedBuilder();

			// The concrete ReflectedReferenceSchemaBuilder overrides the
			// interface default method with a different message
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				builder::nonIndexed
			);

			assertEquals(
				"Reflected references must be indexed " +
					"(otherwise we wouldn't be able to propagate " +
					"the reflections)!",
				exception.getMessage()
			);
		}

		@Test
		@DisplayName(
			"withGroupType() throws UnsupportedOperationException"
		)
		void shouldThrowOnWithGroupType() {
			final ReflectedReferenceSchemaBuilder builder =
				createReflectedBuilder();

			final UnsupportedOperationException exception = assertThrows(
				UnsupportedOperationException.class,
				() -> builder.withGroupType("someGroup")
			);

			assertEquals(
				ReflectedReferenceSchemaEditor.GROUP_TYPE_EXCEPTION_MESSAGE,
				exception.getMessage()
			);
		}

		@Test
		@DisplayName(
			"withGroupTypeRelatedToEntity() throws UnsupportedOperationException"
		)
		void shouldThrowOnWithGroupTypeRelatedToEntity() {
			final ReflectedReferenceSchemaBuilder builder =
				createReflectedBuilder();

			final UnsupportedOperationException exception = assertThrows(
				UnsupportedOperationException.class,
				() -> builder.withGroupTypeRelatedToEntity("someGroup")
			);

			assertEquals(
				ReflectedReferenceSchemaEditor.GROUP_TYPE_EXCEPTION_MESSAGE,
				exception.getMessage()
			);
		}

		@Test
		@DisplayName(
			"withoutGroupType() throws UnsupportedOperationException"
		)
		void shouldThrowOnWithoutGroupType() {
			final ReflectedReferenceSchemaBuilder builder =
				createReflectedBuilder();

			final UnsupportedOperationException exception = assertThrows(
				UnsupportedOperationException.class,
				builder::withoutGroupType
			);

			assertEquals(
				ReflectedReferenceSchemaEditor.GROUP_TYPE_EXCEPTION_MESSAGE,
				exception.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Exception message constant")
	class ExceptionMessageConstantTest {

		@Test
		@DisplayName("GROUP_TYPE_EXCEPTION_MESSAGE is non-empty")
		void shouldHaveNonEmptyMessage() {
			assertNotNull(
				ReflectedReferenceSchemaEditor.GROUP_TYPE_EXCEPTION_MESSAGE
			);
			assertInstanceOf(
				String.class,
				ReflectedReferenceSchemaEditor.GROUP_TYPE_EXCEPTION_MESSAGE
			);
		}
	}
}
