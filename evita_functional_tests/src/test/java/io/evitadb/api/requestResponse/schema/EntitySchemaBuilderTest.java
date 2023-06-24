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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.AssociatedDataAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.ReferenceAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies the process of evitaDB schema update.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntitySchemaBuilderTest {
	private final EntitySchema productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
	private final EntitySchema categorySchema = EntitySchema._internalBuild(Entities.CATEGORY);
	private final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class),
		entityType -> {
			if (entityType.equals(productSchema.getName())) {
				return productSchema;
			} else if (entityType.equals(categorySchema.getName())) {
				return categorySchema;
			} else {
				return null;
			}
		}
	);

	private static void assertAttribute(AttributeSchemaContract attributeSchema, boolean unique, boolean filterable, boolean sortable, boolean localized, int indexedDecimalPlaces, Class<? extends Serializable> ofType) {
		assertNotNull(attributeSchema);
		assertEquals(unique, attributeSchema.isUnique(), "Attribute `" + attributeSchema.getName() + "` should be unique, but it is not!");
		assertEquals(filterable, attributeSchema.isFilterable(), "Attribute `" + attributeSchema.getName() + "` should be filterable, but it is not!");
		assertEquals(sortable, attributeSchema.isSortable(), "Attribute `" + attributeSchema.getName() + "` should be sortable, but it is not!");
		assertEquals(localized, attributeSchema.isLocalized(), "Attribute `" + attributeSchema.getName() + "` should be localized, but it is not!");
		assertEquals(ofType, attributeSchema.getType(), "Attribute `" + attributeSchema.getName() + "` should be `" + ofType + "`, but it is `" + attributeSchema.getType() + "`!");
		assertEquals(indexedDecimalPlaces, attributeSchema.getIndexedDecimalPlaces(), "Attribute `" + attributeSchema.getName() + "` should have `" + indexedDecimalPlaces + "` indexed decimal places, but has `" + attributeSchema.getIndexedDecimalPlaces() + "`!");
	}

	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaContract constructExampleSchema(EntitySchemaBuilder schemaBuilder) {
		return schemaBuilder
			/* all is strictly verified but associated data and references can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define sortable attribute compounds */
			.withSortableAttributeCompound(
				"codeWithEan",
				attributeElement("code"),
				attributeElement("ean")
			)
			.withSortableAttributeCompound(
				"priorityAndQuantity",
				new AttributeElement[]{
					attributeElement("priority", OrderDirection.DESC),
					attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				},
				whichIs -> whichIs
					.withDescription("Priority and quantity in descending order.")
					.deprecated("Already deprecated.")
			)
			/* here we define references that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexed().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* references may be also represented be entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.toInstance();
	}

	@Test
	void shouldDefineProductSchema() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
		assertSchemaContents(updatedSchema);
	}

	@Test
	void shouldWorkWithAttributesInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
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

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithSortableAttributeCompoundsInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
			.withAttribute("attribute", String.class)
			.withAttribute("code", String.class)
			.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))
			.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))
			.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute"));

		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));

		schemaBuilder.withoutSortableAttributeCompound("compound");

		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithReferenceAttributesInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withAttribute("some-attribute-1", String.class)
						.withAttribute("attribute", String.class)
						.withAttribute("code", String.class);

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withoutAttribute("attribute");

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithReferenceAttributesInNamingConventionsWorkProperlyBuildingInstanceInTheMiddle() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withAttribute("some-attribute-1", String.class)
						.withAttribute("attribute", String.class)
						.withAttribute("code", String.class);

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			)
			.toInstance();

		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			catalogSchema,
			instance
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withoutAttribute("attribute");

					assertNotNull(thatIs.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
				}
			)
			.toInstance();

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithReferenceSortableAttributeCompoundsInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withAttribute("attribute", String.class)
						.withAttribute("code", String.class)
						.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))
						.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))
						.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute"));

					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withoutSortableAttributeCompound("compound");

					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithReferenceSortableAttributeCompoundsInNamingConventionsWorkProperlyBuildingInstanceInTheMiddle() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withAttribute("attribute", String.class)
						.withAttribute("code", String.class)
						.withSortableAttributeCompound("some-compound-1", attributeElement("attribute"), attributeElement("code"))
						.withSortableAttributeCompound("compound", attributeElement("code"), attributeElement("attribute"))
						.withSortableAttributeCompound("anotherCompound", attributeElement("code", OrderDirection.DESC), attributeElement("attribute"));

					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			).toInstance();

		final EntitySchemaContract updatedSchema = new InternalEntitySchemaBuilder(
			catalogSchema,
			instance
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> {
					thatIs.withoutSortableAttributeCompound("compound");

					assertNotNull(thatIs.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
					assertNull(thatIs.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
					assertNotNull(thatIs.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
				}
			);

		final ReferenceSchemaContract testReference = updatedSchema.getReferenceOrThrowException("testReference");

		assertNotNull(testReference.getSortableAttributeCompoundByName("someCompound1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(testReference.getSortableAttributeCompoundByName("compound", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("anotherCompound", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(testReference.getSortableAttributeCompoundByName("another_compound", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithAssociatedDatasInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
			.withAssociatedData("some-associatedData-1", String.class)
			.withAssociatedData("data", String.class)
			.withAssociatedData("code", String.class);

		assertNotNull(schemaBuilder.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		schemaBuilder.withoutAssociatedData("data");

		assertNotNull(schemaBuilder.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getAssociatedDataByName("someAssociatedData1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAssociatedDataByName("data", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAssociatedDataByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAssociatedDataByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldWorkWithReferenceInNamingConventionsWorkProperly() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		schemaBuilder
			.withReferenceTo("some-reference-1", Entities.BRAND, Cardinality.ZERO_OR_MORE)
			.withReferenceTo("reference", Entities.CATEGORY, Cardinality.ZERO_OR_MORE)
			.withReferenceTo("code", Entities.PRODUCT, Cardinality.ZERO_OR_MORE);

		assertNotNull(schemaBuilder.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		schemaBuilder.withoutReferenceTo("reference");

		assertNotNull(schemaBuilder.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		final EntitySchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getReferenceByName("someReference1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getReferenceByName("reference", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getReferenceByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getReferenceByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

	@Test
	void shouldUpdateBuildExactlySameProductSchema() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema, productSchema
		);

		final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
		assertSchemaContents(updatedSchema);

		assertSchemaContents(
			new InternalEntitySchemaBuilder(
				catalogSchema, updatedSchema
			).toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributes() {
		final CatalogSchemaContract updatedCatalogSchema = new InternalCatalogSchemaBuilder(catalogSchema)
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.toInstance();

		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			updatedCatalogSchema,
			productSchema
		);

		assertThrows(
			AttributeAlreadyPresentInCatalogSchemaException.class,
			() -> {
				final EntitySchemaContract updatedSchema = constructExampleSchema(schemaBuilder);
				assertSchemaContents(updatedSchema);
			}
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingSortableAttributeCompounds() {
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();


		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("name"), attributeElement("code")
				)
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingSortableAttributeCompoundsInSpecificNamingConvention() {
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"code-name",
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();


		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("name"), attributeElement("code")
				)
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributeAndSortableAttributeCompound() {
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();


		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				theSchema
			)
				.withSortableAttributeCompound(
					"name",
					attributeElement("name"), attributeElement("code")
				)
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingAttributeAndSortableAttributeCompoundInSpecificNamingConvention() {
		final EntitySchema theSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"), attributeElement("name")
			)
			.toInstance();

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				theSchema
			)
				.withAttribute(
					"codeName",
					String.class
				)
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithSingleAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithMultipleAttributeElementsOfSameName() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"),
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithSortableAttributeCompoundWithNonExistingAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("notExisting"),
					attributeElement("code")
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToRemoveAttributePresentInSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withAttribute("code", String.class)
			.withAttribute("name", String.class)
			.withSortableAttributeCompound(
				"codeName",
				attributeElement("code"),
				attributeElement("name")
			);

		final EntitySchemaContract instance = schemaBuilder.toInstance();

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> schemaBuilder.withoutAttribute("code")
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				instance
			)
				.withoutAttribute("code")
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingReferenceSortableAttributeCompounds() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"), attributeElement("name")
				)
			)
			.toInstance();

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				instance
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withSortableAttributeCompound(
						"codeName",
						attributeElement("name"), attributeElement("code")
					)
				)
				.toInstance()
		);

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> schemaBuilder.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withSortableAttributeCompound(
						"codeName",
						attributeElement("name"), attributeElement("code")
					)
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithConflictingReferenceAttributeAndSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		);

		final EntitySchemaContract instance = schemaBuilder
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("code", String.class)
				.withAttribute("name", String.class)
				.withSortableAttributeCompound(
					"codeName",
					attributeElement("code"), attributeElement("name")
				)
			)
			.toInstance();

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				instance
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("codeName", String.class)
				)
				.toInstance()
		);

		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> schemaBuilder.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.withAttribute("codeName", String.class)
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithSingleAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code")
						)
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithMultipleAttributeElementsOfSameName() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code"), attributeElement("code")
						)
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToDefineProductSchemaWithReferenceSortableAttributeCompoundWithNonExistingAttributeElement() {
		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withSortableAttributeCompound(
							"codeName",
							attributeElement("code"), attributeElement("nonExisting")
						)
				)
				.toInstance()
		);
	}

	@Test
	void shouldFailToRemoveReferenceAttributePresentInSortableAttributeCompound() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			productSchema
		)
			.withReferenceTo("testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> thatIs.withAttribute("code", String.class)
					.withAttribute("name", String.class)
					.withSortableAttributeCompound(
						"codeName",
						attributeElement("code"), attributeElement("name")
					)
			);

		final EntitySchemaContract schema = schemaBuilder.toInstance();

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> schemaBuilder.withReferenceToEntity(
				"testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> {
					thatIs.withoutAttribute("code");
				}
			)
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				schema
			).withReferenceToEntity(
				"testReference", Entities.PRODUCT, Cardinality.ZERO_OR_ONE,
				thatIs -> {
					thatIs.withoutAttribute("code");
				}
			)
		);
	}

	@Test
	void shouldDefineProductSchemaWithSharedAttributes() {
		final CatalogSchemaContract updatedCatalogSchema = new InternalCatalogSchemaBuilder(catalogSchema)
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.toInstance();

		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			updatedCatalogSchema,
			productSchema
		);

		final EntitySchemaContract productSchema = schemaBuilder
			/* all is strictly verified but associated data and references can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withGlobalAttribute("code")
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withGlobalAttribute("name")
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define sortable attribute compounds */
			.withSortableAttributeCompound(
				"codeWithEan",
				attributeElement("code"),
				attributeElement("ean")
			)
			.withSortableAttributeCompound(
				"priorityAndQuantity",
				new AttributeElement[]{
					attributeElement("priority", OrderDirection.DESC),
					attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
				},
				whichIs -> whichIs
					.withDescription("Priority and quantity in descending order.")
					.deprecated("Already deprecated.")
			)
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define references that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexed().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* references may be also represented be entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.toInstance();

		assertSchemaContents(productSchema);
	}

	@SuppressWarnings("Convert2MethodRef")
	@Test
	void shouldDefineCategorySchema() {
		final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema, categorySchema
		);

		final EntitySchemaContract updatedSchema = schemaBuilder
			/* all is strictly verified for categories */
			.verifySchemaStrictly()
			/* categories are organized in a tree manner */
			.withHierarchy()
			/* categories don't have prices, we can also omit this line */
			.withoutPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* finally apply schema changes */
			.toInstance();

		assertTrue(updatedSchema.getEvolutionMode().isEmpty());
		assertTrue(updatedSchema.isWithHierarchy());

		assertFalse(updatedSchema.isWithPrice());

		assertTrue(updatedSchema.getLocales().contains(Locale.ENGLISH));
		assertTrue(updatedSchema.getLocales().contains(new Locale("cs", "CZ")));

		assertEquals(5, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, false, false, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, true, false, true, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, true, false, 0, Long.class);

		assertEquals(1, updatedSchema.getAssociatedData().size());
		assertAssociatedData(updatedSchema.getAssociatedData("labels"), true, ComplexDataObject.class);

		assertTrue(updatedSchema.getReferences().isEmpty());
	}

	@Test
	void shouldFailToDefineTwoAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withAttribute("abc", String.class)
				.withAttribute("Abc", String.class)
		);
	}

	@Test
	void shouldFailToDefineTwoReferenceAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withReferenceTo(
					"brand",
					Entities.BRAND,
					Cardinality.ZERO_OR_ONE,
					thatIs -> thatIs
						.withAttribute("abc", String.class)
						.withAttribute("Abc", String.class)
				)
		);
	}

	@Test
	void shouldFailToDefineTwoReferencesSharingNameInSpecificNamingConvention() {
		assertThrows(
			ReferenceAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withReferenceTo("abc", Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.withReferenceTo("Abc", Entities.BRAND, Cardinality.ZERO_OR_ONE)
		);
	}

	@Test
	void shouldFailToDefineTwoAssociatedDataSharingNameInSpecificNamingConvention() {
		assertThrows(
			AssociatedDataAlreadyPresentInEntitySchemaException.class,
			() -> new InternalEntitySchemaBuilder(
				catalogSchema,
				productSchema
			)
				.withAssociatedData("abc", String.class)
				.withAssociatedData("Abc", String.class)
		);
	}

	private void assertSchemaContents(EntitySchemaContract updatedSchema) {
		assertTrue(updatedSchema.allows(EvolutionMode.ADDING_ASSOCIATED_DATA));
		assertTrue(updatedSchema.allows(EvolutionMode.ADDING_REFERENCES));

		assertFalse(updatedSchema.isWithHierarchy());

		assertTrue(updatedSchema.isWithPrice());

		assertTrue(updatedSchema.getLocales().contains(Locale.ENGLISH));
		assertTrue(updatedSchema.getLocales().contains(new Locale("cs", "CZ")));

		assertEquals(9, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, false, false, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, true, false, true, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("quantity").orElseThrow(), false, true, false, false, 2, BigDecimal.class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, true, false, 0, Long.class);

		assertEquals(2, updatedSchema.getSortableAttributeCompounds().size());
		assertSortableAttributeCompound(
			updatedSchema.getSortableAttributeCompound("codeWithEan").orElse(null),
			attributeElement("code"), attributeElement("ean")
		);
		assertSortableAttributeCompound(
			updatedSchema.getSortableAttributeCompound("priorityAndQuantity").orElse(null),
			"Priority and quantity in descending order.",
			"Already deprecated.",
			attributeElement("priority", OrderDirection.DESC),
			attributeElement("quantity", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
		);

		assertEquals(2, updatedSchema.getAssociatedData().size());
		assertAssociatedData(updatedSchema.getAssociatedData("referencedFiles"), false, ComplexDataObject.class);
		assertAssociatedData(updatedSchema.getAssociatedData("labels"), true, ComplexDataObject.class);

		assertEquals(3, updatedSchema.getReferences().size());

		final ReferenceSchemaContract categoryReference = updatedSchema.getReferenceOrThrowException(Entities.CATEGORY);
		assertReference(of(categoryReference), false);
		assertEquals(1, categoryReference.getAttributes().size());
		assertAttribute(categoryReference.getAttribute("categoryPriority").orElseThrow(), false, false, true, false, 0, Long.class);

		assertReference(updatedSchema.getReference(Entities.BRAND), true);
		assertReference(updatedSchema.getReference("stock"), true);
	}

	private void assertSortableAttributeCompound(SortableAttributeCompoundSchemaContract compound, AttributeElement... elements) {
		assertSortableAttributeCompound(compound, null, null, elements);
	}

	private void assertSortableAttributeCompound(SortableAttributeCompoundSchemaContract compound, String description, String deprecation, AttributeElement... elements) {
		assertNotNull(compound);
		if (description == null) {
			assertNull(compound.getDescription());
		} else {
			assertEquals(description, compound.getDescription());
		}

		if (deprecation == null) {
			assertNull(compound.getDeprecationNotice());
		} else {
			assertEquals(deprecation, compound.getDeprecationNotice());
		}

		assertArrayEquals(compound.getAttributeElements().toArray(), elements);
	}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private void assertReference(Optional<ReferenceSchemaContract> reference, boolean indexed) {
		assertTrue(reference.isPresent());
		assertEquals(indexed, reference.get().isFaceted());
	}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private void assertAssociatedData(Optional<AssociatedDataSchemaContract> associatedDataSchema, boolean localized, Class<? extends Serializable> ofType) {
		assertTrue(associatedDataSchema.isPresent());
		associatedDataSchema.ifPresent(it -> {
			assertEquals(localized, it.isLocalized());
			assertEquals(ofType, it.getType());
		});
	}

	public interface Entities {
		String PRODUCT = "PRODUCT";
		String CATEGORY = "CATEGORY";
		String BRAND = "BRAND";
	}

	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
	}

	public static class Labels implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
	}

}