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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link RemoveReferenceSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RemoveReferenceSchemaMutationTest {

	@Test
	void shouldRemovePreviousCreateMutationWithSameName() {
		RemoveReferenceSchemaMutation mutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		CreateReferenceSchemaMutation previousMutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"description", "deprecationNotice",
			Cardinality.EXACTLY_ONE, "brand", false,
			null, false,
			false, false
		);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
		);
		assertNotNull(result);
		assertTrue(result.discarded());
		assertNull(result.origin());
		assertNotNull(result.current());
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentReference() {
		RemoveReferenceSchemaMutation mutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		CreateReferenceSchemaMutation previousMutation = new CreateReferenceSchemaMutation(
			"differentName",
			"differentDescription", "deprecationNotice",
			Cardinality.EXACTLY_ONE, "brand", false,
			null, false,
			false, false
		);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation));
	}

	@Test
	void shouldRemoveReference() {
		RemoveReferenceSchemaMutation mutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		final ReferenceSchemaContract associatedSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema());
		assertNull(associatedSchema);
	}

	@Test
	void shouldRemoveReferenceInEntity() {
		RemoveReferenceSchemaMutation mutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME))
			.thenReturn(of(createExistingReferenceSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		assertFalse(newEntitySchema.getReference(REFERENCE_NAME).isPresent());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
		RemoveReferenceSchemaMutation mutation = new RemoveReferenceSchemaMutation(REFERENCE_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME))
			.thenReturn(empty());
		final EntitySchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertEquals(1, mutatedSchema.version());
	}

}
