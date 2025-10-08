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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateReferenceSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CreateReflectedReferenceSchemaMutationTest {

	static final String REFERENCE_NAME = "categories";
	static final String REFERENCE_TYPE = "category";
	static final String GROUP_TYPE = "group";
	static final String REFERENCE_ATTRIBUTE_PRIORITY = "priority";
	static final String REFERENCE_ATTRIBUTE_QUANTITY = "quantity";
	static final String REFERENCE_ATTRIBUTE_COMPOUND = "priority";

	@Nonnull
	static ReferenceSchemaContract createExistingReferenceSchema() {
		return createExistingReferenceSchema(true);
	}

	@Nonnull
	static ReferenceSchemaContract createExistingReferenceSchema(boolean indexed) {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			REFERENCE_TYPE,
			"oldDescription",
			"oldDeprecationNotice",
			false,
			Cardinality.ZERO_OR_MORE,
			GROUP_TYPE,
			false,
			indexed ? new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) } : ScopedReferenceIndexType.EMPTY,
			indexed ? new Scope[] {Scope.LIVE} : Scope.NO_SCOPE,
			Map.of(
				REFERENCE_ATTRIBUTE_PRIORITY,
				AttributeSchema._internalBuild(
					REFERENCE_ATTRIBUTE_PRIORITY,
					"oldDescription",
					"oldDeprecationNotice",
					new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
					},
					Scope.NO_SCOPE,
					Scope.NO_SCOPE,
					false,
					false,
					false,
					Integer.class,
					null,
					2
				),
				REFERENCE_ATTRIBUTE_QUANTITY,
				AttributeSchema._internalBuild(
					REFERENCE_ATTRIBUTE_QUANTITY,
					"oldDescription",
					"oldDeprecationNotice",
					new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
					},
					Scope.NO_SCOPE,
					Scope.NO_SCOPE,
					false,
					false,
					false,
					Integer.class,
					null,
					2
				)
			),
			Map.of(
				REFERENCE_ATTRIBUTE_COMPOUND,
				SortableAttributeCompoundSchema._internalBuild(
					REFERENCE_ATTRIBUTE_COMPOUND,
					"oldDescription",
					"oldDeprecationNotice",
					new Scope[] { Scope.LIVE },
					List.of(
						new AttributeElement(REFERENCE_ATTRIBUTE_PRIORITY, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement(REFERENCE_ATTRIBUTE_QUANTITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
				)
			)
		);
	}

	@Test
	void shouldThrowExceptionWhenInvalidNameIsProvided() {
		assertThrows(
			InvalidClassifierFormatException.class,
			() -> new CreateReferenceSchemaMutation(
				"primaryKey", "description", "deprecationNotice",
				Cardinality.ZERO_OR_ONE, REFERENCE_TYPE, false,
				null, false,
				false, false
			)
		);
	}

	@Test
	void shouldBeReplacedWithIndividualMutationsWhenReferenceWasRemovedAndCreatedWithDifferentSettings() {
		CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"description", "deprecationNotice",
			Cardinality.EXACTLY_ONE, "brand", false,
			null, false,
			false, false
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME))
			.thenReturn(
				of(createExistingReferenceSchema())
			);
		RemoveReferenceSchemaMutation removeMutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(10, result.current().length);
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSchemaDescriptionMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSchemaDeprecationNoticeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSchemaCardinalityMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSchemaRelatedEntityMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSchemaRelatedEntityGroupMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetReferenceSchemaIndexedMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetReferenceSchemaFacetedMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceAttributeSchemaMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyReferenceSortableAttributeCompoundSchemaMutation));
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentReferenceata() {
		CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			Cardinality.ZERO_OR_MORE,
			REFERENCE_TYPE,
			false,
			GROUP_TYPE,
			false,
			true,
			true
		);
		RemoveReferenceSchemaMutation removeMutation = new RemoveReferenceSchemaMutation("differentName");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), removeMutation));
	}

	@Test
	void shouldCreateReference() {
		CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"description",
			"deprecationNotice",
			Cardinality.ZERO_OR_MORE,
			REFERENCE_TYPE,
			false,
			GROUP_TYPE,
			false,
			true,
			true
		);
		final ReferenceSchemaContract referenceSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);
		assertNotNull(referenceSchema);
		assertEquals(REFERENCE_NAME, referenceSchema.getName());
		assertEquals("description", referenceSchema.getDescription());
		assertEquals("deprecationNotice", referenceSchema.getDeprecationNotice());
		assertEquals(Cardinality.ZERO_OR_MORE, referenceSchema.getCardinality());
		assertEquals(REFERENCE_TYPE, referenceSchema.getReferencedEntityType());
		assertEquals(GROUP_TYPE, referenceSchema.getReferencedGroupType());
		assertFalse(referenceSchema.isReferencedEntityTypeManaged());
		assertFalse(referenceSchema.isReferencedGroupTypeManaged());
		assertTrue(referenceSchema.isIndexed());
		assertTrue(referenceSchema.isFaceted());
	}

	@Test
	void shouldCreateReferenceInEntity() {
		CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"description",
			"deprecationNotice",
			Cardinality.ZERO_OR_MORE,
			REFERENCE_TYPE,
			false,
			GROUP_TYPE,
			false,
			true,
			true
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		final ReferenceSchemaContract referenceSchema = newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
		assertNotNull(referenceSchema);
		assertEquals(REFERENCE_NAME, referenceSchema.getName());
		assertEquals("description", referenceSchema.getDescription());
		assertEquals("deprecationNotice", referenceSchema.getDeprecationNotice());
		assertEquals(Cardinality.ZERO_OR_MORE, referenceSchema.getCardinality());
		assertEquals(REFERENCE_TYPE, referenceSchema.getReferencedEntityType());
		assertEquals(GROUP_TYPE, referenceSchema.getReferencedGroupType());
		assertFalse(referenceSchema.isReferencedEntityTypeManaged());
		assertFalse(referenceSchema.isReferencedGroupTypeManaged());
		assertTrue(referenceSchema.isIndexed());
		assertTrue(referenceSchema.isFaceted());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingReference() {
		CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			Cardinality.ZERO_OR_MORE,
			REFERENCE_TYPE,
			false,
			GROUP_TYPE,
			false,
			true,
			true
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				Mockito.when(entitySchema.getReference(REFERENCE_NAME))
					.thenReturn(of(createExistingReferenceSchema()));
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			}
		);
	}

}
