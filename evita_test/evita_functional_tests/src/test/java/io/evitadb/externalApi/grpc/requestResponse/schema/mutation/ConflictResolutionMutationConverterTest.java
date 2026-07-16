/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaConflictResolutionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaConflictResolutionOverrideMutation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the schema mutations carrying the conflict resolution setting round-trip through the gRPC delegating
 * mutation converters (Java → Grpc → Java) without dropping the field. Every mutation deliberately carries a NON-default
 * value — a plain compile pass cannot catch a silently-dropped field, only a non-default round-trip assertion can.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
class ConflictResolutionMutationConverterTest {

	@Nonnull
	private static ConflictResolution entityConflictResolution() {
		return new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.PRICE, GranularConflictPolicy.REFERENCE));
	}

	@Nonnull
	private static LocalEntitySchemaMutation entityRoundTrip(@Nonnull LocalEntitySchemaMutation mutation) {
		final DelegatingEntitySchemaMutationConverter converter = DelegatingEntitySchemaMutationConverter.INSTANCE;
		return (LocalEntitySchemaMutation) converter.convert(converter.convert(mutation));
	}

	@Nonnull
	private static LocalCatalogSchemaMutation catalogRoundTrip(@Nonnull LocalCatalogSchemaMutation mutation) {
		final DelegatingLocalCatalogSchemaMutationConverter converter = DelegatingLocalCatalogSchemaMutationConverter.INSTANCE;
		return (LocalCatalogSchemaMutation) converter.convert(converter.convert(mutation));
	}

	@Test
	void shouldRoundTripSetOverrideMutationsThroughEntityConverter() {
		final SetAttributeSchemaConflictResolutionOverrideMutation attributeMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation("code", ConflictResolutionOverride.GRANULAR);
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation associatedDataMutation =
			new SetAssociatedDataSchemaConflictResolutionOverrideMutation("labels", ConflictResolutionOverride.ENTITY);
		final SetReferenceSchemaConflictResolutionOverrideMutation referenceMutation =
			new SetReferenceSchemaConflictResolutionOverrideMutation("brand", ConflictResolutionOverride.ENTITY);

		assertEquals(attributeMutation, entityRoundTrip(attributeMutation));
		assertEquals(associatedDataMutation, entityRoundTrip(associatedDataMutation));
		assertEquals(referenceMutation, entityRoundTrip(referenceMutation));
	}

	@Test
	void shouldRoundTripModifyEntityConflictResolutionMutation() {
		final ModifyEntitySchemaConflictResolutionMutation mutation =
			new ModifyEntitySchemaConflictResolutionMutation(entityConflictResolution());
		assertEquals(mutation, entityRoundTrip(mutation));
	}

	@Test
	void shouldRoundTripCreateMutationsCarryingOverrideThroughEntityConverter() {
		final CreateAttributeSchemaMutation attributeMutation = new CreateAttributeSchemaMutation(
			"code", null, null, null, null, null,
			false, false, false, String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);
		final CreateReferenceSchemaMutation referenceMutation = new CreateReferenceSchemaMutation(
			"brand", null, null, Cardinality.ZERO_OR_ONE, "brand", true, null, false,
			null, null, null, null, null, null,
			ConflictResolutionOverride.ENTITY
		);

		assertEquals(attributeMutation, entityRoundTrip(attributeMutation));
		assertEquals(referenceMutation, entityRoundTrip(referenceMutation));
	}

	@Test
	void shouldRoundTripCatalogConflictMutationsThroughCatalogConverter() {
		final SetAttributeSchemaConflictResolutionOverrideMutation attributeMutation =
			new SetAttributeSchemaConflictResolutionOverrideMutation("code", ConflictResolutionOverride.GRANULAR);
		final ModifyCatalogSchemaConflictResolutionMutation catalogMutation =
			new ModifyCatalogSchemaConflictResolutionMutation(entityConflictResolution());
		final CreateGlobalAttributeSchemaMutation globalAttributeMutation = new CreateGlobalAttributeSchemaMutation(
			"url", null, null, null, null, null, null,
			false, false, false, String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		assertEquals(attributeMutation, catalogRoundTrip(attributeMutation));
		assertEquals(catalogMutation, catalogRoundTrip(catalogMutation));
		assertEquals(globalAttributeMutation, catalogRoundTrip(globalAttributeMutation));
	}
}
