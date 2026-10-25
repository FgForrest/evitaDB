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

import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiComplexType;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies the served typed normalization contract without starting a server. */
@Tag(EXTERNAL_API)
@Tag(REST)
@Tag(SCHEMA)
@DisplayName("REST typed normalization input schema")
class AnalysisStepInputObjectBuilderTest {

	@Test
	@DisplayName("registers sorted exclusive branches and descriptor-driven parameter types")
	void shouldBuildExclusiveTypedInput() {
		final RestBuildingContext context = mock(RestBuildingContext.class);
		final ObjectDescriptorToOpenApiObjectTransformer transformer = new ObjectDescriptorToOpenApiObjectTransformer(
			new PropertyDescriptorToOpenApiPropertyTransformer(
				new PropertyDataTypeDescriptorToOpenApiTypeTransformer(context)
			)
		);
		AnalysisStepInputObjectBuilder.registerTypes(context, transformer);
		final ArgumentCaptor<OpenApiComplexType> registered = ArgumentCaptor.forClass(OpenApiComplexType.class);
		verify(context, atLeastOnce()).registerType(registered.capture());
		final List<OpenApiComplexType> types = registered.getAllValues();
		final Schema<?> output = types.stream()
			.filter(type -> type.getName().equals(AnalysisStepDescriptor.THIS.name()))
			.findFirst().orElseThrow().toSchema();
		final Schema<?> outputParameters = output.getProperties().get(AnalysisStepDescriptor.PARAMETERS.name());
		assertEquals("object", outputParameters.getType());
		assertNull(outputParameters.getAnyOf());
		assertNotEquals(Boolean.FALSE, outputParameters.getAdditionalProperties());
		final Schema<?> input = types.stream()
			.filter(type -> type.getName().equals(AnalysisStepDescriptor.THIS_INPUT.name()))
			.findFirst().orElseThrow().toSchema();
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
				final Schema<?> parameters = types.stream()
					.filter(type -> type.getName().equals(step.parameters().name()))
					.findFirst().orElseThrow().toSchema();
				assertEquals(Boolean.FALSE, parameters.getAdditionalProperties());
				for (final PropertyDescriptor property : step.parameters().staticProperties()) {
					assertNotNull(parameters.getProperties().get(property.name()));
				}
			}
		}
	}
}
