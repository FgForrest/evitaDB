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
import io.evitadb.spi.analysis.StringNormalizationStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Provider metadata coverage independent of engines, web servers and the built-in step implementations. */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@DisplayName("Installed normalizing step input metadata")
class AnalysisStepInputDescriptorsTest {
	@Test
	@DisplayName("Generates typed required and optional parameters for installed extensions")
	void shouldGenerateMetadataFromInstalledProviders() {
		final NormalizingAnalysisStepFactory extension = new InstalledNormalizer("custom-fold", List.of(
			new AnalysisStepParameterDescriptor("optionalLimit", Integer.class, false, "Optional limit."),
			new AnalysisStepParameterDescriptor("characters", String[].class, true, "Required characters.")
		));
		final NormalizingAnalysisStepFactory parameterless = new InstalledNormalizer("ascii-fallback", List.of());
		final AnalysisStepFactory unsafe = new InstalledTokenizer("not-normalizing");
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
		final NormalizingAnalysisStepFactory first = new InstalledNormalizer("custom-fold", List.of());
		final NormalizingAnalysisStepFactory second = new InstalledNormalizer("custom_fold", List.of());
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

	/**
	 * Installed extension that declares itself safe as a normalizer. The descriptors read only its name and
	 * parameters, so it never builds a step.
	 *
	 * @param name name of the step as written in a schema
	 * @param parameters parameters the step declares
	 */
	private record InstalledNormalizer(
		@Nonnull String name,
		@Nonnull List<AnalysisStepParameterDescriptor> parameters
	) implements NormalizingAnalysisStepFactory {

		@Nonnull
		@Override
		public String getName() {
			return this.name;
		}

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return this.parameters;
		}

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// every parameter set is accepted - the descriptors never validate one
		}

		@Nonnull
		@Override
		public StringNormalizationStep create(
			@Nonnull Map<String, Serializable> parameters,
			@Nullable Locale foldLocale
		) {
			throw new UnsupportedOperationException("The descriptors never build a step.");
		}
	}

	/**
	 * Installed extension that does not declare itself safe as a normalizer, so the descriptors must leave it out.
	 *
	 * @param name name of the step as written in a schema
	 */
	private record InstalledTokenizer(@Nonnull String name) implements AnalysisStepFactory {

		@Nonnull
		@Override
		public String getName() {
			return this.name;
		}

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return List.of();
		}

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// every parameter set is accepted - the descriptors never validate one
		}
	}
}
