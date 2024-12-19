/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.ATTRIBUTE_COMPOUND_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createExistingAttributeCompoundSchema;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createMockedReferenceSchema;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link RemoveSortableAttributeCompoundSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class RemoveSortableAttributeCompoundSchemaMutationTest {

	@Test
	void shouldRemovePreviousCreateMutationWithSameName() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		CreateSortableAttributeCompoundSchemaMutation previousMutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
		);
		assertNotNull(result);
		assertTrue(result.discarded());
		assertNull(result.origin());
		assertNotNull(result.current());
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		CreateAttributeSchemaMutation previousMutation = new CreateAttributeSchemaMutation(
			"differentName", "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class),
				previousMutation
			)
		);
	}

	@Test
	void shouldRemoveEntityAttribute() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final SortableAttributeCompoundSchemaContract attributeSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			Mockito.mock(ReferenceSchemaContract.class),
			createExistingAttributeCompoundSchema()
		);
		assertNull(attributeSchema);
	}

	@Test
	void shouldRemoveAttributeInEntity() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
			.thenReturn(of(createExistingAttributeCompoundSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		assertFalse(newEntitySchema.getAttribute(ATTRIBUTE_COMPOUND_NAME).isPresent());
	}

	@Test
	void shouldRemoveAttributeInReference() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
			.thenReturn(of(createExistingAttributeCompoundSchema()));
		final ReferenceSchemaContract newReferenceSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema);
		assertNotNull(newReferenceSchema);
		assertFalse(newReferenceSchema.getAttribute(ATTRIBUTE_COMPOUND_NAME).isPresent());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		RemoveSortableAttributeCompoundSchemaMutation mutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_COMPOUND_NAME))
			.thenReturn(empty());
		final EntitySchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertEquals(1, mutatedSchema.version());
	}

}
