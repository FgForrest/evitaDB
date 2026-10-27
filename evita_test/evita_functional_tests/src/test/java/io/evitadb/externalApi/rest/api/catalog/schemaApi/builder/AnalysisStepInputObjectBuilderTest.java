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


package io.evitadb.externalApi.rest.api.catalog.schemaApi.builder;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.system.SystemRestBuilder;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.EvitaTestSupport;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the served typed normalization contract without starting a server: the types are read from the OpenAPI
 * specification the system REST API is built with.
 */
@Tag(EXTERNAL_API)
@Tag(REST)
@Tag(SCHEMA)
@DisplayName("REST typed normalization input schema")
class AnalysisStepInputObjectBuilderTest implements EvitaTestSupport {

	@SuppressWarnings("rawtypes")
	@Test
	@DisplayName("registers sorted exclusive branches and descriptor-driven parameter types")
	void shouldBuildExclusiveTypedInput() {
		final Map<String, Schema> types = systemApiTypes();
		final Schema<?> output = types.get(AnalysisStepDescriptor.THIS.name());
		final Schema<?> outputParameters = output.getProperties().get(AnalysisStepDescriptor.PARAMETERS.name());
		assertEquals("object", outputParameters.getType());
		assertNull(outputParameters.getAnyOf());
		assertNotEquals(Boolean.FALSE, outputParameters.getAdditionalProperties());
		final Schema<?> input = types.get(AnalysisStepDescriptor.THIS_INPUT.name());
		final List<AnalysisStepInputDescriptors.StepInput> steps = AnalysisStepInputDescriptors.getSteps();
		assertEquals(steps.size(), input.getOneOf().size());
		assertEquals(steps.stream().map(AnalysisStepInputDescriptors.StepInput::stepName).sorted().toList(),
			steps.stream().map(AnalysisStepInputDescriptors.StepInput::stepName).toList());
		for (int i = 0; i < steps.size(); i++) {
			final AnalysisStepInputDescriptors.StepInput step = steps.get(i);
			final Schema<?> branch = input.getOneOf().get(i);
			assertEquals(Boolean.FALSE, branch.getAdditionalProperties());
			assertEquals(List.of(step.fieldName()), branch.getRequired());
			assertEquals(1, branch.getProperties().size());
			final Schema<?> value = branch.getProperties().get(step.fieldName());
			if (step.parameters() == null) {
				assertEquals("boolean", value.getType());
				assertEquals(List.of(Boolean.TRUE), value.getEnum());
			} else {
				assertTrue(value.get$ref().endsWith("/" + step.parameters().name()));
				final Schema<?> parameters = types.get(step.parameters().name());
				assertEquals(Boolean.FALSE, parameters.getAdditionalProperties());
				for (final PropertyDescriptor property : step.parameters().staticProperties()) {
					assertNotNull(parameters.getProperties().get(property.name()));
				}
			}
		}
	}

	/**
	 * Builds the OpenAPI specification of the system REST API over an empty engine. The engine only satisfies the
	 * builder; nothing is read from it.
	 *
	 * @return schemas of the specification's components, keyed by type name
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	private Map<String, Schema> systemApiTypes() {
		final TestPaths paths = createTestPaths("AnalysisStepInputObjectBuilder");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			return new SystemRestBuilder(
				new RestOptions(true, "localhost:5555", null, null, null, "rest", null),
				HeaderOptions.builder().build(),
				evita
			).build().openApi().getComponents().getSchemas();
		} finally {
			cleanupTestPaths(paths);
		}
	}
}
