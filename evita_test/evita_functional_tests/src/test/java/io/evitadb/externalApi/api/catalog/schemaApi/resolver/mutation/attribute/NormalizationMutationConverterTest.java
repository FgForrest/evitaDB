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


package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Cheap common regression coverage of custom normalization serialization and typed mutation input. */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@DisplayName("Normalization mutation converters")
class NormalizationMutationConverterTest {
	private static final List<Map<String, Object>> INPUT = List.of(
		Map.of("lowercase", true), Map.of("asciiFolding", true), Map.of("trim", true),
		Map.of("removeCharacters", Map.of("characters", "- "))
	);
	private static final AnalysisStep[] STEPS = {lowercase(), asciiFolding(), trim(), removeCharacters("- ")};
	private static final List<Map<String, Object>> OUTPUT = List.of(
		Map.of("step", "lowercase", "parameters", Map.of()),
		Map.of("step", "ascii-folding", "parameters", Map.of()),
		Map.of("step", "trim", "parameters", Map.of()),
		Map.of("step", "remove-characters", "parameters", Map.of("characters", "- "))
	);

	/** The three serializable creators carrying the custom AnalysisStep array. */
	enum Kind { CREATE, CREATE_GLOBAL, SET }

	@ParameterizedTest
	@EnumSource(Kind.class)
	@DisplayName("Reads typed steps and writes ordered generic output")
	void shouldConvertNormalization(Kind kind) {
		final Map<String, Object> input = CollectionUtils.createLinkedHashMap(3);
		input.put("name", "code");
		input.put("type", "String");
		input.put("normalization", INPUT);
		assertArrayEquals(STEPS, read(kind, input));
		assertEquals(OUTPUT, write(kind, input).get("normalization"));
	}

	@ParameterizedTest
	@EnumSource(Kind.class)
	@DisplayName("Omitted and empty lists keep legacy requests compatible")
	void shouldAcceptAbsentOrEmptyNormalization(Kind kind) {
		final Map<String, Object> input = CollectionUtils.createLinkedHashMap(3);
		input.put("name", "code");
		input.put("type", "String");
		final AnalysisStep[] absent = read(kind, input);
		if (kind == Kind.SET) {
			assertEquals(0, absent.length);
		} else {
			assertNull(absent);
		}
		assertEquals(kind == Kind.SET ? List.of() : null, write(kind, input).get("normalization"));
		input.put("normalization", List.of());
		assertEquals(0, read(kind, input).length);
		assertEquals(List.of(), write(kind, input).get("normalization"));
	}

	@ParameterizedTest
	@MethodSource("invalidInputs")
	@DisplayName("Rejects malformed one-of input and missing or unknown parameters")
	void shouldRejectInvalidNormalization(Object normalization) {
		assertThrows(EvitaInvalidUsageException.class, () -> read(Kind.SET, Map.of(
			"name", "code", "normalization", normalization
		)));
	}

	/**
	 * Supplies invalid typed selectors without creating an engine or web server.
	 *
	 * @return malformed `normalization` payloads, each of which a converter must reject
	 */
	static Stream<Object> invalidInputs() {
		return Stream.of(
			List.of(Map.of()),
			List.of(Map.of("lowercase", true, "trim", true)),
			List.of(Map.of("lowercase", false)),
			List.of(Map.of("unknown", true)),
			List.of(Map.of("removeCharacters", Map.of())),
			List.of(Map.of("removeCharacters", Map.of("characters", "-", "unknown", "x"))),
			List.of(Map.of("removeCharacters", true)),
			List.of(Map.of("removeCharacters", Map.of("characters", 4))),
			List.of("lowercase"),
			Map.of("lowercase", true)
		);
	}

	/**
	 * Converts through each production converter and returns its declared steps.
	 *
	 * @param kind which attribute mutation converter to run
	 * @param input mutation fields as received from the external API
	 * @return normalization chain declared by the converted mutation
	 */
	private AnalysisStep[] read(Kind kind, Map<String, Object> input) {
		return switch (kind) {
			case CREATE -> createConverter().convertFromInput(input).getNormalization();
			case CREATE_GLOBAL -> globalConverter().convertFromInput(input).getNormalization();
			case SET -> setConverter().convertFromInput(input).getNormalization();
		};
	}

	/**
	 * Exercises reflection-based output after custom fields were pre-serialized.
	 *
	 * @param kind which attribute mutation converter to run
	 * @param input mutation fields converted to a mutation and back
	 * @return the mutation as rendered by the converter output
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> write(Kind kind, Map<String, Object> input) {
		return (Map<String, Object>) switch (kind) {
			case CREATE -> {
				final CreateAttributeSchemaMutationConverter converter = createConverter();
				final CreateAttributeSchemaMutation mutation = converter.convertFromInput(input);
				yield converter.convertToOutput(mutation);
			}
			case CREATE_GLOBAL -> {
				final CreateGlobalAttributeSchemaMutationConverter converter = globalConverter();
				final CreateGlobalAttributeSchemaMutation mutation = converter.convertFromInput(input);
				yield converter.convertToOutput(mutation);
			}
			case SET -> {
				final SetAttributeSchemaNormalizedMutationConverter converter = setConverter();
				final SetAttributeSchemaNormalizedMutation mutation = converter.convertFromInput(input);
				yield converter.convertToOutput(mutation);
			}
		};
	}

	/**
	 * Creates the entity/reference create converter.
	 *
	 * @return converter for entity and reference attribute create mutations
	 */
	private CreateAttributeSchemaMutationConverter createConverter() {
		return new CreateAttributeSchemaMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	/**
	 * Creates the global create converter.
	 *
	 * @return converter for global attribute create mutations
	 */
	private CreateGlobalAttributeSchemaMutationConverter globalConverter() {
		return new CreateGlobalAttributeSchemaMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	/**
	 * Creates the full-statement converter shared by all three attribute owners.
	 *
	 * @return converter for the full-statement normalization mutation
	 */
	private SetAttributeSchemaNormalizedMutationConverter setConverter() {
		return new SetAttributeSchemaNormalizedMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
		);
	}
}
