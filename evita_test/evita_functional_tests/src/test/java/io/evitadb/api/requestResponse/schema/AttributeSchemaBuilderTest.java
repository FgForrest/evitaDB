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
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link io.evitadb.api.requestResponse.schema.builder.AttributeSchemaBuilder},
 * {@link io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder}, and
 * {@link io.evitadb.api.requestResponse.schema.builder.GlobalAttributeSchemaBuilder} verifying
 * scope-based filtering, uniqueness, sortability operations, BooleanSupplier decider variants,
 * and known bugs in the builder implementations.
 *
 * @author evitaDB
 */
@DisplayName("Attribute schema builders")
class AttributeSchemaBuilderTest {

	private EntitySchema productSchema;
	private CatalogSchema catalogSchema;

	@BeforeEach
	void setUp() {
		this.productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(
						AttributeSchemaBuilderTest.this.productSchema
					);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(
					@Nonnull String entityType
				) {
					if (entityType.equals(
						AttributeSchemaBuilderTest.this
							.productSchema.getName()
					)) {
						return of(
							AttributeSchemaBuilderTest.this.productSchema
						);
					}
					return empty();
				}
			}
		);
	}

	/**
	 * Creates a fresh entity schema builder for the product entity.
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

	/**
	 * Creates a fresh catalog schema builder.
	 *
	 * @return new catalog schema builder instance
	 */
	@Nonnull
	private CatalogSchemaBuilder createCatalogSchemaBuilder() {
		return new CatalogSchemaDecorator(this.catalogSchema)
			.openForWrite();
	}

	@Nested
	@DisplayName("EntityAttributeSchemaBuilder")
	class EntityAttributeTests {

		@Nested
		@DisplayName("filterability scope operations")
		class FilterabilityScope {

			@Test
			@DisplayName(
				"should make attribute filterable in LIVE scope"
			)
			void shouldMakeAttributeFilterableInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.filterableInScope(
								Scope.LIVE
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertTrue(attr.isFilterableInScope(Scope.LIVE));
				assertFalse(
					attr.isFilterableInScope(Scope.ARCHIVED)
				);
			}

			@Test
			@DisplayName(
				"should remove filterability from LIVE scope"
			)
			void shouldMakeAttributeNonFilterableInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.filterableInScope(Scope.LIVE)
								.nonFilterableInScope(Scope.LIVE)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertFalse(
					attr.isFilterableInScope(Scope.LIVE),
					"LIVE filterability should be removed"
				);
				assertFalse(
					attr.isFilterableInScope(Scope.ARCHIVED),
					"ARCHIVED should remain not filterable"
				);
			}
		}

		@Nested
		@DisplayName("uniqueness scope operations")
		class UniquenessScope {

			@Test
			@DisplayName(
				"should make attribute unique in LIVE scope"
			)
			void shouldMakeAttributeUniqueInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.uniqueInScope(
								Scope.LIVE
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertTrue(attr.isUniqueInScope(Scope.LIVE));
				assertEquals(
					AttributeUniquenessType
						.UNIQUE_WITHIN_COLLECTION,
					attr.getUniquenessType(Scope.LIVE)
				);
				assertFalse(
					attr.isUniqueInScope(Scope.ARCHIVED)
				);
			}

			@Test
			@DisplayName(
				"should make attribute unique in both scopes"
			)
			void shouldMakeAttributeUniqueInBothScopes() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.uniqueInScope(
								Scope.LIVE, Scope.ARCHIVED
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertTrue(attr.isUniqueInScope(Scope.LIVE));
				assertTrue(
					attr.isUniqueInScope(Scope.ARCHIVED)
				);
			}

			@Test
			@DisplayName(
				"should remove uniqueness from LIVE scope"
			)
			void shouldMakeAttributeNonUniqueInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueInScope(Scope.LIVE)
								.nonUniqueInScope(Scope.LIVE)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertFalse(
					attr.isUniqueInScope(Scope.LIVE),
					"LIVE uniqueness should be removed"
				);
				assertFalse(
					attr.isUniqueInScope(Scope.ARCHIVED),
					"ARCHIVED should remain not unique"
				);
			}

			@Test
			@DisplayName(
				"should make attribute unique within locale"
			)
			void shouldMakeAttributeUniqueWithinLocaleInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueWithinLocaleInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertTrue(
					attr.isUniqueWithinLocaleInScope(
						Scope.LIVE
					)
				);
				assertEquals(
					AttributeUniquenessType
						.UNIQUE_WITHIN_COLLECTION_LOCALE,
					attr.getUniquenessType(Scope.LIVE)
				);
			}

			@Test
			@DisplayName(
				"should remove locale uniqueness via "
					+ "nonUniqueWithinLocaleInScope"
			)
			void shouldMakeAttributeNonUniqueWithinLocaleInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueWithinLocaleInScope(
									Scope.LIVE
								)
								.nonUniqueWithinLocaleInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertFalse(
					attr.isUniqueWithinLocaleInScope(
						Scope.LIVE
					),
					"LIVE locale uniqueness should be "
						+ "removed"
				);
			}
		}

		@Nested
		@DisplayName("sortability scope operations")
		class SortabilityScope {

			@Test
			@DisplayName(
				"should make attribute sortable in LIVE scope"
			)
			void shouldMakeAttributeSortableInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs.sortableInScope(
								Scope.LIVE
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertTrue(
					attr.isSortableInScope(Scope.LIVE)
				);
				assertFalse(
					attr.isSortableInScope(Scope.ARCHIVED)
				);
			}

			@Test
			@DisplayName(
				"should remove sortability from LIVE scope"
			)
			void shouldMakeAttributeNonSortableInScope() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs
								.sortableInScope(Scope.LIVE)
								.nonSortableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertFalse(
					attr.isSortableInScope(Scope.LIVE),
					"LIVE sortability should be removed"
				);
				assertFalse(
					attr.isSortableInScope(Scope.ARCHIVED),
					"ARCHIVED should remain not sortable"
				);
			}
		}

		@Nested
		@DisplayName("BooleanSupplier decider variants")
		class DeciderVariants {

			@Test
			@DisplayName(
				"should make filterable when decider "
					+ "returns true"
			)
			void shouldMakeAttributeFilterableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.filterable(
								() -> true
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertTrue(attr.isFilterable());
			}

			@Test
			@DisplayName(
				"should not make filterable when decider "
					+ "returns false"
			)
			void shouldMakeAttributeNotFilterableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.filterable(
								() -> false
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertFalse(
					attr.isFilterable(),
					"Attribute should not be filterable "
						+ "when decider returns false"
				);
			}

			@Test
			@DisplayName(
				"should make sortable when decider "
					+ "returns true"
			)
			void shouldMakeAttributeSortableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs.sortable(
								() -> true
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertTrue(attr.isSortable());
			}

			@Test
			@DisplayName(
				"should not make sortable when decider "
					+ "returns false"
			)
			void shouldMakeAttributeNotSortableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs.sortable(
								() -> false
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertFalse(attr.isSortable());
			}

			@Test
			@DisplayName(
				"should make localized when decider "
					+ "returns true"
			)
			void shouldMakeAttributeLocalizedWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs.localized(
								() -> true
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertTrue(attr.isLocalized());
			}

			@Test
			@DisplayName(
				"should not make localized when decider "
					+ "returns false"
			)
			void shouldMakeAttributeNotLocalizedWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs.localized(
								() -> false
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertFalse(attr.isLocalized());
			}

			@Test
			@DisplayName(
				"should make nullable when decider "
					+ "returns true"
			)
			void shouldMakeAttributeNullableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs.nullable(
								() -> true
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertTrue(attr.isNullable());
			}

			@Test
			@DisplayName(
				"should not make nullable when decider "
					+ "returns false"
			)
			void shouldMakeAttributeNotNullableWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs.nullable(
								() -> false
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("name").orElseThrow();

				assertFalse(attr.isNullable());
			}

			@Test
			@DisplayName(
				"should make representative when decider "
					+ "returns true"
			)
			void shouldMakeAttributeRepresentativeWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.representative(
								() -> true
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertTrue(attr.isRepresentative());
			}

			@Test
			@DisplayName(
				"should not make representative when "
					+ "decider returns false"
			)
			void shouldMakeAttributeNotRepresentativeWithDecider() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.representative(
								() -> false
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertFalse(attr.isRepresentative());
			}
		}

		@Nested
		@DisplayName("common attribute properties")
		class CommonProperties {

			@Test
			@DisplayName("should set default value")
			void shouldSetDefaultValue() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs.withDefaultValue(
								42L
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertEquals(42L, attr.getDefaultValue());
			}

			@Test
			@DisplayName(
				"should set null default value"
			)
			void shouldSetNullDefaultValue() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"priority", Long.class,
							whichIs -> whichIs
								.withDefaultValue(42L)
								.withDefaultValue(null)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("priority")
						.orElseThrow();

				assertNull(attr.getDefaultValue());
			}

			@Test
			@DisplayName("should set description")
			void shouldSetDescription() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.withDescription(
								"Product code"
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertEquals(
					"Product code", attr.getDescription()
				);
			}

			@Test
			@DisplayName("should set deprecation notice")
			void shouldSetDeprecationNotice() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs.deprecated(
								"Use sku instead"
							)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertEquals(
					"Use sku instead",
					attr.getDeprecationNotice()
				);
			}

			@Test
			@DisplayName("should remove deprecation")
			void shouldRemoveDeprecation() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.deprecated("Use sku instead")
								.notDeprecatedAnymore()
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("code").orElseThrow();

				assertNull(attr.getDeprecationNotice());
			}

			@Test
			@DisplayName(
				"should set indexed decimal places"
			)
			void shouldSetIndexDecimalPlaces() {
				final EntitySchemaContract schema =
					createEntitySchemaBuilder()
						.withAttribute(
							"price",
							java.math.BigDecimal.class,
							whichIs -> whichIs
								.filterable()
								.indexDecimalPlaces(4)
						)
						.toInstance();

				final EntityAttributeSchemaContract attr =
					schema.getAttribute("price")
						.orElseThrow();

				assertEquals(
					4, attr.getIndexedDecimalPlaces()
				);
			}
		}
	}

	@Nested
	@DisplayName("GlobalAttributeSchemaBuilder")
	class GlobalAttributeTests {

		@Nested
		@DisplayName("global uniqueness scope operations")
		class GlobalUniquenessScope {

			@Test
			@DisplayName(
				"should make attribute globally unique "
					+ "in LIVE scope"
			)
			void shouldMakeAttributeUniqueGloballyInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueGloballyInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(
					attr.isUniqueGloballyInScope(Scope.LIVE)
				);
				assertEquals(
					GlobalAttributeUniquenessType
						.UNIQUE_WITHIN_CATALOG,
					attr.getGlobalUniquenessType(Scope.LIVE)
				);
				assertFalse(
					attr.isUniqueGloballyInScope(
						Scope.ARCHIVED
					)
				);
			}

			@Test
			@DisplayName(
				"should remove global uniqueness from "
					+ "LIVE scope"
			)
			void shouldMakeAttributeNonUniqueGloballyInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueGloballyInScope(
									Scope.LIVE
								)
								.nonUniqueGloballyInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertFalse(
					attr.isUniqueGloballyInScope(Scope.LIVE),
					"Global uniqueness should be removed "
						+ "from LIVE scope"
				);
				assertFalse(
					attr.isUniqueGloballyInScope(
						Scope.ARCHIVED
					),
					"ARCHIVED should remain not globally "
						+ "unique"
				);
			}

			@Test
			@DisplayName(
				"should make attribute globally unique "
					+ "within locale"
			)
			void shouldMakeAttributeUniqueGloballyWithinLocaleInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueGloballyWithinLocaleInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertTrue(
					attr
						.isUniqueGloballyWithinLocaleInScope(
							Scope.LIVE
						)
				);
				assertEquals(
					GlobalAttributeUniquenessType
						.UNIQUE_WITHIN_CATALOG_LOCALE,
					attr.getGlobalUniquenessType(Scope.LIVE)
				);
			}

			@Test
			@DisplayName(
				"should remove global locale uniqueness "
					+ "from LIVE scope"
			)
			void shouldMakeAttributeNonUniqueGloballyWithinLocaleInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueGloballyWithinLocaleInScope(
									Scope.LIVE
								)
								.nonUniqueGloballyWithinLocaleInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertFalse(
					attr.isUniqueGloballyWithinLocaleInScope(
						Scope.LIVE
					),
					"Global locale uniqueness should be "
						+ "removed from LIVE scope"
				);
				assertFalse(
					attr.isUniqueGloballyWithinLocaleInScope(
						Scope.ARCHIVED
					),
					"ARCHIVED should remain not globally "
						+ "unique within locale"
				);
			}
		}

		@Nested
		@DisplayName("BooleanSupplier decider variants")
		class GlobalDeciderVariants {

			@Test
			@DisplayName(
				"should make globally unique when "
					+ "decider returns true"
			)
			void shouldMakeAttributeUniqueGloballyWithDecider() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueGlobally(() -> true)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(attr.isUniqueGlobally());
			}

			@Test
			@DisplayName(
				"should not make globally unique when "
					+ "decider returns false"
			)
			void shouldMakeAttributeNotUniqueGloballyWithDecider() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueGlobally(() -> false)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertFalse(attr.isUniqueGlobally());
			}

			@Test
			@DisplayName(
				"should make globally unique within "
					+ "locale when decider returns true"
			)
			void shouldMakeAttributeUniqueGloballyWithinLocaleWithDecider() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueGloballyWithinLocale(
									() -> true
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertTrue(
					attr.isUniqueGloballyWithinLocale()
				);
			}

			@Test
			@DisplayName(
				"should not make globally unique within "
					+ "locale when decider returns false"
			)
			void shouldMakeAttributeNotUniqueGloballyWithinLocaleWithDecider() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.uniqueGloballyWithinLocale(
									() -> false
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertFalse(
					attr.isUniqueGloballyWithinLocale()
				);
			}
		}

		@Nested
		@DisplayName("representative operations")
		class RepresentativeOperations {

			@Test
			@DisplayName(
				"should make global attribute "
					+ "representative"
			)
			void shouldMakeGlobalAttributeRepresentative() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							AttributeSchemaEditor::representative
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(attr.isRepresentative());
			}

			@Test
			@DisplayName(
				"should make representative with decider"
			)
			void shouldMakeGlobalAttributeRepresentativeWithDecider() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.representative(() -> true)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(attr.isRepresentative());
			}
		}

		@Nested
		@DisplayName("mutation impact tracking")
		class MutationImpactTracking {

			@Test
			@DisplayName(
				"should properly track mutation impact "
					+ "for uniqueGloballyInScope"
			)
			void shouldTrackMutationImpactForUniqueGlobally() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.sortable()
								.uniqueGloballyInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(
					attr.isUniqueGloballyInScope(Scope.LIVE)
				);
				assertTrue(attr.isSortable());
			}
		}

		@Nested
		@DisplayName("inherited properties from "
			+ "AbstractAttributeSchemaBuilder")
		class InheritedProperties {

			@Test
			@DisplayName(
				"should set default value"
			)
			void shouldSetDefaultValueForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.withDefaultValue(
									"defaultCode"
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertEquals(
					"defaultCode",
					attr.getDefaultValue()
				);
			}

			@Test
			@DisplayName(
				"should set null default value"
			)
			void shouldSetNullDefaultValueForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.withDefaultValue("value")
								.withDefaultValue(null)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertNull(attr.getDefaultValue());
			}

			@Test
			@DisplayName(
				"should set description"
			)
			void shouldSetDescriptionForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.withDescription(
									"Global code"
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertEquals(
					"Global code",
					attr.getDescription()
				);
			}

			@Test
			@DisplayName(
				"should set deprecation notice"
			)
			void shouldSetDeprecationNoticeForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.deprecated("Use newCode")
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertEquals(
					"Use newCode",
					attr.getDeprecationNotice()
				);
			}

			@Test
			@DisplayName(
				"should remove deprecation"
			)
			void shouldRemoveDeprecationForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.deprecated("notice")
								.notDeprecatedAnymore()
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertNull(attr.getDeprecationNotice());
			}

			@Test
			@DisplayName(
				"should make filterable in LIVE scope"
			)
			void shouldMakeGlobalAttributeFilterableInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.filterableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(
					attr.isFilterableInScope(Scope.LIVE)
				);
				assertFalse(
					attr.isFilterableInScope(
						Scope.ARCHIVED
					)
				);
			}

			@Test
			@DisplayName(
				"should remove filterability "
					+ "from LIVE scope"
			)
			void shouldRemoveFilterabilityFromGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.filterableInScope(
									Scope.LIVE
								)
								.nonFilterableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertFalse(
					attr.isFilterableInScope(Scope.LIVE),
					"Filterability should be removed "
						+ "from LIVE scope"
				);
				assertFalse(
					attr.isFilterableInScope(
						Scope.ARCHIVED
					),
					"ARCHIVED should remain "
						+ "not filterable"
				);
			}

			@Test
			@DisplayName(
				"should make sortable in LIVE scope"
			)
			void shouldMakeGlobalAttributeSortableInScope() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.sortableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(
					attr.isSortableInScope(Scope.LIVE)
				);
			}

			@Test
			@DisplayName(
				"should remove sortability "
					+ "from LIVE scope"
			)
			void shouldRemoveSortabilityFromGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.sortableInScope(
									Scope.LIVE
								)
								.nonSortableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertFalse(
					attr.isSortableInScope(Scope.LIVE),
					"Sortability should be removed "
						+ "from LIVE scope"
				);
			}

			@Test
			@DisplayName(
				"should set indexed decimal places"
			)
			void shouldSetIndexDecimalPlacesForGlobalAttribute() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"price",
							BigDecimal.class,
							whichIs -> whichIs
								.filterable()
								.indexDecimalPlaces(4)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("price")
						.orElseThrow();

				assertEquals(
					4,
					attr.getIndexedDecimalPlaces()
				);
			}

			@Test
			@DisplayName(
				"should make localized"
			)
			void shouldMakeGlobalAttributeLocalized() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							AttributeSchemaEditor::localized
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertTrue(attr.isLocalized());
			}

			@Test
			@DisplayName(
				"should make non-localized"
			)
			void shouldMakeGlobalAttributeNonLocalized() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.localized()
								.nonLocalized()
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertFalse(attr.isLocalized());
			}

			@Test
			@DisplayName(
				"should make nullable"
			)
			void shouldMakeGlobalAttributeNullable() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							AttributeSchemaEditor::nullable
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(attr.isNullable());
			}

			@Test
			@DisplayName(
				"should make non-nullable"
			)
			void shouldMakeGlobalAttributeNonNullable() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.nullable()
								.nonNullable()
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertFalse(attr.isNullable());
			}
		}

		@Nested
		@DisplayName("combined inherited operations")
		class CombinedOperations {

			@Test
			@DisplayName(
				"should combine global uniqueness "
					+ "with sortability"
			)
			void shouldCombineGlobalUniquenessWithSortability() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"code", String.class,
							whichIs -> whichIs
								.uniqueGloballyInScope(
									Scope.LIVE
								)
								.sortableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("code")
						.orElseThrow();

				assertTrue(
					attr.isUniqueGloballyInScope(
						Scope.LIVE
					),
					"Should be globally unique "
						+ "in LIVE scope"
				);
				assertTrue(
					attr.isSortableInScope(Scope.LIVE),
					"Should be sortable "
						+ "in LIVE scope"
				);
			}

			@Test
			@DisplayName(
				"should chain multiple inherited "
					+ "methods"
			)
			void shouldChainMultipleInheritedMethods() {
				final CatalogSchemaContract schema =
					createCatalogSchemaBuilder()
						.withAttribute(
							"name", String.class,
							whichIs -> whichIs
								.withDescription("desc")
								.nullable()
								.localized()
								.sortableInScope(
									Scope.LIVE
								)
						)
						.toInstance();

				final GlobalAttributeSchemaContract attr =
					schema.getAttribute("name")
						.orElseThrow();

				assertEquals(
					"desc", attr.getDescription()
				);
				assertTrue(
					attr.isNullable(),
					"Should be nullable"
				);
				assertTrue(
					attr.isLocalized(),
					"Should be localized"
				);
				assertTrue(
					attr.isSortableInScope(Scope.LIVE),
					"Should be sortable "
						+ "in LIVE scope"
				);
			}
		}
	}

	@Nested
	@DisplayName("AttributeSchemaBuilder (reference-level)")
	class ReferenceAttributeTests {

		@Test
		@DisplayName(
			"should make reference attribute filterable"
		)
		void shouldMakeReferenceAttributeFilterable() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand", Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref
							.indexed()
							.withAttribute(
								"priority",
								Integer.class,
								AttributeSchemaEditor::filterable
							)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand").orElseThrow();
			final AttributeSchemaContract attr =
				refSchema.getAttribute("priority")
					.orElseThrow();

			assertTrue(attr.isFilterable());
		}

		@Test
		@DisplayName(
			"should make reference attribute sortable"
		)
		void shouldMakeReferenceAttributeSortable() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand", Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref
							.indexed()
							.withAttribute(
								"priority",
								Integer.class,
								AttributeSchemaEditor::sortable
							)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand").orElseThrow();
			final AttributeSchemaContract attr =
				refSchema.getAttribute("priority")
					.orElseThrow();

			assertTrue(attr.isSortable());
		}

		@Test
		@DisplayName(
			"should make reference attribute unique"
		)
		void shouldMakeReferenceAttributeUnique() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand", Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref
							.indexed()
							.withAttribute(
								"code", String.class,
								AttributeSchemaEditor::unique
							)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand").orElseThrow();
			final AttributeSchemaContract attr =
				refSchema.getAttribute("code")
					.orElseThrow();

			assertTrue(attr.isUnique());
		}

		@Test
		@DisplayName(
			"should make reference attribute localized"
		)
		void shouldMakeReferenceAttributeLocalized() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand", Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref.withAttribute(
							"label", String.class,
							AttributeSchemaEditor::localized
						)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand").orElseThrow();
			final AttributeSchemaContract attr =
				refSchema.getAttribute("label")
					.orElseThrow();

			assertTrue(attr.isLocalized());
		}

		@Test
		@DisplayName(
			"should make reference attribute nullable"
		)
		void shouldMakeReferenceAttributeNullable() {
			final EntitySchemaContract schema =
				createEntitySchemaBuilder()
					.withReferenceTo(
						"brand", Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ref -> ref.withAttribute(
							"note", String.class,
							AttributeSchemaEditor::nullable
						)
					)
					.toInstance();

			final ReferenceSchemaContract refSchema =
				schema.getReference("brand").orElseThrow();
			final AttributeSchemaContract attr =
				refSchema.getAttribute("note")
					.orElseThrow();

			assertTrue(attr.isNullable());
		}
	}
}
