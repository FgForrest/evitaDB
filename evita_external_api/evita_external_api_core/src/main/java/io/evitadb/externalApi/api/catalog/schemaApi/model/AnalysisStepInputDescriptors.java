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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Shared typed normalization input metadata generated from installed normalizing providers.
 * GraphQL applies `@oneOf` to the aggregate; REST represents the same exclusivity with OpenAPI `oneOf`.
 * Parameterless providers use `true`, because GraphQL does not permit input objects without fields.
 */
public final class AnalysisStepInputDescriptors {
	private static final List<StepInput> STEPS = getSteps(new AnalysisStepRegistry());

	/** Prevents instantiation of this static holder. */
	private AnalysisStepInputDescriptors() {
	}

	/**
	 * Returns installed normalizing steps sorted by their stable names.
	 *
	 * @return immutable metadata of the steps installed on this server
	 */
	@Nonnull
	public static List<StepInput> getSteps() {
		return STEPS;
	}

	/**
	 * Builds the common optional-field aggregate; each API adds its exclusivity constraint.
	 *
	 * @return descriptor with one optional property per installed normalizing step
	 */
	@Nonnull
	public static ObjectDescriptor getInputDescriptor() {
		final ObjectDescriptor.ObjectDescriptorBuilder builder = ObjectDescriptor.builder()
			.name("InputAnalysisStep")
			.description("Exactly one installed normalization step must be selected with a non-null value.");
		for (final StepInput step : STEPS) {
			builder.staticProperty(PropertyDescriptor.builder()
				.name(step.fieldName())
				.description("Selects `" + step.stepName() + "`; parameterless steps accept only `true`.")
				.type(step.parameters() == null ? nullable(Boolean.class) : nullableRef(step.parameters()))
				.build());
		}
		return builder.build();
	}

	/**
	 * Builds deterministic metadata for a server registry and refuses names that collide after camel-case conversion.
	 *
	 * @param registry installed server analysis providers
	 * @return immutable metadata sorted by step name
	 */
	@Nonnull
	public static List<StepInput> getSteps(@Nonnull AnalysisStepRegistry registry) {
		final List<StepInput> result = new ArrayList<>();
		final Set<String> names = CollectionUtils.createHashSet(registry.getNormalizingFactories().size());
		for (final NormalizingAnalysisStepFactory factory : registry.getNormalizingFactories()) {
			final String fieldName = StringUtils.toCamelCase(factory.getName());
			if (!names.add(fieldName)) {
				throw new ExternalApiInternalError("Normalizing step input name collision: `" + fieldName + "`.");
			}
			ObjectDescriptor parameters = null;
			if (!factory.getParameters().isEmpty()) {
				final ObjectDescriptor.ObjectDescriptorBuilder builder = ObjectDescriptor.builder()
					.name("Input" + StringUtils.toPascalCase(factory.getName()) + "AnalysisStepParameters")
					.description("Parameters accepted by installed normalizing step `" + factory.getName() + "`.");
				final List<AnalysisStepParameterDescriptor> sorted = new ArrayList<>(factory.getParameters());
				sorted.sort(Comparator.comparing(AnalysisStepParameterDescriptor::name));
				for (final AnalysisStepParameterDescriptor parameter : sorted) {
					builder.staticProperty(PropertyDescriptor.builder()
						.name(parameter.name())
						.description(parameter.description())
						.type(new PrimitivePropertyDataTypeDescriptor(parameter.type(), parameter.required()))
						.build());
				}
				parameters = builder.build();
			}
			result.add(new StepInput(factory.getName(), fieldName, parameters));
		}
		return List.copyOf(result);
	}

	/**
	 * Input field and its optional parameter object.
	 *
	 * @param stepName persisted provider name
	 * @param fieldName camel-case API field name
	 * @param parameters parameter object descriptor, or null for a Boolean `true` selector
	 */
	public record StepInput(
		@Nonnull String stepName,
		@Nonnull String fieldName,
		@Nullable ObjectDescriptor parameters
	) {
	}
}
