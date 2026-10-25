/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingAttributeSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutationConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cheap wire round trips for normalization descriptions, schema kinds and each mutation dispatcher.
 * No engine or API server is needed to verify order, parameter types and absent-field compatibility.
 */
@Tag(EXTERNAL_API)
@Tag(GRPC)
@Tag(SCHEMA)
class AnalysisStepConverterTest {

	/** Returns empty, built-in and extensible typed descriptions. */
	@Nonnull
	static Stream<List<AnalysisStep>> chains() {
		return Stream.of(
			List.of(),
			List.of(lowercase(), asciiFolding(), removeCharacters("- "), trim()),
			List.of(new AnalysisStep("custom", Map.of(
				"words", new String[]{"one", "two"}, "count", 2, "amount", new BigDecimal("1.20"),
				"enabled", true
			)))
		);
	}

	@ParameterizedTest
	@MethodSource("chains")
	@DisplayName("Preserves declared order and typed parameters through protobuf bytes")
	void shouldRoundTripSteps(@Nonnull List<AnalysisStep> steps) throws Exception {
		final List<GrpcAnalysisStep> wire = AnalysisStepConverter.toGrpcAnalysisSteps(steps);
		for (int i = 0; i < wire.size(); i++) {
			assertEquals(wire.get(i), GrpcAnalysisStep.parseFrom(wire.get(i).toByteArray()));
		}
		assertEquals(steps, AnalysisStepConverter.toAnalysisSteps(wire));
	}

	@ParameterizedTest
	@EnumSource(value = GrpcAttributeSchemaType.class, names = {
		"GLOBAL_SCHEMA", "ENTITY_SCHEMA", "REFERENCE_SCHEMA"
	})
	@DisplayName("Reads declared normalization for every attribute schema kind")
	void shouldReadAttributeSchema(@Nonnull GrpcAttributeSchemaType type) {
		final List<AnalysisStep> steps = List.of(removeCharacters("- "), trim(), lowercase());
		final GrpcAttributeSchema wire = GrpcAttributeSchema.newBuilder()
			.setName("code").setSchemaType(type)
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(String.class))
			.addAllNormalization(AnalysisStepConverter.toGrpcAnalysisSteps(steps)).build();
		assertEquals(steps, EntitySchemaConverter.toAttributeSchema(wire, AttributeSchemaContract.class)
			.getNormalization());
		assertTrue(EntitySchemaConverter.toAttributeSchema(wire.toBuilder().clearNormalization().build(),
			AttributeSchemaContract.class).getNormalization().isEmpty());
		final AttributeSchemaContract schema = EntitySchemaConverter.toAttributeSchema(
			wire, AttributeSchemaContract.class);
		final SetAttributeSchemaNormalizedMutationConverter converter =
			SetAttributeSchemaNormalizedMutationConverter.INSTANCE;
		final SetAttributeSchemaNormalizedMutation replacement = converter.convert(
			GrpcSetAttributeSchemaNormalizedMutation.newBuilder().setName("code")
				.addNormalization(AnalysisStepConverter.toGrpcAnalysisSteps(List.of(asciiFolding())).get(0)).build());
		final AttributeSchemaContract updated = replacement.mutate(null, schema, AttributeSchemaContract.class);
		assertEquals(List.of(asciiFolding()), updated.getNormalization());
		final SetAttributeSchemaNormalizedMutation removal = converter.convert(
			GrpcSetAttributeSchemaNormalizedMutation.newBuilder().setName("code").build());
		assertTrue(removal.mutate(null, updated, AttributeSchemaContract.class).getNormalization().isEmpty());
	}

	@ParameterizedTest
	@MethodSource("chains")
	@DisplayName("Creates entity/reference and global declarations and tolerates omitted normalization")
	void shouldRoundTripCreateMutations(@Nonnull List<AnalysisStep> steps) {
		final GrpcCreateAttributeSchemaMutation wire = GrpcCreateAttributeSchemaMutation.newBuilder()
			.setName("code").setType(EvitaDataTypesConverter.toGrpcEvitaDataType(String.class))
			.addAllNormalization(AnalysisStepConverter.toGrpcAnalysisSteps(steps)).build();
		final CreateAttributeSchemaMutationConverter converter = CreateAttributeSchemaMutationConverter.INSTANCE;
		final CreateAttributeSchemaMutation mutation =
			converter.convert(wire);
		assertEquals(steps, AnalysisStepConverter.toAnalysisSteps(converter.convert(mutation).getNormalizationList()));
		assertEquals(mutation, converter.convert(converter.convert(mutation)));
		final GrpcCreateGlobalAttributeSchemaMutation globalWire = GrpcCreateGlobalAttributeSchemaMutation.newBuilder()
			.setName("code").setType(wire.getType()).addAllNormalization(wire.getNormalizationList()).build();
		final CreateGlobalAttributeSchemaMutationConverter globalConverter =
			CreateGlobalAttributeSchemaMutationConverter.INSTANCE;
		assertEquals(steps, AnalysisStepConverter.toAnalysisSteps(
			globalConverter.convert(globalConverter.convert(globalWire)).getNormalizationList()));
		if (steps.isEmpty()) {
			assertNull(mutation.getNormalization());
			assertNull(globalConverter.convert(globalWire).getNormalization());
		}
	}

	@ParameterizedTest
	@MethodSource("chains")
	@DisplayName("Sets and clears normalization through all three mutation unions")
	void shouldRoundTripSetAndDispatchers(@Nonnull List<AnalysisStep> steps) {
		final SetAttributeSchemaNormalizedMutation mutation = new SetAttributeSchemaNormalizedMutation(
			"code", steps.toArray(AnalysisStep[]::new));
		final SetAttributeSchemaNormalizedMutationConverter converter =
			SetAttributeSchemaNormalizedMutationConverter.INSTANCE;
		assertEquals(mutation, converter.convert(converter.convert(mutation)));
		assertEquals(mutation, DelegatingAttributeSchemaMutationConverter.INSTANCE.convert(
			DelegatingAttributeSchemaMutationConverter.INSTANCE.convert(mutation)));
		assertEquals(mutation, DelegatingEntitySchemaMutationConverter.INSTANCE.convert(
			DelegatingEntitySchemaMutationConverter.INSTANCE.convert(mutation)));
		assertEquals(mutation, DelegatingLocalCatalogSchemaMutationConverter.INSTANCE.convert(
			DelegatingLocalCatalogSchemaMutationConverter.INSTANCE.convert(mutation)));
	}
}
