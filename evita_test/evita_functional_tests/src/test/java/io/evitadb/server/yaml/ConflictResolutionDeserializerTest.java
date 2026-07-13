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

package io.evitadb.server.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.evitadb.test.TestTags.SERVER;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ConflictResolutionDeserializer} accepts both the deprecated flat-list configuration
 * form and the current object form, and that a {@link ConflictResolution} round-trips through a bare mapper
 * (the shape used by the server configuration dump).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictResolutionDeserializer")
@Tag(SERVER)
@Tag(TRANSACTION)
class ConflictResolutionDeserializerTest {

	/**
	 * Builds a YAML mapper wired exactly like the server configuration parser: the custom deserializer
	 * registered for {@link ConflictResolution}.
	 */
	private static ObjectMapper yamlMapper() {
		final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
		final SimpleModule module = new SimpleModule();
		module.addDeserializer(ConflictResolution.class, new ConflictResolutionDeserializer());
		mapper.registerModule(module);
		return mapper;
	}

	@Test
	@DisplayName("should parse deprecated flat list with granular members")
	void shouldParseLegacyListWithGranularMembers() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("[ENTITY, ENTITY_ATTRIBUTE]", ConflictResolution.class);

		assertEquals(
			new ConflictResolution(
				ConflictPolicy.ENTITY,
				EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
			),
			resolution
		);
	}

	@Test
	@DisplayName("should read an empty list as NONE (last-writer-wins)")
	void shouldReadEmptyListAsNone() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("[]", ConflictResolution.class);

		assertEquals(ConflictPolicy.NONE, resolution.policy());
		assertTrue(resolution.granularity().isEmpty());
	}

	@Test
	@DisplayName("should collapse a multi-coarse legacy list to the coarsest policy")
	void shouldCollapseMultiCoarseListToCoarsest() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("[CATALOG, ENTITY]", ConflictResolution.class);

		assertEquals(new ConflictResolution(ConflictPolicy.CATALOG), resolution);
	}

	@Test
	@DisplayName("should default the policy to ENTITY in the object form when granularity is present")
	void shouldDefaultObjectFormPolicyToEntity() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("granularity: [PRICE]", ConflictResolution.class);

		assertEquals(
			new ConflictResolution(
				ConflictPolicy.ENTITY,
				EnumSet.of(GranularConflictPolicy.PRICE)
			),
			resolution
		);
	}

	@Test
	@DisplayName("should parse the object form with an explicit coarse policy")
	void shouldParseObjectFormWithExplicitPolicy() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("policy: COLLECTION", ConflictResolution.class);

		assertEquals(new ConflictResolution(ConflictPolicy.COLLECTION), resolution);
	}

	@Test
	@DisplayName("should parse a scalar shorthand as a coarse-only policy")
	void shouldParseScalarShorthand() throws Exception {
		final ConflictResolution resolution = yamlMapper()
			.readValue("CATALOG", ConflictResolution.class);

		assertEquals(new ConflictResolution(ConflictPolicy.CATALOG), resolution);
	}

	@Test
	@DisplayName("should serialize through a bare mapper without modules")
	void shouldSerializeThroughBareMapper() throws Exception {
		// mirrors EvitaServer#serializeConfiguration, which uses a module-less mapper
		final String yaml = new ObjectMapper(new YAMLFactory())
			.writeValueAsString(
				new ConflictResolution(
					ConflictPolicy.ENTITY,
					EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
				)
			);

		assertTrue(yaml.contains("policy"), () -> "Serialized form missing policy: " + yaml);
		assertTrue(yaml.contains("ENTITY_ATTRIBUTE"), () -> "Serialized form missing granularity: " + yaml);
	}

}
