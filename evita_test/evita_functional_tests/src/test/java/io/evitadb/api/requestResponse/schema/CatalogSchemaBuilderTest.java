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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * This test verifies {@link CatalogSchemaBuilder} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CatalogSchemaBuilder")
@Tag(CONTRACT)
@Tag(SCHEMA)
class CatalogSchemaBuilderTest {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	private static void assertAttribute(
		GlobalAttributeSchemaContract attributeSchema,
		boolean unique,
		boolean uniqueGlobally,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		int indexedDecimalPlaces,
		Class<? extends Serializable> ofType
	) {
		assertNotNull(attributeSchema);
		assertEquals(unique, attributeSchema.isUnique());
		assertEquals(uniqueGlobally, attributeSchema.isUniqueGlobally());
		assertEquals(filterable, attributeSchema.isFilterable());
		assertEquals(sortable, attributeSchema.isSortable());
		assertEquals(localized, attributeSchema.isLocalized());
		assertEquals(nullable, attributeSchema.isNullable());
		assertEquals(representative, attributeSchema.isRepresentative());
		assertSame(ofType, attributeSchema.getType());
		assertEquals(indexedDecimalPlaces, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("define catalog schema with various attribute configurations")
	void shouldDefineCategorySchema() {
		final CatalogSchemaContract updatedSchema = new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.uniqueGlobally().representative())
			.withAttribute("url", String.class, whichIs -> whichIs.uniqueGlobally().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable().nullable())
			.withAttribute("priority", Long.class, AttributeSchemaEditor::sortable)
			/* finally apply schema changes */
			.toInstance();

		assertEquals(5, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, true, false, false, false, false, true, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("name").orElseThrow(), false, false, true, true, false, true, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("url").orElseThrow(), true, true, false, false, true, false, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, false, true, false, true, false, false, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, false, true, false, false, false, 0, Long.class);
	}

	@Test
	@DisplayName("reject duplicate attribute names differing only in case")
	void shouldFailToDefineTwoAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
				.withAttribute("abc", String.class)
				.withAttribute("Abc", String.class)
				.toMutation()
		);
	}

	@Test
	@DisplayName("resolve attributes by naming conventions and handle removal")
	void shouldWorkWithAttributesInNamingConventionsWorkProperly() {
		final CatalogSchemaBuilder schemaBuilder = new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
			.withAttribute("some-attribute-1", String.class)
			.withAttribute("attribute", String.class)
			.withAttribute("code", String.class);

		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		schemaBuilder.withoutAttribute("attribute");

		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		final CatalogSchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	@DisplayName("apply each entity schema modification once when the builder replays its mutations")
	void shouldApplyEntitySchemaModificationsOnceWhenReplaying() {
		// the second modification is combined with the first one, which makes the builder replay from its base;
		// a replay over an already modified entity schema would create the reference a second time
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(catalogSchemaWithProduct())
			.withEntitySchema(
				ENTITY_PRODUCT,
				entity -> entity.withReferenceTo(
					"tags", "tag", Cardinality.ZERO_OR_MORE, whichIs -> whichIs.withAttribute("order", Integer.class)
				)
			)
			.withEntitySchema(ENTITY_PRODUCT, entity -> entity.withDescription("changed"));

		builder.toInstance();

		final EntitySchemaContract productSchema = builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT);
		assertEquals("changed", productSchema.getDescription());
		assertTrue(productSchema.getReferenceOrThrowException("tags").getAttribute("order").isPresent());
	}

	@Test
	@DisplayName("serve the entity schemas as its mutations leave them, without being materialized first")
	void shouldServeCurrentEntitySchemas() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(catalogSchemaWithProduct())
			.withEntitySchema(ENTITY_PRODUCT, entity -> entity.withDescription("changed"));

		assertEquals("changed", builder.getEntitySchema(ENTITY_PRODUCT).orElseThrow().getDescription());
		assertEquals("changed", builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT).getDescription());
		assertEquals(
			List.of("changed"),
			builder.getEntitySchemas().stream().map(EntitySchemaContract::getDescription).toList()
		);
	}

	@Test
	@DisplayName("remove a removed global attribute from the entity schemas that use it")
	void shouldCascadeGlobalAttributeRemovalIntoEntitySchemas() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(catalogSchemaWithProductUsingCode())
			.withoutAttribute(ATTRIBUTE_CODE);

		assertTrue(builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT).getAttribute(ATTRIBUTE_CODE).isEmpty());
	}

	@Test
	@DisplayName("carry a changed global attribute into the entity schemas that use it")
	void shouldCascadeGlobalAttributeChangeIntoEntitySchemas() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(catalogSchemaWithProductUsingCode())
			.withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::sortable);

		assertTrue(builder.getAttribute(ATTRIBUTE_CODE).orElseThrow().isSortable());
		assertTrue(
			builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT).getAttribute(ATTRIBUTE_CODE).orElseThrow().isSortable()
		);
	}

	@Test
	@DisplayName("apply the cascades of a catalog mutation after the entity modifications that follow it, as the server does")
	void shouldApplyCascadesAfterFollowingEntityModifications() {
		// the server changes the description while the global attribute still exists and removes the attribute
		// from the entity with the cascade once the whole batch is processed
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			catalogSchemaWithProductUsingCode(),
			List.of(
				new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
				new ModifyEntitySchemaMutation(
					ENTITY_PRODUCT, new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_CODE, "changed")
				)
			)
		);

		assertTrue(builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT).getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertTrue(builder.getAttribute(ATTRIBUTE_CODE).isEmpty());
	}

	@Test
	@DisplayName("keep a cascade applied once when the builder is read and extended again")
	void shouldKeepCascadeAppliedOnceWhenExtended() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(catalogSchemaWithProductUsingCode())
			.withoutAttribute(ATTRIBUTE_CODE);
		assertTrue(builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT).getAttribute(ATTRIBUTE_CODE).isEmpty());

		builder.withEntitySchema(ENTITY_PRODUCT, whichIs -> whichIs.withDescription("changed"));

		final EntitySchemaContract product = builder.getEntitySchemaOrThrowException(ENTITY_PRODUCT);
		assertTrue(product.getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertEquals("changed", product.getDescription());
	}

	/**
	 * Creates a catalog schema with the global attribute `code` and a product entity schema that uses it.
	 */
	@Nonnull
	private static CatalogSchemaContract catalogSchemaWithProductUsingCode() {
		final CatalogSchemaContract catalogWithCode = new InternalCatalogSchemaBuilder(CATALOG_SCHEMA)
			.withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::filterable)
			.toInstance();
		final EntitySchemaContract productSchema = new InternalEntitySchemaBuilder(
			catalogWithCode, EntitySchema._internalBuild(ENTITY_PRODUCT)
		)
			.withGlobalAttribute(ATTRIBUTE_CODE)
			.toInstance();
		assertTrue(productSchema.getAttribute(ATTRIBUTE_CODE).isPresent());
		return CatalogSchema._internalBuild(
			catalogWithCode.version(),
			catalogWithCode.getName(),
			catalogWithCode.getNameVariants(),
			catalogWithCode.getDescription(),
			catalogWithCode.getConflictResolution().orElse(null),
			catalogWithCode.getCatalogEvolutionMode(),
			catalogWithCode.getAttributes(),
			singleEntitySchemaProvider(productSchema)
		);
	}

	/**
	 * Creates a catalog schema that provides a single product entity schema with a description.
	 */
	@Nonnull
	private static CatalogSchema catalogSchemaWithProduct() {
		final EntitySchemaContract productSchema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_PRODUCT)
		)
			.withDescription("original")
			.toInstance();
		return CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			singleEntitySchemaProvider(productSchema)
		);
	}

	/**
	 * Creates an entity schema provider that knows exactly the given entity schema.
	 */
	@Nonnull
	private static EntitySchemaProvider singleEntitySchemaProvider(@Nonnull EntitySchemaContract entitySchema) {
		return new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of(entitySchema);
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return entitySchema.getName().equals(entityType) ? Optional.of(entitySchema) : Optional.empty();
			}
		};
	}

}
