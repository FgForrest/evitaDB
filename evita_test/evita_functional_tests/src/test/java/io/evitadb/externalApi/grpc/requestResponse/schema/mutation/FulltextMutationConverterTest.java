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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import java.math.BigDecimal;
import com.google.protobuf.StringValue;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.FulltextMutationTestSupport;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture;
import io.evitadb.externalApi.grpc.generated.GrpcCreateFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcFulltextAnalyzerSchema;
import io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.FulltextSchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises protobuf bytes, dispatcher registration and Java-origin CDC payloads for fulltext mutations. */
@Tag(EXTERNAL_API)
@Tag(GRPC)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("Fulltext mutation gRPC round trips and CDC")
class FulltextMutationConverterTest {
	/** Empty repeated fields preserve the exact pre-samples wire bytes for the schema and both mutations. */
	@DisplayName("adds no wire bytes when no author samples are supplied")
	@Test
	void shouldKeepWireBytesWithoutSamples() {
		final String headBytes = "0a06637573746f6d12060a04746578741a00";
		final FulltextAnalyzerSchema schema = FulltextAnalyzerSchema._internalBuild(
			"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of());
		final CreateFulltextAnalyzerSchemaMutation create = new CreateFulltextAnalyzerSchemaMutation(
			"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of());
		final ModifyFulltextAnalyzerSchemaMutation modify = new ModifyFulltextAnalyzerSchemaMutation(
			"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of());
		assertEquals(headBytes, HexFormat.of().formatHex(FulltextSchemaConverter.toGrpcAnalyzer(schema).toByteArray()));
		assertEquals(headBytes, HexFormat.of().formatHex(
			CreateFulltextAnalyzerSchemaMutationConverter.INSTANCE.convert(create).toByteArray()));
		assertEquals(headBytes, HexFormat.of().formatHex(
			ModifyFulltextAnalyzerSchemaMutationConverter.INSTANCE.convert(modify).toByteArray()));
	}

	/** Wire input reaches the same construction-time sample limits as a Java declaration. */
	@DisplayName("Refuses blank and excessive author samples received on the wire")
	@Test
	void shouldRefuseInvalidSamplesFromWire() {
		final List<String> tooMany = new ArrayList<>(FulltextMutationTestSupport.analyzerSamples());
		tooMany.add("overflow");

		assertWireSamplesRefused(List.of(" "), "null or blank");
		assertWireSamplesRefused(tooMany, "more than 100 distinct");
	}

	/**
	 * Converts the samples through the analyzer schema converter and both mutation converters, each of which must
	 * refuse them with the same reason.
	 */
	private static void assertWireSamplesRefused(@Nonnull List<String> samples, @Nonnull String reason) {
		final StringValue basedOn = StringValue.of("text");
		final List<Executable> conversions = List.of(
			() -> FulltextSchemaConverter.toAnalyzer(GrpcFulltextAnalyzerSchema.newBuilder()
				.setName("custom").setBasedOn(basedOn).addAllSamples(samples).build()),
			() -> CreateFulltextAnalyzerSchemaMutationConverter.INSTANCE.convert(
				GrpcCreateFulltextAnalyzerSchemaMutation.newBuilder()
					.setName("custom").setBasedOn(basedOn).addAllSamples(samples).build()),
			() -> ModifyFulltextAnalyzerSchemaMutationConverter.INSTANCE.convert(
				GrpcModifyFulltextAnalyzerSchemaMutation.newBuilder()
					.setName("custom").setBasedOn(basedOn).addAllSamples(samples).build())
		);

		for (final Executable conversion : conversions) {
			final String message = assertThrows(InvalidSchemaMutationException.class, conversion).getMessage();
			assertTrue(message.contains(reason), message);
		}
	}

	/** Verifies actual wire serialization for all create and set mutation families. */
	@DisplayName("Preserves every declared fulltext setting through conversion")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationTestSupport#roundTripMutations")
	void shouldRoundTrip(@Nonnull SchemaMutation mutation) throws Exception {
		if (mutation instanceof EntitySchemaMutation entity) {
			assertEquals(mutation, DelegatingEntitySchemaMutationConverter.INSTANCE.convert(
				GrpcEntitySchemaMutation.parseFrom(DelegatingEntitySchemaMutationConverter.INSTANCE.convert(entity)
					.toByteArray())));
		}
		if (mutation instanceof LocalCatalogSchemaMutation catalog) {
			assertEquals(mutation, DelegatingLocalCatalogSchemaMutationConverter.INSTANCE.convert(
				GrpcLocalCatalogSchemaMutation.parseFrom(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE
					.convert(catalog).toByteArray())));
		}
	}

	/** Verifies that Java-origin CDC bodies survive protobuf serialization with every fulltext setting. */
	@DisplayName("Preserves Java-origin CDC bodies through protobuf bytes")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationTestSupport#roundTripMutations")
	void shouldDeliverJavaOriginCapture(@Nonnull SchemaMutation mutation) throws Exception {
		final ChangeCatalogCapture capture = new ChangeCatalogCapture(
			UUID.randomUUID(), 5L, 2, OffsetDateTime.now(), CaptureArea.SCHEMA,
			mutation instanceof LocalCatalogSchemaMutation ? null : "owner", null, Operation.UPSERT, mutation);
		final GrpcChangeCatalogCapture wire = GrpcChangeCatalogCapture.parseFrom(
			ChangeCaptureConverter.toGrpcChangeCatalogCapture(capture, null).toByteArray());
		assertTrue(
			mutation instanceof EntitySchemaMutation ? wire.hasSchemaMutation() : wire.hasCatalogSchemaMutation()
		);
		assertEquals(capture, ChangeCaptureConverter.toChangeCatalogCapture(wire, capture.catalogId()));
	}

	/**
	 * Catalog-level schema bodies travel in their own capture field; every mutation the catalog converter can
	 * encode must also decode, otherwise the client fails on a capture the server delivered.
	 */
	@DisplayName("Delivers catalog evolution mode changes as capture bodies")
	@Test
	void shouldDeliverCatalogEvolutionModeCapture() throws Exception {
		shouldDeliverJavaOriginCapture(
			new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES)
		);
		shouldDeliverJavaOriginCapture(
			new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES)
		);
	}

	/** Exercises the attribute oneof used inside a reference attribute wrapper. */
	@DisplayName("Preserves the reference attribute mutation wrapper")
	@Test
	void shouldRoundTripReferenceAttribute() throws Exception {
		shouldRoundTrip(new ModifyReferenceAttributeSchemaMutation("related",
			new SetAttributeSchemaSearchableMutation("text", Scope.DEFAULT_SCOPES, "text", 73, BigDecimal.ZERO)));
	}
}
