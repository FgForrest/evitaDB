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
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link io.evitadb.api.requestResponse.schema.builder.AssociatedDataSchemaBuilder}
 * verifying localization, nullability, description, deprecation, method
 * chaining, and default value behavior when building associated data
 * schemas through the {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AssociatedDataSchemaBuilder")
class AssociatedDataSchemaBuilderTest {

	private EntitySchema productSchema;
	private CatalogSchema catalogSchema;

	@BeforeEach
	void setUp() {
		this.productSchema =
			EntitySchema._internalBuild(Entities.PRODUCT);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(
				APITestConstants.TEST_CATALOG
			),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(
						AssociatedDataSchemaBuilderTest
							.this.productSchema
					);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(
						AssociatedDataSchemaBuilderTest
							.this.productSchema.getName()
					)) {
						return of(
							AssociatedDataSchemaBuilderTest
								.this.productSchema
						);
					}
					return empty();
				}
			}
		);
	}

	/**
	 * Creates a fresh entity schema builder for the product
	 * entity.
	 *
	 * @return new entity schema builder instance
	 */
	@Nonnull
	private EntitySchemaBuilder createEntitySchemaBuilder() {
		return new InternalEntitySchemaBuilder(
			this.catalogSchema,
			this.productSchema
		);
	}

	@Nested
	@DisplayName("Localization operations")
	class LocalizationOperations {

		@Test
		@DisplayName(
			"should make associated data localized"
		)
		void shouldMakeAssociatedDataLocalized() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"labels", String.class,
						AssociatedDataSchemaEditor::localized
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("labels")
					.orElseThrow();

			assertTrue(
				data.isLocalized(),
				"Associated data should be localized"
			);
		}

		@Test
		@DisplayName(
			"should make localized with true decider"
		)
		void shouldMakeAssociatedDataLocalizedWithTrueDecider() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"labels", String.class,
						whichIs -> whichIs
							.localized(() -> true)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("labels")
					.orElseThrow();

			assertTrue(
				data.isLocalized(),
				"Associated data should be localized"
					+ " when decider returns true"
			);
		}

		@Test
		@DisplayName(
			"should not make localized with false decider"
		)
		void shouldNotMakeAssociatedDataLocalizedWithFalseDecider() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"labels", String.class,
						whichIs -> whichIs
							.localized(() -> false)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("labels")
					.orElseThrow();

			assertFalse(
				data.isLocalized(),
				"Associated data should not be"
					+ " localized when decider"
					+ " returns false"
			);
		}
	}

	@Nested
	@DisplayName("Nullability operations")
	class NullabilityOperations {

		@Test
		@DisplayName(
			"should make associated data nullable"
		)
		void shouldMakeAssociatedDataNullable() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"description", String.class,
						AssociatedDataSchemaEditor::nullable
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("description")
					.orElseThrow();

			assertTrue(
				data.isNullable(),
				"Associated data should be nullable"
			);
		}

		@Test
		@DisplayName(
			"should make nullable with true decider"
		)
		void shouldMakeAssociatedDataNullableWithTrueDecider() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"description", String.class,
						whichIs -> whichIs
							.nullable(() -> true)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("description")
					.orElseThrow();

			assertTrue(
				data.isNullable(),
				"Associated data should be nullable"
					+ " when decider returns true"
			);
		}

		@Test
		@DisplayName(
			"should not make nullable with false decider"
		)
		void shouldNotMakeAssociatedDataNullableWithFalseDecider() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"description", String.class,
						whichIs -> whichIs
							.nullable(() -> false)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("description")
					.orElseThrow();

			assertFalse(
				data.isNullable(),
				"Associated data should not be"
					+ " nullable when decider"
					+ " returns false"
			);
		}
	}

	@Nested
	@DisplayName("Description and deprecation")
	class DescriptionAndDeprecation {

		@Test
		@DisplayName("should set description")
		void shouldSetDescription() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"gallery", String.class,
						whichIs -> whichIs
							.withDescription(
								"Product image gallery"
							)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("gallery")
					.orElseThrow();

			assertEquals(
				"Product image gallery",
				data.getDescription()
			);
		}

		@Test
		@DisplayName("should set null description")
		void shouldSetNullDescription() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"gallery", String.class,
						whichIs -> whichIs
							.withDescription(
								"Product image gallery"
							)
							.withDescription(null)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("gallery")
					.orElseThrow();

			assertNull(
				data.getDescription(),
				"Description should be null after"
					+ " setting it to null"
			);
		}

		@Test
		@DisplayName("should set deprecation notice")
		void shouldSetDeprecationNotice() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"gallery", String.class,
						whichIs -> whichIs.deprecated(
							"Use media instead"
						)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("gallery")
					.orElseThrow();

			assertEquals(
				"Use media instead",
				data.getDeprecationNotice()
			);
		}

		@Test
		@DisplayName(
			"should remove deprecation notice"
		)
		void shouldRemoveDeprecation() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"gallery", String.class,
						whichIs -> whichIs
							.deprecated(
								"Use media instead"
							)
							.notDeprecatedAnymore()
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("gallery")
					.orElseThrow();

			assertNull(
				data.getDeprecationNotice(),
				"Deprecation notice should be null"
					+ " after removal"
			);
		}
	}

	@Nested
	@DisplayName("Method chaining")
	class MethodChaining {

		@Test
		@DisplayName(
			"should chain all builder methods"
		)
		void shouldChainAllMethods() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"richContent", String.class,
						whichIs -> whichIs
							.localized()
							.nullable()
							.withDescription(
								"Rich HTML content"
							)
							.deprecated(
								"Moving to markdown"
							)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("richContent")
					.orElseThrow();

			assertAll(
				() -> assertTrue(
					data.isLocalized(),
					"Should be localized"
				),
				() -> assertTrue(
					data.isNullable(),
					"Should be nullable"
				),
				() -> assertEquals(
					"Rich HTML content",
					data.getDescription()
				),
				() -> assertEquals(
					"Moving to markdown",
					data.getDeprecationNotice()
				)
			);
		}
	}

	@Nested
	@DisplayName("toInstance behavior")
	class ToInstanceBehavior {

		@Test
		@DisplayName(
			"should build complete instance with all"
				+ " properties"
		)
		void shouldBuildCompleteInstance() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"metadata", String.class,
						whichIs -> whichIs
							.localized()
							.nullable()
							.withDescription(
								"Product metadata"
							)
							.deprecated(
								"Will be removed in v2"
							)
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("metadata")
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					"metadata", data.getName()
				),
				() -> assertSame(String.class, data.getType()),
				() -> assertTrue(data.isLocalized()),
				() -> assertTrue(data.isNullable()),
				() -> assertEquals(
					"Product metadata",
					data.getDescription()
				),
				() -> assertEquals(
					"Will be removed in v2",
					data.getDeprecationNotice()
				)
			);
		}

		@Test
		@DisplayName(
			"should return default values for"
				+ " unconfigured associated data"
		)
		void shouldReturnDefaultValuesForNewAssociatedData() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withAssociatedData(
						"simple", String.class
					)
					.toInstance();

			final AssociatedDataSchemaContract data =
				schema.getAssociatedData("simple")
					.orElseThrow();

			assertAll(
				() -> assertEquals(
					"simple", data.getName()
				),
				() -> assertSame(String.class, data.getType()),
				() -> assertFalse(
					data.isLocalized(),
					"Default should be not localized"
				),
				() -> assertFalse(
					data.isNullable(),
					"Default should be not nullable"
				),
				() -> assertNull(
					data.getDescription(),
					"Default description should"
						+ " be null"
				),
				() -> assertNull(
					data.getDeprecationNotice(),
					"Default deprecation notice"
						+ " should be null"
				)
			);
		}
	}
}
