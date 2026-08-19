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

package io.evitadb.index.mutation.local;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.mutation.local.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;

import static io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeDelta;
import static io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeRemoval;
import static io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeUpsert;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies {@link AttributeIndexMutator} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeIndexMutator — attribute index operations")
@Tag(INDEXING)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class AttributeIndexMutatorTest extends AbstractMutatorTestBase {
	public static final Supplier<Entity> UNSUPPORTED_OPERATION = () -> {
		throw new UnsupportedOperationException("Not supported in the test.");
	};
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_VARIANT_COUNT = "variantCount";
	private static final String ATTRIBUTE_CHAR_ARRAY = "charArray";
	private AttributeAndCompoundSchemaProvider productAttributeSchemaProvider;
	private final AtomicInteger priceIdSequence = new AtomicInteger(1);

	/**
	 * Finds the index of `id` in the given array, or returns -1 if not found.
	 *
	 * @param ids the array to search
	 * @param id  the value to find
	 * @return index of the value, or -1
	 */
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
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		schema.withAttribute(ATTRIBUTE_GLOBAL_CODE, String.class, GlobalAttributeSchemaEditor::uniqueGlobally);
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schema) {
		schema.withAttribute(ATTRIBUTE_VARIANT_COUNT, Integer.class, whichIs -> whichIs.sortable().filterable());
		schema.withAttribute(ATTRIBUTE_CHAR_ARRAY, Character[].class, AttributeSchemaEditor::filterable);
		schema.withGlobalAttribute(ATTRIBUTE_GLOBAL_CODE);
	}

	@BeforeEach
	void setUp() {
		this.productAttributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema);
	}

	@Test
	@DisplayName("Should insert new unique, filter, and global-unique attributes into index")
	void shouldInsertNewAttribute() {
		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			new AttributeKey(ATTRIBUTE_CODE), "A",
			true, true
		);
		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			new AttributeKey(ATTRIBUTE_EAN), "EAN-001",
			true, true
		);
		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			new AttributeKey(ATTRIBUTE_GLOBAL_CODE), "GA",
			true, true
		);

		final AttributeSchema codeSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_CODE);
		final AttributeSchema eanSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_EAN);
		assertEquals(1, this.productIndex.getUniqueIndex(null, codeSchema, null).getRecordIdByUniqueValue("A"));
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, eanSchema, null).getRecordsEqualTo("EAN-001").getArray());
		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = this.catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertNotNull(globalUniqueIndex);
		assertEquals(
			new EntityReferenceWithLocale(this.productSchema.getName(), 1, null),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null, this.classifierResolver).orElse(null)
		);

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(6, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		this.catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	@DisplayName("Should insert new attribute with automatic type conversion")
	void shouldInsertNewAttributeWithAutomaticConversion() {
		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			new AttributeKey(ATTRIBUTE_VARIANT_COUNT), "115",
			false, true
		);

		final AttributeSchema variantCountSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_VARIANT_COUNT);
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, variantCountSchema, null).getRecordsEqualTo(115).getArray());
		assertTrue(Arrays.binarySearch(this.productIndex.getSortIndex(null, variantCountSchema, null).getSortedRecordValues(), 115) >= 0);

		final TrappedChanges trappedChanges = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(3, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(trappedChanges, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
	}

	@Test
	@DisplayName("Should update existing attribute values in unique, filter, and global-unique indices")
	void shouldInsertAndThenUpdateNewAttribute() {
		shouldInsertNewAttribute();

		final AttributeKey codeAttributeKey = new AttributeKey(ATTRIBUTE_CODE);
		final AttributeSchema codeSchema = AttributeSchema._internalBuild(ATTRIBUTE_CODE, String.class, false, ConflictResolutionOverride.INHERITED);
		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(codeAttributeKey, codeSchema, attributeValue -> new AttributeValue(codeAttributeKey, "A"));

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			codeAttributeKey, "B",
			true, true
		);

		final AttributeKey eanAttributeKey = new AttributeKey(ATTRIBUTE_EAN);
		final AttributeSchema eanSchema = AttributeSchema._internalBuild(ATTRIBUTE_EAN, String.class, false, ConflictResolutionOverride.INHERITED);
		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(eanAttributeKey, eanSchema, attributeValue -> new AttributeValue(eanAttributeKey, "EAN-001"));

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			eanAttributeKey, "EAN-002",
			true, true
		);

		final AttributeKey globalCodeAttributeKey = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		final AttributeSchema globalCodeSchema = AttributeSchema._internalBuild(ATTRIBUTE_GLOBAL_CODE, String.class, false, ConflictResolutionOverride.INHERITED);
		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(globalCodeAttributeKey, globalCodeSchema, attributeValue -> new AttributeValue(globalCodeAttributeKey, "GA"));

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			globalCodeAttributeKey, "GB",
			true, true
		);

		final UniqueIndex uniqueIndex = this.productIndex.getUniqueIndex(null, codeSchema, null);
		assertNull(uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final FilterIndex filterIndex = this.productIndex.getFilterIndex(null, eanSchema, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo("EAN-001").getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo("EAN-002").getArray());

		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = this.catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertNull(globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null, this.classifierResolver).orElse(null));
		assertEquals(
			new EntityReferenceWithLocale(this.productSchema.getName(), 1, null),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GB", null, this.classifierResolver).orElse(null)
		);

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(6, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_EAN);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		this.catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	@DisplayName("Should update simple value to array value in filter index")
	void shouldInsertSimpleAndThenUpdateWithArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false, ConflictResolutionOverride.INHERITED);

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			charArrayAttr, 'A',
			false, true
		);
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, charArraySchema, null).getRecordsEqualTo('A').getArray());

		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A'}));

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			charArrayAttr, new Character[]{'C', 'D'},
			false, true
		);

		final FilterIndex filterIndex = this.productIndex.getFilterIndex(null, charArraySchema, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final TrappedChanges trappedChanges = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(2, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	@Test
	@DisplayName("Should replace array attribute values in filter index")
	void shouldInsertAndThenUpdateNewArrayAttribute() {
		final AttributeKey charArrayAttr = new AttributeKey(ATTRIBUTE_CHAR_ARRAY);
		final AttributeSchema charArraySchema = AttributeSchema._internalBuild(ATTRIBUTE_CHAR_ARRAY, Character[].class, false, ConflictResolutionOverride.INHERITED);

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			charArrayAttr, new Character[]{'A', 'B'},
			false, true
		);
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, charArraySchema, null).getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, charArraySchema, null).getRecordsEqualTo('B').getArray());

		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(charArrayAttr, charArraySchema, attributeValue -> new AttributeValue(charArrayAttr, new Character[]{'A', 'B'}));

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			charArrayAttr, new Character[]{'C', 'D'},
			false, true
		);

		final FilterIndex filterIndex = this.productIndex.getFilterIndex(null, charArraySchema, null);
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('A').getArray());
		assertArrayEquals(new int[0], filterIndex.getRecordsEqualTo('B').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('C').getArray());
		assertArrayEquals(new int[]{1}, filterIndex.getRecordsEqualTo('D').getArray());

		final TrappedChanges trappedChanges = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(2, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_CHAR_ARRAY);
	}

	/**
	 * Creates an {@link ExistingAttributeValueSupplier} for the given schema and entity PK,
	 * backed by the test's container accessor.
	 *
	 * @param entitySchema     the entity schema providing attribute definitions
	 * @param entityPrimaryKey the primary key of the entity whose attributes to supply
	 * @return a supplier that retrieves existing attribute values from test storage
	 */
	@Nonnull
	ExistingAttributeValueSupplier getEntityAttributeValueSupplier(
		@Nonnull EntitySchema entitySchema,
		int entityPrimaryKey
	) {
		return new EntityStoragePartExistingDataFactory(
			this.executor.getContainerAccessor(), entitySchema, entityPrimaryKey, Map.of()
		).getEntityAttributeValueSupplier();
	}

	@Test
	@DisplayName("Should reject duplicate unique code value for a different entity")
	void shouldFailToUseUniqueCodeTwice() {
		shouldInsertNewAttribute();

		assertThrows(
			UniqueValueViolationException.class,
			() -> {
				final AttributeKey attrCode = new AttributeKey(ATTRIBUTE_CODE);
				executeAttributeUpsert(
					new EntityIndexLocalMutationExecutor(
						this.containerAccessor, 2,
						new MockEntityIndexCreator<>(this.productIndex),
						new MockEntityIndexCreator<>(this.catalogIndex),
						() -> this.productSchema,
						this.priceIdSequence::incrementAndGet,
						UNSUPPORTED_OPERATION,
						null,
						null,
						null,
						this.classifierResolver,
						this.usageRegistry,
						this.catalogUsageRegistry
					),
					null,
					this.productAttributeSchemaProvider,
					getEntityAttributeValueSupplier(this.productSchema, 2),
					this.productIndex,
					this.productIndex,
					attrCode, "A",
					false, true
				);
			}
		);

		assertThrows(
			UniqueValueViolationException.class,
			() -> {
				final AttributeKey attrGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
				executeAttributeUpsert(
					new EntityIndexLocalMutationExecutor(
						this.containerAccessor, 2,
						new MockEntityIndexCreator<>(this.productIndex),
						new MockEntityIndexCreator<>(this.catalogIndex),
						() -> this.productSchema,
						this.priceIdSequence::incrementAndGet,
						UNSUPPORTED_OPERATION,
						null,
						null,
						null,
						this.classifierResolver,
						this.usageRegistry,
						this.catalogUsageRegistry
					),
					null,
					this.productAttributeSchemaProvider,
					getEntityAttributeValueSupplier(this.productSchema, 2),
					this.productIndex,
					this.productIndex,
					attrGlobalCode, "GA",
					false, true
				);
			}
		);
	}

	@Test
	@DisplayName("Should allow reuse of a freed unique code value by another entity")
	void shouldReuseUniqueCode() {
		shouldInsertAndThenUpdateNewAttribute();
		this.productIndex.resetDirty();
		this.containerAccessor.reset();

		final AttributeKey attrCode = new AttributeKey(ATTRIBUTE_CODE);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				this.containerAccessor, 2,
				new MockEntityIndexCreator<>(this.productIndex),
				new MockEntityIndexCreator<>(this.catalogIndex),
				() -> this.productSchema,
				this.priceIdSequence::incrementAndGet,
				UNSUPPORTED_OPERATION,
				null,
				null,
				null,
				this.classifierResolver,
				this.usageRegistry,
				this.catalogUsageRegistry
			),
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 2),
			this.productIndex,
			this.productIndex,
			attrCode, "A",
			true, true
		);

		final AttributeKey attrGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				this.containerAccessor, 2,
				new MockEntityIndexCreator<>(this.productIndex),
				new MockEntityIndexCreator<>(this.catalogIndex),
				() -> this.productSchema,
				this.priceIdSequence::incrementAndGet,
				UNSUPPORTED_OPERATION,
				null,
				null,
				null,
				this.classifierResolver,
				this.usageRegistry,
				this.catalogUsageRegistry
			),
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 2),
			this.productIndex,
			this.productIndex,
			attrGlobalCode, "GA",
			true, true
		);

		final AttributeSchema codeSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_CODE);
		final UniqueIndex uniqueIndex = this.productIndex.getUniqueIndex(null, codeSchema, null);
		assertEquals(2, uniqueIndex.getRecordIdByUniqueValue("A"));
		assertEquals(1, uniqueIndex.getRecordIdByUniqueValue("B"));

		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_GLOBAL_CODE);
		final GlobalUniqueIndex globalUniqueIndex = this.catalogIndex.getGlobalUniqueIndex(attributeSchema, null);
		assertEquals(
			new EntityReferenceWithLocale(this.productSchema.getName(), 2, null),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GA", null, this.classifierResolver).orElse(null)
		);
		assertEquals(
			new EntityReferenceWithLocale(this.productSchema.getName(), 1, null),
			globalUniqueIndex.getEntityReferenceByUniqueValue("GB", null, this.classifierResolver).orElse(null)
		);

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(5, trappedChanges1.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.UNIQUE, ATTRIBUTE_GLOBAL_CODE);
		assertContainsChangedPart(trappedChanges1, AttributeIndexType.FILTER, ATTRIBUTE_GLOBAL_CODE);

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		this.catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(2, trappedChanges2.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges2, ATTRIBUTE_GLOBAL_CODE);
	}

	@Test
	@DisplayName("Should remove attribute from unique, filter, and global-unique indices")
	void shouldRemoveAttribute() {
		shouldInsertNewAttribute();
		this.productIndex.resetDirty();

		final AttributeKey attributeCode = new AttributeKey(ATTRIBUTE_CODE);
		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeCode,
				this.productSchema.getAttribute(attributeCode.attributeName()).orElseThrow(),
				attributeValue -> new AttributeValue(attributeCode, "A"));

		executeAttributeRemoval(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			attributeCode,
			true, true
		);

		final AttributeKey attributeGlobalCode = new AttributeKey(ATTRIBUTE_GLOBAL_CODE);
		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(
				attributeGlobalCode, this.productSchema.getAttribute(attributeGlobalCode.attributeName()).orElse(null),
				attributeValue -> new AttributeValue(attributeGlobalCode, "GA"));

		executeAttributeRemoval(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			attributeGlobalCode,
			true, true
		);

		final AttributeSchema codeSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_CODE);
		assertNull(this.productIndex.getUniqueIndex(null, codeSchema, null));
		assertNull(this.productIndex.getFilterIndex(null, codeSchema, null));
		final GlobalAttributeSchema attributeSchema = (GlobalAttributeSchema) this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_GLOBAL_CODE);
		assertNull(this.catalogIndex.getGlobalUniqueIndex(attributeSchema, null));

		final TrappedChanges trappedChanges1 = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges1);
		assertEquals(1, trappedChanges1.getTrappedChangesCount());

		final TrappedChanges trappedChanges2 = new TrappedChanges();

		this.catalogIndex.getModifiedStorageParts(trappedChanges2);
		assertEquals(1, trappedChanges2.getTrappedChangesCount());
	}

	@Test
	@DisplayName("Should apply delta to numeric attribute and update filter and sort indices")
	void shouldApplyDeltaToAttribute() {
		final AttributeKey attrVariantCount = new AttributeKey(ATTRIBUTE_VARIANT_COUNT);
		final AttributeSchema variantSchema = AttributeSchema._internalBuild(ATTRIBUTE_VARIANT_COUNT, Integer.class, false, ConflictResolutionOverride.INHERITED);

		executeAttributeUpsert(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			attrVariantCount, 10,
			false, true
		);
		executeAttributeUpsert(
			new EntityIndexLocalMutationExecutor(
				this.containerAccessor, 2,
				new MockEntityIndexCreator<>(this.productIndex),
				new MockEntityIndexCreator<>(this.catalogIndex),
				() -> this.productSchema,
				this.priceIdSequence::incrementAndGet,
				UNSUPPORTED_OPERATION,
				null,
				null,
				null,
				this.classifierResolver,
				this.usageRegistry,
				this.catalogUsageRegistry
			),
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 2),
			this.productIndex,
			this.productIndex,
			attrVariantCount, 9,
			false, true
		);

		final AttributeSchema variantCountSchema = this.productAttributeSchemaProvider.getAttributeSchema(ATTRIBUTE_VARIANT_COUNT);
		assertNull(this.productIndex.getUniqueIndex(null, variantCountSchema, null));
		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, variantCountSchema, null).getRecordsEqualTo(10).getArray());
		final int position = findInArray(this.productIndex.getSortIndex(null, variantCountSchema, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1);
		assertTrue(position >= 0);

		this.containerAccessor.getAttributeStoragePart(ENTITY_NAME, 1)
			.upsertAttribute(attrVariantCount, variantSchema, attributeValue -> new AttributeValue(attrVariantCount, 10));

		executeAttributeDelta(
			this.executor,
			null,
			this.productAttributeSchemaProvider,
			getEntityAttributeValueSupplier(this.productSchema, 1),
			this.productIndex,
			this.productIndex,
			attrVariantCount, -3
		);

		assertArrayEquals(new int[]{1}, this.productIndex.getFilterIndex(null, variantCountSchema, null).getRecordsEqualTo(7).getArray());
		assertArrayEquals(new int[0], this.productIndex.getFilterIndex(null, variantCountSchema, null).getRecordsEqualTo(10).getArray());
		assertTrue(findInArray(this.productIndex.getSortIndex(null, variantCountSchema, null).getAscendingOrderRecordsSupplier().getSortedRecordIds(), 1) < position);

		final TrappedChanges trappedChanges = new TrappedChanges();

		this.productIndex.getModifiedStorageParts(trappedChanges);
		assertEquals(3, trappedChanges.getTrappedChangesCount());
		assertContainsChangedPart(trappedChanges, AttributeIndexType.FILTER, ATTRIBUTE_VARIANT_COUNT);
		assertContainsChangedPart(trappedChanges, AttributeIndexType.SORT, ATTRIBUTE_VARIANT_COUNT);
	}

	/**
	 * Asserts that the trapped changes contain a storage part of the given type for the specified
	 * non-localized attribute.
	 *
	 * @param trappedChanges tracked storage part modifications
	 * @param type           expected attribute index type (UNIQUE, FILTER, SORT, etc.)
	 * @param attributeName  expected attribute name
	 */
	private static void assertContainsChangedPart(
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull AttributeIndexType type,
		@Nonnull String attributeName
	) {
		assertContainsChangedPart(trappedChanges, type, attributeName, null);
	}

	/**
	 * Asserts that the trapped changes contain a storage part of the given type for the specified
	 * attribute, optionally scoped to a locale.
	 *
	 * @param trappedChanges tracked storage part modifications
	 * @param type           expected attribute index type (UNIQUE, FILTER, SORT, etc.)
	 * @param attributeName  expected attribute name
	 * @param locale         locale scope (null for non-localized attributes)
	 */
	private static void assertContainsChangedPart(
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull AttributeIndexType type,
		@Nonnull String attributeName,
		@Nullable Locale locale
	) {
		final Class<? extends StoragePart> containerType = switch (type) {
			case FILTER -> FilterIndexStoragePart.class;
			case UNIQUE -> UniqueIndexStoragePart.class;
			case SORT -> SortIndexStoragePart.class;
			case CHAIN -> ChainIndexStoragePart.class;
			case CARDINALITY -> AttributeCardinalityIndexStoragePart.class;
		};
		final AttributeIndexKey checkedAttributeKey = new AttributeIndexKey(null, attributeName, locale);
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart changedStoragePart = it.next();
			if (changedStoragePart instanceof final AttributeIndexStoragePart aisp) {
				if (containerType.isInstance(changedStoragePart)) {
					final AttributeIndexKey attributeKey = aisp.getAttributeIndexKey();
					if (checkedAttributeKey.equals(attributeKey)) {
						return;
					}
				}
			}
		}
		fail("Expected " + type + " storage part for attribute " + attributeName + " was not found!");
	}

	/**
	 * Asserts that the trapped changes contain a {@link GlobalUniqueIndexStoragePart} for the
	 * specified attribute name.
	 *
	 * @param trappedChanges tracked storage part modifications
	 * @param attributeName  expected global attribute name
	 */
	private static void assertContainsChangedPart(
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull String attributeName
	) {
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
