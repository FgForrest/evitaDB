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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateAssociatedDataSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class CreateAssociatedDataSchemaMutationTest {
	static final String ASSOCIATED_DATA_NAME = "name";

	@Nonnull
	static AssociatedDataSchemaContract createExistingAssociatedDataSchema() {
		return AssociatedDataSchema._internalBuild(
			ASSOCIATED_DATA_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			Integer.class,
			false,
			false
		);
	}

	@Test
	void shouldThrowExceptionWhenInvalidTypeIsProvided() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", Serializable.class, true, true
			)
		);
	}

	@Test
	void shouldBeReplacedWithIndividualMutationsWhenAssociatedDataWasRemovedAndCreatedWithDifferentSettings() {
		CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
			.thenReturn(
				of(createExistingAssociatedDataSchema())
			);
		RemoveAssociatedDataSchemaMutation removeMutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(5, result.current().length);
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAssociatedDataSchemaDescriptionMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAssociatedDataSchemaDeprecationNoticeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAssociatedDataSchemaTypeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAssociatedDataSchemaLocalizedMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAssociatedDataSchemaNullableMutation));
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAssociateData() {
		CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		RemoveAssociatedDataSchemaMutation removeMutation = new RemoveAssociatedDataSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, null, removeMutation));
	}

	@Test
	void shouldCreateAssociatedData() {
		CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		final AssociatedDataSchemaContract associatedSchema = mutation.mutate(null);
		assertNotNull(associatedSchema);
		assertEquals(ASSOCIATED_DATA_NAME, associatedSchema.getName());
		assertEquals("description", associatedSchema.getDescription());
		assertEquals("deprecationNotice", associatedSchema.getDeprecationNotice());
		assertEquals(String.class, associatedSchema.getType());
		assertTrue(associatedSchema.isLocalized());
		assertTrue(associatedSchema.isNullable());
	}

	@Test
	void shouldCreateAssociatedDataInEntity() {
		CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		final AssociatedDataSchemaContract associatedSchema = newEntitySchema.getAssociatedData(ASSOCIATED_DATA_NAME).orElseThrow();
		assertNotNull(associatedSchema);
		assertEquals(ASSOCIATED_DATA_NAME, associatedSchema.getName());
		assertEquals("description", associatedSchema.getDescription());
		assertEquals("deprecationNotice", associatedSchema.getDeprecationNotice());
		assertEquals(String.class, associatedSchema.getType());
		assertTrue(associatedSchema.isLocalized());
		assertTrue(associatedSchema.isNullable());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAssociatedData() {
		CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
					.thenReturn(of(createExistingAssociatedDataSchema()));
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			}
		);
	}

}
