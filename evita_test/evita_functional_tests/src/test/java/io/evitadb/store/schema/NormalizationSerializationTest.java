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

package io.evitadb.store.schema;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator.SUBSTRING_SEARCH;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises normalization through the registered schema and WAL serializers, including runtime parameter types
 * and deterministic encoding independent of parameter insertion order.
 */
@DisplayName("Attribute normalization persistence")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(SCHEMA)
class NormalizationSerializationTest {

	/**
	 * Supplies empty, built-in and parameterized chains for every persisted schema shape.
	 */
	@Nonnull
	static Stream<Arguments> schemaCases() {
		return normalizations().flatMap(steps -> Stream.of(
			AttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.ENTITY, steps),
			EntityAttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.ENTITY, steps),
			GlobalAttributeSchema._internalBuild("code", String.class, false, ConflictResolutionOverride.ENTITY, steps)
		).map(schema -> Arguments.of(schema.getClass().getSimpleName(), schema)));
	}

	/**
	 * Supplies absent, explicitly empty, built-in and parameterized statements for create mutations.
	 */
	@Nonnull
	static Stream<Arguments> mutationCases() {
		return Stream.concat(
			Stream.of(Arguments.of("absent", null)),
			normalizations().map(steps -> Arguments.of(steps.toString(), steps.toArray(AnalysisStep[]::new)))
		);
	}

	/**
	 * Supplies chains whose order and parameter values must survive persistence.
	 */
	@Nonnull
	private static Stream<List<AnalysisStep>> normalizations() {
		return Stream.of(
			List.of(),
			List.of(lowercase(), asciiFolding(), trim(), removeCharacters("- ")),
			List.of(step("custom", parameter("limit", 3), parameter("characters", new String[]{"-", "/"})))
		);
	}

	/**
	 * Covers all three distinct schema serializers through their production registration chains.
	 */
	@Nested
	@DisplayName("Schema serializers")
	class SchemaSerializers {
		/**
		 * Verifies the complete schema and its ordered normalization, including empty chains.
		 */
		@ParameterizedTest(name = "{0}: {1}")
		@MethodSource("io.evitadb.store.schema.NormalizationSerializationTest#schemaCases")
		@DisplayName("round-trips reference, entity and global attribute normalization")
		void shouldRoundTripNormalization(@Nonnull String type, @Nonnull AttributeSchema schema) {
			final Kryo kryo = createSchemaKryo();
			final AttributeSchema restored = roundTrip(kryo, schema);
			assertEquals(schema, restored);
			assertEquals(schema.getNormalization(), restored.getNormalization());
			assertEquals(schema.getClass(), restored.getClass());
			assertThrows(IllegalArgumentException.class, () -> kryo.getRegistration(AnalysisStep.class));
		}
	}

	/**
	 * Covers both nullable create payloads and the full normalization replacement statement.
	 */
	@Nested
	@Tag(WAL)
	@DisplayName("WAL mutation serializers")
	class WalSerializers {
		/**
		 * Verifies null, empty and populated create-attribute statements alongside preceding accelerator fields.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.store.schema.NormalizationSerializationTest#mutationCases")
		@DisplayName("round-trips create attribute normalization")
		void shouldRoundTripCreateAttribute(@Nonnull String description, @Nullable AnalysisStep[] steps) {
			final CreateAttributeSchemaMutation mutation = createAttributeMutation(steps);
			final CreateAttributeSchemaMutation restored = roundTrip(createWalKryo(), mutation);
			assertEquals(mutation, restored);
			assertArrayEquals(steps, restored.getNormalization());
		}

		/**
		 * Verifies null, empty and populated global-create statements, retaining the nullable field's presence.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.store.schema.NormalizationSerializationTest#mutationCases")
		@DisplayName("round-trips create global attribute normalization")
		void shouldRoundTripCreateGlobalAttribute(@Nonnull String description, @Nullable AnalysisStep[] steps) {
			final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
				"code", "description", "deprecated", null, null,
				Scope.DEFAULT_SCOPES,
				new ScopedAttributeFilterAccelerators[]{
					new ScopedAttributeFilterAccelerators(Scope.LIVE, SUBSTRING_SEARCH)
				},
				Scope.DEFAULT_SCOPES, Scope.DEFAULT_SCOPES,
				true, true, true, String.class, "default", 0, ConflictResolutionOverride.ENTITY, steps
			);
			final CreateGlobalAttributeSchemaMutation restored = roundTrip(createWalKryo(), mutation);
			assertEquals(mutation, restored);
			assertArrayEquals(steps, restored.getNormalization());
		}

		/**
		 * Verifies the new mutation's registration and that an absent or empty chain remains a clearing statement.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.store.schema.NormalizationSerializationTest#mutationCases")
		@DisplayName("round-trips set normalized mutation including withdrawal")
		void shouldRoundTripSetNormalized(@Nonnull String description, @Nullable AnalysisStep[] steps) {
			final Kryo kryo = createWalKryo();
			final SetAttributeSchemaNormalizedMutation mutation = new SetAttributeSchemaNormalizedMutation("code", steps);
			final SetAttributeSchemaNormalizedMutation restored = roundTrip(kryo, mutation);
			assertEquals(mutation, restored);
			assertArrayEquals(steps == null ? new AnalysisStep[0] : steps, restored.getNormalization());
			assertThrows(IllegalArgumentException.class, () -> kryo.getRegistration(AnalysisStep.class));
		}
	}

	/**
	 * Proves the persisted parameter order directly; a round trip alone cannot detect nondeterministic bytes.
	 */
	@Test
	@DisplayName("writes parameter names in sorted order for both persistence paths")
	void shouldWriteParametersDeterministically() {
		final AnalysisStep forward = step("custom", parameter("alpha", 1), parameter("zeta", "value"));
		final AnalysisStep reverse = step("custom", parameter("zeta", "value"), parameter("alpha", 1));
		assertEquals(forward, reverse);
		assertArrayEquals(
			serialize(createSchemaKryo(), AttributeSchema._internalBuild(
				"code", String.class, false, ConflictResolutionOverride.ENTITY, List.of(forward)
			)),
			serialize(createSchemaKryo(), AttributeSchema._internalBuild(
				"code", String.class, false, ConflictResolutionOverride.ENTITY, List.of(reverse)
			))
		);
		assertArrayEquals(
			serialize(createWalKryo(), createAttributeMutation(new AnalysisStep[]{forward})),
			serialize(createWalKryo(), createAttributeMutation(new AnalysisStep[]{reverse}))
		);
	}

	/**
	 * Builds a create mutation with non-default preceding fields to expose any field-order mismatch.
	 */
	@Nonnull
	private static CreateAttributeSchemaMutation createAttributeMutation(@Nullable AnalysisStep[] steps) {
		return new CreateAttributeSchemaMutation(
			"code", "description", "deprecated", null,
			Scope.DEFAULT_SCOPES,
			new ScopedAttributeFilterAccelerators[]{
				new ScopedAttributeFilterAccelerators(Scope.LIVE, SUBSTRING_SEARCH)
			},
			Scope.DEFAULT_SCOPES, Scope.DEFAULT_SCOPES,
			true, true, true, String.class, "default", 0, ConflictResolutionOverride.ENTITY, steps
		);
	}

	/**
	 * Creates the same Kryo registration chain used by schema persistence.
	 */
	@Nonnull
	private static Kryo createSchemaKryo() {
		return KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
	}

	/**
	 * Creates the production WAL registration chain, including the appended normalization mutation.
	 */
	@Nonnull
	private static Kryo createWalKryo() {
		return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	}

	/**
	 * Returns bytes written through the registered serializer with its class and serial-version routing.
	 */
	@Nonnull
	private static byte[] serialize(@Nonnull Kryo kryo, @Nonnull Object value) {
		try (final Output output = new Output(512, -1)) {
			kryo.writeClassAndObject(output, value);
			return output.toBytes();
		}
	}

	/**
	 * Reads a registered serializer's output and checks that it consumed the entire record.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(@Nonnull Kryo kryo, @Nonnull T value) {
		final byte[] bytes = serialize(kryo, value);
		try (final Input input = new Input(bytes)) {
			final T restored = (T) kryo.readClassAndObject(input);
			assertEquals(bytes.length, input.position());
			return restored;
		}
	}
}
