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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateSortableAttributeCompoundSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CreateSortableAttributeCompoundSchemaMutationTest {

	static final String ATTRIBUTE_COMPOUND_NAME = "name";

	@Nonnull
	static EntitySortableAttributeCompoundSchemaContract createExistingAttributeCompoundSchema() {
		return EntitySortableAttributeCompoundSchema._internalBuild(
			ATTRIBUTE_COMPOUND_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			new Scope[] { Scope.LIVE },
			List.of(
				new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
				new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			)
		);
	}

	@Nonnull
	static ReferenceSchemaContract createMockedReferenceSchema() {
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchema.class);
		Mockito.when(referenceSchema.getName()).thenReturn("referenceName");
		Mockito.when(referenceSchema.getReferencedEntityType()).thenReturn("abd");
		return referenceSchema;
	}

	@Test
	void shouldBeReplacedWithIndividualMutationsWhenAttributeWasRemovedAndCreatedWithDifferentSettings() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
			.thenReturn(
				of(createExistingAttributeCompoundSchema())
			);
		RemoveSortableAttributeCompoundSchemaMutation removeMutation = new RemoveSortableAttributeCompoundSchemaMutation(ATTRIBUTE_COMPOUND_NAME);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
		);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(2, result.current().length);
		assertTrue(Arrays.stream(result.current())
			           .anyMatch(ModifySortableAttributeCompoundSchemaDescriptionMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current())
			           .anyMatch(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class::isInstance));
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentCompoundData() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, null, removeMutation));
	}

	@Test
	void shouldCreateSortableAttributeCompound() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		final SortableAttributeCompoundSchemaContract compoundSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			Mockito.mock(ReferenceSchemaContract.class),
			(SortableAttributeCompoundSchemaContract) null
		);
		assertNotNull(compoundSchema);
		assertEquals(ATTRIBUTE_COMPOUND_NAME, compoundSchema.getName());
		assertEquals("description", compoundSchema.getDescription());
		assertEquals("deprecationNotice", compoundSchema.getDeprecationNotice());
		final List<AttributeElement> attributeElements = compoundSchema.getAttributeElements();
		assertEquals(2, attributeElements.size());
		assertEquals(new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST), attributeElements.get(0));
		assertEquals(new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST), attributeElements.get(1));
	}

	@Test
	void shouldCreateAttributeInEntity() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		final SortableAttributeCompoundSchemaContract compoundSchema = newEntitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
		assertNotNull(compoundSchema);
		assertEquals(ATTRIBUTE_COMPOUND_NAME, compoundSchema.getName());
		assertEquals("description", compoundSchema.getDescription());
		assertEquals("deprecationNotice", compoundSchema.getDeprecationNotice());
		final List<AttributeElement> attributeElements = compoundSchema.getAttributeElements();
		assertEquals(2, attributeElements.size());
		assertEquals(new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST), attributeElements.get(0));
		assertEquals(new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST), attributeElements.get(1));
	}

	@Test
	void shouldCreateAttributeInReference() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		final ReferenceSchemaContract newReferenceSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			referenceSchema
		);
		assertNotNull(newReferenceSchema);
		final SortableAttributeCompoundSchemaContract compoundSchema = newReferenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
		assertNotNull(compoundSchema);
		assertEquals(ATTRIBUTE_COMPOUND_NAME, compoundSchema.getName());
		assertEquals("description", compoundSchema.getDescription());
		assertEquals("deprecationNotice", compoundSchema.getDeprecationNotice());
		final List<AttributeElement> attributeElements = compoundSchema.getAttributeElements();
		assertEquals(2, attributeElements.size());
		assertEquals(new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST), attributeElements.get(0));
		assertEquals(new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST), attributeElements.get(1));
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAttribute() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
					.thenReturn(
						of(createExistingAttributeCompoundSchema())
					);
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			}
		);
	}

	@Test
	void shouldThrowExceptionWhenMutatingReferenceSchemaWithExistingAttribute() {
		CreateSortableAttributeCompoundSchemaMutation mutation = new CreateSortableAttributeCompoundSchemaMutation(
			ATTRIBUTE_COMPOUND_NAME, "description", "deprecationNotice", new Scope[] { Scope.LIVE },
			new AttributeElement("A", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("B", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
				Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME))
					.thenReturn(
						of(createExistingAttributeCompoundSchema())
					);
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema);
			}
		);
	}

}
