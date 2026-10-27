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


package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider metadata coverage independent of engines, web servers and the built-in step implementations. */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@DisplayName("Installed normalizing step input metadata")
class AnalysisStepInputDescriptorsTest {
	@Test
	@DisplayName("Generates typed required and optional parameters for installed extensions")
	void shouldGenerateMetadataFromInstalledProviders() {
		final NormalizingAnalysisStepFactory extension = mock(NormalizingAnalysisStepFactory.class);
		when(extension.getName()).thenReturn("custom-fold");
		when(extension.getParameters()).thenReturn(List.of(
			new AnalysisStepParameterDescriptor("optionalLimit", Integer.class, false, "Optional limit."),
			new AnalysisStepParameterDescriptor("characters", String[].class, true, "Required characters.")
		));
		final NormalizingAnalysisStepFactory parameterless = mock(NormalizingAnalysisStepFactory.class);
		when(parameterless.getName()).thenReturn("ascii-fallback");
		when(parameterless.getParameters()).thenReturn(List.of());
		final AnalysisStepFactory unsafe = mock(AnalysisStepFactory.class);
		when(unsafe.getName()).thenReturn("not-normalizing");
		final List<AnalysisStepInputDescriptors.StepInput> result = AnalysisStepInputDescriptors.getSteps(
			new AnalysisStepRegistry(List.of(unsafe, extension, parameterless))
		);
		assertEquals(List.of("ascii-fallback", "custom-fold"), result.stream()
			.map(AnalysisStepInputDescriptors.StepInput::stepName).toList());
		assertEquals("asciiFallback", result.getFirst().fieldName());
		assertNull(result.getFirst().parameters());
		assertEquals("customFold", result.getLast().fieldName());
		assertNotNull(result.getLast().parameters());
		assertEquals("InputCustomFoldAnalysisStepParameters", result.getLast().parameters().name());
		final Map<String, PropertyDescriptor> properties = result.getLast().parameters().staticProperties().stream()
			.collect(Collectors.toMap(PropertyDescriptor::name, Function.identity()));
		assertTrue(properties.get("characters").type().nonNull());
		assertSame(String[].class, properties.get("characters").primitiveType().javaType());
		assertEquals("Required characters.", properties.get("characters").description());
		assertFalse(properties.get("optionalLimit").type().nonNull());
		assertSame(Integer.class, properties.get("optionalLimit").primitiveType().javaType());
		assertThrows(UnsupportedOperationException.class, result::clear);
	}

	@Test
	@DisplayName("Refuses installed provider names that collide in camel case")
	void shouldRejectCollidingInputNames() {
		final NormalizingAnalysisStepFactory first = mock(NormalizingAnalysisStepFactory.class);
		when(first.getName()).thenReturn("custom-fold");
		when(first.getParameters()).thenReturn(List.of());
		final NormalizingAnalysisStepFactory second = mock(NormalizingAnalysisStepFactory.class);
		when(second.getName()).thenReturn("custom_fold");
		when(second.getParameters()).thenReturn(List.of());
		assertThrows(ExternalApiInternalError.class, () -> AnalysisStepInputDescriptors.getSteps(
			new AnalysisStepRegistry(List.of(first, second))
		));
	}

	@Test
	@DisplayName("Builds the default selector from sorted installed normalizers")
	void shouldDescribeInstalledNormalizers() {
		assertEquals(List.of("ascii-folding", "lowercase", "remove-characters", "trim"),
			AnalysisStepInputDescriptors.getSteps().stream()
				.map(AnalysisStepInputDescriptors.StepInput::stepName).toList());
		assertEquals("InputAnalysisStep", AnalysisStepDescriptor.THIS_INPUT.name());
		assertTrue(AnalysisStepDescriptor.THIS_INPUT.staticProperties().stream()
			.noneMatch(it -> it.type().nonNull()));
	}
}
