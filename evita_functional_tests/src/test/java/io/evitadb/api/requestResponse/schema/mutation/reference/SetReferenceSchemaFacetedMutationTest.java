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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link SetReferenceSchemaFacetedMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetReferenceSchemaFacetedMutationTest {

	@Test
	void shouldOverrideFacetedFlagOfPreviousMutationIfNamesMatch() {
		SetReferenceSchemaFacetedMutation mutation = new SetReferenceSchemaFacetedMutation(
			REFERENCE_NAME, Scope.NO_SCOPE
		);
		SetReferenceSchemaFacetedMutation existingMutation = new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetReferenceSchemaFacetedMutation.class, result.current()[0]);
		assertArrayEquals(Scope.NO_SCOPE, ((SetReferenceSchemaFacetedMutation) result.current()[0]).getFacetedInScopes());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		SetReferenceSchemaFacetedMutation mutation = new SetReferenceSchemaFacetedMutation(
			REFERENCE_NAME, Scope.NO_SCOPE
		);
		SetReferenceSchemaFacetedMutation existingMutation = new SetReferenceSchemaFacetedMutation("differentName", Scope.DEFAULT_SCOPES);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateReferenceSchema() {
		SetReferenceSchemaFacetedMutation mutation = new SetReferenceSchemaFacetedMutation(
			REFERENCE_NAME, Scope.NO_SCOPE
		);
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema());
		assertNotNull(mutatedSchema);
		assertArrayEquals(Scope.NO_SCOPE, mutatedSchema.getFacetedInScopes().toArray(Scope[]::new));
	}

	@Test
	void shouldMutateEntitySchema() {
		SetReferenceSchemaFacetedMutation mutation = new SetReferenceSchemaFacetedMutation(
			REFERENCE_NAME, Scope.NO_SCOPE
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final ReferenceSchemaContract newReferenceSchema = newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
		assertArrayEquals(Scope.DEFAULT_SCOPES, newReferenceSchema.getIndexedInScopes().toArray(Scope[]::new));
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
		SetReferenceSchemaFacetedMutation mutation = new SetReferenceSchemaFacetedMutation(
			REFERENCE_NAME, Scope.NO_SCOPE
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
