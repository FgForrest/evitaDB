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

package io.evitadb.index.mutation;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.mutation.index.AttributeIndexMutator;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.index.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.CardinalityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.ChainIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.index.mutation.index.AttributeIndexMutator.executeAttributeDelta;
import static io.evitadb.index.mutation.index.AttributeIndexMutator.executeAttributeRemoval;
import static io.evitadb.index.mutation.index.AttributeIndexMutator.executeAttributeUpsert;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AttributeIndexMutator} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeIndexMutatorTest extends AbstractMutatorTestBase {
	public static final Consumer<Runnable> DO_NOTHING_CONSUMER = runnable -> {
	};
	public static final Supplier<Entity> UNSUPPORTED_OPERATION = () -> {
		throw new UnsupportedOperationException("Not supported in the test.");
	};
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_VARIANT_COUNT = "variantCount";
	private static final String ATTRIBUTE_CHAR_ARRAY = "charArray";
	private Function<String, AttributeSchema> productAttributeSchemaProvider;
	private Function<String, Stream<SortableAttributeCompoundSchema>> productCompoundSchemaProvider;
	private final AtomicInteger priceIdSequence = new AtomicInteger(1);

	private static int findInArray(int[] ids, int id) {
		for (int i = 0; i < ids.length; i++) {
			int examinedId = ids[i];
			if (examinedId == id) {
				return i;
			}
		}
		return -1;
	}

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

	@BeforeEach
	void setUp() {
		productAttributeSchemaProvider = attributeName -> productSchema.getAttribute(attributeName)
			.map(AttributeSchema.class::cast)
			.orElse(null);
		productCompoundSchemaProvider = attributeKey -> productSchema.getSortableAttributeCompoundsForAttribute(attributeKey)
			.stream()
			.map(SortableAttributeCompoundSchema.class::cast);
	}

	@Test
	void shouldInsertNewAttribute() {
		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, new AttributeKey(ATTRIBUTE_CODE), "A",
			true, true, DO_NOTHING_CONSUMER
		);
		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, new AttributeKey(ATTRIBUTE_EAN), "EAN-001",
			true, true, DO_NOTHING_CONSUMER
		);
		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, new AttributeKey(ATTRIBUTE_GLOBAL_CODE), "GA",
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeSchema codeSchema = productAttributeSchemaProvider.apply(ATTRIBUTE_CODE);
		assertEquals(1, productIndex.getUniqueIndex(codeSchema, null).getRecordIdByUniqueValue("A"));
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_EAN, null).getRecordsEqualTo("EAN-001").getArray());
		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) productAttributeSchemaProvider.apply(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertNotNull(globalUniqueIndex);
		assertEquals(
			new EntityReference(productSchema.getName(), 1),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null).orElse(null)
		);

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(6, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldInsertNewAttributeWithAutomaticConversion() {
		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, new AttributeKey(ATTRIBUTE_VARIANT_COUNT), "115",
			false, true, DO_NOTHING_CONSUMER
		);

		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(115).getArray());
		assertTrue(Arrays.binarySearch(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getSortedRecordValues(), 115) >= 0);

		final TrappedChanges trappedChanges = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(3, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(trappedChanges, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
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
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, codeAttributeKey, "B",
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeKey eanAttributeKey = new AttributeKey(ATTRIBUTE_EAN);
		final AttributeSchema eanSchema = AttributeSchema._internalBuild(ATTRIBUTE_EAN, String.class, false);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(eanAttributeKey, eanSchema, attributeValue -> new AttributeValue(eanAttributeKey, "EAN-001"));

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, eanAttributeKey, "EAN-002",
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeKey globalCodeAttributeKey = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		final AttributeSchema globalCodeSchema = AttributeSchema._internalBuild(ATTRIBUTE_GLOBAL_CODE, String.class, false);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(globalCodeAttributeKey, globalCodeSchema, attributeValue -> new AttributeValue(globalCodeAttributeKey, "GA"));

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, globalCodeAttributeKey, "GB",
			true, true, DO_NOTHING_CONSUMER
		);

		final UniqueIndex uniqueIndex = productIndex.getUniqueIndex(codeSchema, null);
		assertNull(uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_EAN, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo("EAN-001").getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo("EAN-002").getArray());

		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) productAttributeSchemaProvider.apply(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertNull(globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null).orElse(null));
		assertEquals(
			new EntityReference(productSchema.getName(), 1),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GB", null).orElse(null)
		);

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(6, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldInsertSimpleAndThenUpdateWithArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false);

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, charArrayAttr, 'A',
			false, true, DO_NOTHING_CONSUMER
		);
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('A').getArray());

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A'}));

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, charArrayAttr, new Character[]{'C', 'D'},
			false, true, DO_NOTHING_CONSUMER
		);

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final TrappedChanges trappedChanges = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(2, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	@Test
	void shouldInsertAndThenUpdateNewArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false);

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, charArrayAttr, new Character[]{'A', 'B'},
			false, true, DO_NOTHING_CONSUMER
		);
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null).getRecordsEqualTo('B').getArray());

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A', 'B'}));

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, charArrayAttr, new Character[]{'C', 'D'},
			false, true, DO_NOTHING_CONSUMER
		);

		final FilterIndex filterIndex = productIndex.getFilterIndex(ATTRIBUTE_CHAR_ARRAY, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('B').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final TrappedChanges trappedChanges = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(2, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	@Nonnull
	ExistingAttributeValueSupplier getEntityAttributeValueSupplier(
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		return new EntityStoragePartExistingDataFactory(executor.getContainerAccessor(), entityType, entityPrimaryKey)
			.getEntityAttributeValueSupplier();
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
						priceIdSequence::incrementAndGet,
						false,
						UNSUPPORTED_OPERATION
					),
					productAttributeSchemaProvider,
					productCompoundSchemaProvider,
					getEntityAttributeValueSupplier(ENTITY_NAME, 2),
					productIndex, attrCode, "A",
					false, true, DO_NOTHING_CONSUMER
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
						priceIdSequence::incrementAndGet,
						false,
						UNSUPPORTED_OPERATION
					),
					productAttributeSchemaProvider,
					productCompoundSchemaProvider,
					getEntityAttributeValueSupplier(ENTITY_NAME, 2),
					productIndex, attrGlobalCode, "GA",
					false, true, DO_NOTHING_CONSUMER
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
				priceIdSequence::incrementAndGet,
				false,
				UNSUPPORTED_OPERATION
			),
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 2),
			productIndex, attrCode, "A",
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeKey attrGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				containerAccessor, 2,
				new MockEntityIndexCreator<>(productIndex),
				new MockEntityIndexCreator<>(catalogIndex),
				() -> productSchema,
				priceIdSequence::incrementAndGet,
				false,
				UNSUPPORTED_OPERATION
			),
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 2),
			productIndex, attrGlobalCode, "GA",
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeSchema codeSchema = productAttributeSchemaProvider.apply(ATTRIBUTE_CODE);
		final UniqueIndex uniqueIndex = productIndex.getUniqueIndex(codeSchema, null);
		assertEquals(2, uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) productAttributeSchemaProvider.apply(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertEquals(new EntityReference(productSchema.getName(), 2), globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null).orElse(null));
		assertEquals(new EntityReference(productSchema.getName(), 1), globalUniqueIndex.getEntityReferenceByUniqueValue("GB", null).orElse(null));

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(5, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	void shouldRemoveAttribute() {
		shouldInsertNewAttribute();
		productIndex.resetDirty();

		final AttributeKey attributeCode = new AttributeKey(ATTRIBUTE_CODE);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeCode,
				productSchema.getAttribute(attributeCode.attributeName()).orElseThrow(),
				attributeValue -> new AttributeValue(attributeCode, "A"));

		executeAttributeRemoval(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, attributeCode,
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeKey attributeGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeGlobalCode, productSchema.getAttribute(attributeGlobalCode.attributeName()).orElse(null),
				attributeValue -> new AttributeValue(attributeGlobalCode, "GA"));

		executeAttributeRemoval(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, attributeGlobalCode,
			true, true, DO_NOTHING_CONSUMER
		);

		final AttributeSchema codeSchema = productAttributeSchemaProvider.apply(ATTRIBUTE_CODE);
		assertNull(productIndex.getUniqueIndex(codeSchema, null));
		assertNull(productIndex.getFilterIndex(ATTRIBUTE_CODE, null));
		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) productAttributeSchemaProvider.apply(ATTRIBUTE_GLOBAL_CODE);
		assertNull(catalogIndex.getGlobalUniqueIndex(attributeSchema, null));

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(1, trappedChanges1.getTrappedChangesCount());

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(1, trappedChanges2.getTrappedChangesCount());
	}

	@Test
	void shouldApplyDeltaToAttribute() {
		final AttributeKey attrVariantCount = new AttributeKey(ATTRIBUTE_VARIANT_COUNT);
		final AttributeSchema variantSchema = AttributeSchema._internalBuild(ATTRIBUTE_VARIANT_COUNT, Integer.class, false);

		executeAttributeUpsert(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, attrVariantCount, 10,
			false, true, DO_NOTHING_CONSUMER
		);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				containerAccessor, 2,
				new MockEntityIndexCreator<>(productIndex),
				new MockEntityIndexCreator<>(catalogIndex),
				() -> productSchema,
				priceIdSequence::incrementAndGet,
				false,
				UNSUPPORTED_OPERATION
			),
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 2),
			productIndex, attrVariantCount, 9,
			false, true, DO_NOTHING_CONSUMER
		);

		final AttributeSchema variantCountSchema = productAttributeSchemaProvider.apply(ATTRIBUTE_VARIANT_COUNT);
		assertNull(productIndex.getUniqueIndex(variantCountSchema, null));
		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(10).getArray());
		final int position = findInArray(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1);
		assertTrue(position >= 0);

		containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(attrVariantCount, variantSchema, attributeValue -> new AttributeValue(attrVariantCount, 10));

		executeAttributeDelta(
			executor,
			productAttributeSchemaProvider,
			productCompoundSchemaProvider,
			getEntityAttributeValueSupplier(ENTITY_NAME, 1),
			productIndex, attrVariantCount, -3, DO_NOTHING_CONSUMER
		);

		assertArrayEquals(new int[]{1}, productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(7).getArray());
		assertArrayEquals(new int[0], productIndex.getFilterIndex(ATTRIBUTE_VARIANT_COUNT, null).getRecordsEqualTo(10).getArray());
		assertTrue(findInArray(productIndex.getSortIndex(ATTRIBUTE_VARIANT_COUNT, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1) < position);

		final TrappedChanges trappedChanges = new TrappedChanges();

		productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(3, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(trappedChanges, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
	}

	private void assertContainsChangedPart(@Nonnull TrappedChanges trappedChanges, @Nonnull AttributeIndexType type, @Nonnull String attributeName) {
		assertContainsChangedPart(trappedChanges, type, attributeName, null);
	}

	private void assertContainsChangedPart(@Nonnull TrappedChanges trappedChanges, @Nonnull AttributeIndexType type, @Nonnull String attributeName, @Nullable Locale locale) {
		final Class<? extends StoragePart> containerType = switch (type) {
			case FILTER -> FilterIndexStoragePart.class;
			case UNIQUE -> UniqueIndexStoragePart.class;
			case SORT -> SortIndexStoragePart.class;
			case CHAIN -> ChainIndexStoragePart.class;
			case CARDINALITY -> CardinalityIndexStoragePart.class;
		};
		final AttributeKey checkedAttributeKey = locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart changedStoragePart = it.next();
			if (changedStoragePart instanceof final AttributeIndexStoragePart aisp) {
				if (containerType.isInstance(changedStoragePart)) {
					final AttributeKey attributeKey = aisp.getAttributeKey();
					if (checkedAttributeKey.equals(attributeKey)) {
						return;
					}
				}
			}
		}
		fail("Expected " + type + " storage part for attribute " + attributeName + " was not found!");
	}

	private void assertNotContainsChangedPart(@Nonnull TrappedChanges trappedChanges, @Nonnull AttributeIndexType type, @Nonnull String attributeName) {
		assertNotContainsChangedPart(trappedChanges, type, attributeName, null);
	}

	private void assertNotContainsChangedPart(@Nonnull TrappedChanges trappedChanges, @Nonnull AttributeIndexType type, @Nonnull String attributeName, @Nullable Locale locale) {
		final Class<? extends StoragePart> containerType = switch (type) {
			case FILTER -> FilterIndexStoragePart.class;
			case UNIQUE -> UniqueIndexStoragePart.class;
			case SORT -> SortIndexStoragePart.class;
			case CHAIN -> ChainIndexStoragePart.class;
			case CARDINALITY -> CardinalityIndexStoragePart.class;
		};
		final AttributeKey checkedAttributeKey = locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart changedStoragePart = it.next();
			if (changedStoragePart instanceof final AttributeIndexStoragePart aisp) {
				if (containerType.isInstance(changedStoragePart)) {
					final AttributeKey attributeKey = aisp.getAttributeKey();
					if (checkedAttributeKey.equals(attributeKey)) {
						fail("Expected " + type + " storage part for attribute " + attributeName + " was found!");
					}
				}
			}
		}
	}

	private void assertContainsChangedPart(@Nonnull TrappedChanges trappedChanges, @Nonnull String attributeName) {
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart changedStoragePart = it.next();
			if (changedStoragePart instanceof final GlobalUniqueIndexStoragePart guisp) {
				final AttributeKey attributeKey = guisp.getAttributeKey();
				if (attributeName.equals(attributeKey.attributeName())) {
					return;
				}
			}
		}
		fail("Expected global storage part for attribute " + attributeName + " was not found!");
	}

}
