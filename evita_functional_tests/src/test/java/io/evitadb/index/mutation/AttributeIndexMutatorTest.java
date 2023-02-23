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

package io.evitadb.index.mutation;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.mutation.AttributeIndexMutator.ExistingAttributeAccessor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;

import static io.evitadb.index.mutation.AttributeIndexMutator.executeAttributeDelta;
import static io.evitadb.index.mutation.AttributeIndexMutator.executeAttributeRemoval;
import static io.evitadb.index.mutation.AttributeIndexMutator.executeAttributeUpsert;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AttributeIndexMutator} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeIndexMutatorTest extends AbstractMutatorTestBase {
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_VARIANT_COUNT = "variantCount";
	private static final String ATTRIBUTE_CHAR_ARRAY = "charArray";

	@Override
	protected void alterCatalogSchema(CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		schema.withAttribute(ATTRIBUTE_GLOBAL_CODE, String.class, whichIs -> whichIs.uniqueGlobally());
	}

	@Override
	protected void alterProductSchema(EntitySchemaEditor.EntitySchemaBuilder schema) {
		schema.withAttribute(ATTRIBUTE_VARIANT_COUNT, Integer.class, whichIs -> whichIs.sortable().filterable());
		schema.withAttribute(ATTRIBUTE_CHAR_ARRAY, Character[].class, whichIs -> whichIs.filterable());
		schema.withGlobalAttribute(ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldInsertNewAttribute() {
		final AttributeKey codeAttr = new AttributeKey(ATTRIBUTE_CODE);
		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, codeAttr),
			productIndex, codeAttr, "A",
			true
		);
		final AttributeKey eanAttr = new AttributeKey(ATTRIBUTE_EAN);
		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, eanAttr),
			productIndex, eanAttr, "EAN-001",
			true
		);
		final AttributeKey globalCodeAttr = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, globalCodeAttr),
			productIndex, globalCodeAttr, "GA",
			true
		);

		assertEquals(1, productIndex.getUniqueIndex(ATTRIBUTE_CODE, null).getRecordIdByUniqueValue("A"));
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_EAN, null).getRecordsEqualTo("EAN-001").getArray());
		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(ATTRIBUTE_GLOBAL_CODE);
		assertNotNull(globalUniqueIndex);
		assertEquals(
			new EntityReference(productSchema.getName(), 1),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GA")
		);

		final Collection<StoragePart> modifiedProductIndexStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(6, modifiedProductIndexStorageParts.size());
		assertContainsChangedPart(modifiedProductIndexStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedProductIndexStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedProductIndexStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(modifiedProductIndexStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(modifiedProductIndexStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final Collection<StoragePart> modifiedCatalogIndexStorageParts = catalogIndex.getModifiedStorageParts();
		assertEquals(2, modifiedCatalogIndexStorageParts.size());
		assertContainsChangedPart(modifiedCatalogIndexStorageParts, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldInsertNewAttributeWithAutomaticConversion() {
		final AttributeKey variantCountAttr = new AttributeKey(ATTRIBUTE_VARIANT_COUNT);
		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, variantCountAttr),
			productIndex, variantCountAttr, "115",
			false
		);

		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(115).getArray());
		assertTrue(Arrays.binarySearch(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getSortedRecordValues(), 115) >= 0);

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(3, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
	}

	@Test
	void shouldInsertAndThenUpdateNewAttribute() {
		shouldInsertNewAttribute();

		final AttributeKey codeAttributeKey = new AttributeKey(ATTRIBUTE_CODE);
		final AttributeSchema codeSchema = AttributeSchema._internalBuild(ATTRIBUTE_CODE, String.class, false);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(codeAttributeKey, codeSchema, attributeValue -> new AttributeValue(codeAttributeKey, "A"));

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, codeAttributeKey),
			productIndex, codeAttributeKey, "B",
			true
		);

		final AttributeKey eanAttributeKey = new AttributeKey(ATTRIBUTE_EAN);
		final AttributeSchema eanSchema = AttributeSchema._internalBuild(ATTRIBUTE_EAN, String.class, false);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(eanAttributeKey, eanSchema, attributeValue -> new AttributeValue(eanAttributeKey, "EAN-001"));

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, eanAttributeKey),
			productIndex, eanAttributeKey, "EAN-002",
			true
		);

		final AttributeKey globalCodeAttributeKey = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		final AttributeSchema globalCodeSchema = AttributeSchema._internalBuild(ATTRIBUTE_GLOBAL_CODE, String.class, false);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(globalCodeAttributeKey, globalCodeSchema, attributeValue -> new AttributeValue(globalCodeAttributeKey, "GA"));

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, globalCodeAttributeKey),
			productIndex, globalCodeAttributeKey, "GB",
			true
		);

		final UniqueIndex uniqueIndex = productIndex.getUniqueIndex(ATTRIBUTE_CODE, null);
		assertNull(uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_EAN, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo("EAN-001").getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo("EAN-002").getArray());

		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(ATTRIBUTE_GLOBAL_CODE);
		assertNull(globalUniqueIndex.getEntityReferenceByUniqueValue("GA"));
		assertEquals(
			new EntityReference(productSchema.getName(), 1),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GB")
		);

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(6, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final Collection<StoragePart> modifiedCatalogIndexStorageParts = catalogIndex.getModifiedStorageParts();
		assertEquals(2, modifiedCatalogIndexStorageParts.size());
		assertContainsChangedPart(modifiedCatalogIndexStorageParts, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldInsertSimpleAndThenUpdateWithArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false);

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, charArrayAttr),
			productIndex, charArrayAttr, 'A',
			false
		);
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('A').getArray());

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A'}));

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, charArrayAttr),
			productIndex, charArrayAttr, new Character[]{'C', 'D'},
			false
		);

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(2, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	@Test
	void shouldInsertAndThenUpdateNewArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false);

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, charArrayAttr),
			productIndex, charArrayAttr, new Character[]{'A', 'B'},
			false
		);
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('B').getArray());

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A', 'B'}));

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, charArrayAttr),
			productIndex, charArrayAttr, new Character[]{'C', 'D'},
			false
		);

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('B').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(2, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	@Test
	void shouldFailToUseUniqueCodeTwice() {
		shouldInsertNewAttribute();

		assertThrows(
			UniqueValueViolationException.class,
			() -> {
				final AttributeKey attrCode = new AttributeKey(ATTRIBUTE_CODE);
				executeAttributeUpsert(
					new EntityIndexLocalMutationExecutor(
						containerAccessor, 2,
						new MockEntityIndexCreator<>(productIndex),
						new MockEntityIndexCreator<>(catalogIndex),
						() -> productSchema,
						entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
					),
					attributeName -> productSchema.getAttribute(attributeName).orElse(null),
					new ExistingAttributeAccessor(ENTITY_NAME, 2, executor, attrCode),
					productIndex, attrCode, "A",
					false
				);
			}
		);

		assertThrows(
			UniqueValueViolationException.class,
			() -> {
				final AttributeKey attrGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
				executeAttributeUpsert(
					new EntityIndexLocalMutationExecutor(
						containerAccessor, 2,
						new MockEntityIndexCreator<>(productIndex),
						new MockEntityIndexCreator<>(catalogIndex),
						() -> productSchema,
						entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
					),
					attributeName -> productSchema.getAttribute(attributeName).orElse(null),
					new ExistingAttributeAccessor(ENTITY_NAME, 2, executor, attrGlobalCode),
					productIndex, attrGlobalCode, "GA",
					false
				);
			}
		);
	}

	@Test
	void shouldReuseUniqueCode() {
		shouldInsertAndThenUpdateNewAttribute();
		productIndex.resetDirty();
		containerAccessor.reset();

		final AttributeKey attrCode = new AttributeKey(ATTRIBUTE_CODE);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				containerAccessor, 2,
				new MockEntityIndexCreator<>(productIndex),
				new MockEntityIndexCreator<>(catalogIndex),
				() -> productSchema,
				entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
			),
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 2, executor, attrCode),
			productIndex, attrCode, "A",
			true
		);

		final AttributeKey attrGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				containerAccessor, 2,
				new MockEntityIndexCreator<>(productIndex),
				new MockEntityIndexCreator<>(catalogIndex),
				() -> productSchema,
				entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
			),
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 2, executor, attrGlobalCode),
			productIndex, attrGlobalCode, "GA",
			true
		);

		final UniqueIndex uniqueIndex = productIndex.getUniqueIndex(ATTRIBUTE_CODE, null);
		assertEquals(2, uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(ATTRIBUTE_GLOBAL_CODE);
		assertEquals(new EntityReference(productSchema.getName(), 2), globalUniqueIndex.getEntityReferenceByUniqueValue("GA"));
		assertEquals(new EntityReference(productSchema.getName(), 1), globalUniqueIndex.getEntityReferenceByUniqueValue("GB"));

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(5, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final Collection<StoragePart> modifiedCatalogIndexStorageParts = catalogIndex.getModifiedStorageParts();
		assertEquals(2, modifiedCatalogIndexStorageParts.size());
		assertContainsChangedPart(modifiedCatalogIndexStorageParts, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldRemoveAttribute() {
		shouldInsertNewAttribute();
		productIndex.resetDirty();

		final AttributeKey attributeCode = new AttributeKey(ATTRIBUTE_CODE);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeCode,
				productSchema.getAttribute(attributeCode.getAttributeName()).orElseThrow(),
				attributeValue -> new AttributeValue(attributeCode, "A"));

		executeAttributeRemoval(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, attributeCode),
			productIndex, attributeCode,
			true
		);

		final AttributeKey attributeGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeGlobalCode, productSchema.getAttribute(attributeGlobalCode.getAttributeName()).orElse(null),
				attributeValue -> new AttributeValue(attributeGlobalCode, "GA"));

		executeAttributeRemoval(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, attributeGlobalCode),
			productIndex, attributeGlobalCode,
			true
		);

		assertNull(productIndex.getUniqueIndex(ATTRIBUTE_CODE, null));
		assertNull(productIndex.getFilterIndex(ATTRIBUTE_CODE, null));
		assertNull(catalogIndex.getGlobalUniqueIndex(ATTRIBUTE_GLOBAL_CODE));

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(1, modifiedStorageParts.size());

		final Collection<StoragePart> catalogStorageParts = catalogIndex.getModifiedStorageParts();
		assertEquals(1, catalogStorageParts.size());
	}

	@Test
	void shouldApplyDeltaToAttribute() {
		final AttributeKey attrVariantCount = new AttributeKey(ATTRIBUTE_VARIANT_COUNT);
		final AttributeSchema variantSchema = AttributeSchema._internalBuild(ATTRIBUTE_VARIANT_COUNT, Integer.class, false);

		executeAttributeUpsert(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, attrVariantCount),
			productIndex, attrVariantCount, 10,
			false
		);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				containerAccessor, 2,
				new MockEntityIndexCreator<>(productIndex),
				new MockEntityIndexCreator<>(catalogIndex),
				() -> productSchema,
				entityType -> entityType.equals(productSchema.getName()) ? productSchema : null
			),
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 2, executor, attrVariantCount),
			productIndex, attrVariantCount, 9,
			false
		);

		assertNull(productIndex.getUniqueIndex(ATTRIBUTE_VARIANT_COUNT, null));
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(10).getArray());
		final int position = findInArray(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1);
		assertTrue(position >= 0);

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(attrVariantCount, variantSchema, attributeValue -> new AttributeValue(attrVariantCount, 10));

		executeAttributeDelta(
			executor,
			attributeName -> productSchema.getAttribute(attributeName).orElse(null),
			new ExistingAttributeAccessor(ENTITY_NAME, 1, executor, attrVariantCount),
			productIndex, attrVariantCount, -3
		);

		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(7).getArray());
		assertArrayEquals(new int[0], productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(10).getArray());
		assertTrue(findInArray(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1) < position);

		final Collection<StoragePart> modifiedStorageParts = productIndex.getModifiedStorageParts();
		assertEquals(3, modifiedStorageParts.size());
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(modifiedStorageParts, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
	}

	private void assertContainsChangedPart(@Nonnull Collection<StoragePart> changedStorageParts, @Nonnull AttributeIndexType type, @Nonnull String attributeName) {
		final Class<? extends StoragePart> containerType = switch (type) {
			case FILTER -> FilterIndexStoragePart.class;
			case UNIQUE -> UniqueIndexStoragePart.class;
			case SORT -> SortIndexStoragePart.class;
		};
		for (StoragePart changedStoragePart : changedStorageParts) {
			if (changedStoragePart instanceof final AttributeIndexStoragePart aisp) {
				if (containerType.isInstance(changedStoragePart)) {
					final AttributeKey attributeKey = aisp.getAttributeKey();
					if (attributeName.equals(attributeKey.getAttributeName())) {
						return;
					}
				}
			}
		}
		fail("Expected " + type + " storage part for attribute " + attributeName + " was not found!");
	}

	private void assertContainsChangedPart(@Nonnull Collection<StoragePart> changedStorageParts, @Nonnull String attributeName) {
		for (StoragePart changedStoragePart : changedStorageParts) {
			if (changedStoragePart instanceof final GlobalUniqueIndexStoragePart guisp) {
				final AttributeKey attributeKey = guisp.getAttributeKey();
				if (attributeName.equals(attributeKey.getAttributeName())) {
					return;
				}
			}
		}
		fail("Expected global storage part for attribute " + attributeName + " was not found!");
	}

	private static int findInArray(int[] ids, int id) {
		for (int i = 0; i < ids.length; i++) {
			int examinedId = ids[i];
			if (examinedId == id) {
				return i;
			}
		}
		return -1;
	}

}