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


package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests the ordered declared normalization output without booting a server or consulting index policy. */
@Tag(EXTERNAL_API)
@Tag(REST)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@DisplayName("REST attribute normalization serialization")
class SchemaJsonSerializerNormalizationTest {

	/**
	 * Provides empty, built-in and future typed parameter payloads.
	 *
	 * @return chains to render, one per test invocation
	 */
	@Nonnull
	static Stream<List<AnalysisStep>> chains() {
		return Stream.of(
			List.of(),
			List.of(removeCharacters("- "), trim(), lowercase(), asciiFolding()),
			List.of(new AnalysisStep("custom", Map.of("limit", 3, "enabled", true, "values", new String[]{"a", "b"})))
		);
	}

	@ParameterizedTest
	@MethodSource("chains")
	@DisplayName("renders step order and typed parameter payloads, including an empty chain")
	void shouldRenderDeclaredNormalization(@Nonnull List<AnalysisStep> chain) {
		final AttributeSchemaContract attribute = AttributeSchema._internalBuild(
			"code", String.class, false, ConflictResolutionOverride.INHERITED, chain
		);
		final ArrayNode output = new TestSerializer().render(attribute);
		assertEquals(chain.size(), output.size());
		final ObjectMapper mapper = new ObjectMapper();
		for (int i = 0; i < chain.size(); i++) {
			assertEquals(chain.get(i).name(), output.get(i).get("step").asText());
			assertEquals(mapper.valueToTree(chain.get(i).parameters()), output.get(i).get("parameters"));
		}
	}

	/** Exposes the shared protected renderer through the same serializer seam as the accelerator test. */
	private static final class TestSerializer extends SchemaJsonSerializer {
		/** Creates a serializer with the standard JSON representation. */
		private TestSerializer() {
			super(new ObjectJsonSerializer(new ObjectMapper()));
		}

		/**
		 * Returns the declared step list as JSON.
		 *
		 * @param attribute attribute schema whose declared normalization is rendered
		 * @return JSON array with one object per step, in declaration order
		 */
		@Nonnull
		private ArrayNode render(@Nonnull AttributeSchemaContract attribute) {
			return serializeNormalization(attribute);
		}
	}
}
