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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import static io.evitadb.index.mutation.ReferenceIndexMutator.attributeUpdate;
import static io.evitadb.index.mutation.ReferenceIndexMutator.referenceInsert;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies contract of {@link ReferenceIndexMutator} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ReferenceIndexMutatorTest extends AbstractMutatorTestBase {
	private static final String ATTRIBUTE_BRAND_CODE = "brandCode";
	private static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	private static final String ATTRIBUTE_VARIANT_COUNT = "variantCount";
	private static final String ATTRIBUTE_CHAR_ARRAY = "charArray";
	private final EntityIndex entityIndex = new GlobalEntityIndex(1, new EntityIndexKey(EntityIndexType.GLOBAL), () -> productSchema);
	private final ReferencedTypeEntityIndex referenceTypesIndex = new ReferencedTypeEntityIndex(1, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Entities.BRAND), () -> productSchema);

	@Override
	protected void alterCatalogSchema(CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		// do nothing
	}

	@Override
	protected void alterProductSchema(EntitySchemaEditor.EntitySchemaBuilder schema) {
		schema.withReferenceTo(
			Entities.BRAND,
			Entities.BRAND,
			Cardinality.ZERO_OR_ONE,
			thatIs -> {
				thatIs.withAttribute(ATTRIBUTE_BRAND_CODE, String.class, whichIs -> whichIs.unique());
				thatIs.withAttribute(ATTRIBUTE_BRAND_EAN, String.class, whichIs -> whichIs.filterable());
				thatIs.withAttribute(ATTRIBUTE_VARIANT_COUNT, Integer.class, whichIs -> whichIs.sortable().filterable());
				thatIs.withAttribute(ATTRIBUTE_CHAR_ARRAY, Character[].class, whichIs -> whichIs.filterable());
			});
	}

	@Test
	void shouldInsertNewReference() {
		final ReferenceKey referenceKey = new ReferenceKey(Entities.BRAND, 10);
		final EntityIndex referenceIndex = new GlobalEntityIndex(2, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, referenceKey), () -> productSchema);
		referenceInsert(
			1, ENTITY_NAME, executor, entityIndex, referenceTypesIndex, referenceIndex, referenceKey
		);
		assertArrayEquals(new int[]{10}, referenceTypesIndex.getAllPrimaryKeys().getArray());
		assertArrayEquals(new int[]{1}, referenceIndex.getAllPrimaryKeys().getArray());
	}

	@Test
	void shouldIndexAttributes() {
		final ReferenceKey referenceKey = new ReferenceKey(Entities.BRAND, 10);
		final EntityIndex referenceIndex = new GlobalEntityIndex(2, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, referenceKey), () -> productSchema);
		referenceInsert(
			1, ENTITY_NAME, executor, entityIndex, referenceTypesIndex, referenceIndex, referenceKey
		);
		final ReferenceAttributeMutation referenceMutation = new ReferenceAttributeMutation(referenceKey, new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_VARIANT_COUNT), 55));
		attributeUpdate(
			1, ENTITY_NAME, executor, referenceTypesIndex, referenceIndex, referenceMutation.getReferenceKey(), referenceMutation.getAttributeMutation()
		);
		final ReferenceAttributeMutation a = new ReferenceAttributeMutation(referenceKey, new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_BRAND_CODE), "A"));
		attributeUpdate(
			1, ENTITY_NAME, executor, referenceTypesIndex, referenceIndex, a.getReferenceKey(), a.getAttributeMutation()
		);
		final ReferenceAttributeMutation referenceMutation1 = new ReferenceAttributeMutation(referenceKey, new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_BRAND_EAN), "EAN-001"));
		attributeUpdate(
			1, ENTITY_NAME, executor, referenceTypesIndex, referenceIndex, referenceMutation1.getReferenceKey(), referenceMutation1.getAttributeMutation()
		);

		assertArrayEquals(new int[]{10}, referenceTypesIndex.getAllPrimaryKeys().getArray());
		assertArrayEquals(new int[]{1}, referenceIndex.getAllPrimaryKeys().getArray());

		AttributeSchemaContract brandCodeSchema = AttributeSchema._internalBuild(ATTRIBUTE_BRAND_CODE, String.class, false);
		assertEquals(10, referenceTypesIndex.getUniqueIndex(brandCodeSchema, null).getRecordIdByUniqueValue("A"));
		assertArrayEquals(new int[]{10}, referenceTypesIndex.getFilterIndex(ATTRIBUTE_BRAND_EAN, null).getRecordsEqualTo("EAN-001").getArray());
		assertEquals(1, referenceIndex.getUniqueIndex(brandCodeSchema, null).getRecordIdByUniqueValue("A"));
		assertArrayEquals(new int[]{1}, referenceIndex.getFilterIndex(ATTRIBUTE_BRAND_EAN, null).getRecordsEqualTo("EAN-001").getArray());
	}

}