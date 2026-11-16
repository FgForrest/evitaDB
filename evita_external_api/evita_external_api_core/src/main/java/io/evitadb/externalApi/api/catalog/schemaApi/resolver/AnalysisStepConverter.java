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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.StepInput;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Converts the shared typed input selector into an immutable analysis-step description.
 * It verifies selector exclusivity and parameter shapes before constructing the declaration. The inverse path
 * preserves step order and renders parameter values through the external API's common serialization contract.
 */
public final class AnalysisStepConverter {
	/** Prevents instantiation of this static holder. */
	private AnalysisStepConverter() {
	}

	/**
	 * Converts exactly one selected installed step, coercing parameters according to provider metadata.
	 *
	 * @param input raw one-of input object
	 * @param exceptionFactory API-specific invalid-argument diagnostics
	 * @return declared step description
	 */
	@Nonnull
	public static AnalysisStep fromInput(
		@Nonnull Input input,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		return fromInput(input, exceptionFactory, AnalysisStepInputDescriptors.Context.NORMALIZATION);
	}

	/**
	 * Converts a typed step in a specific fulltext phase using installed parameter metadata.
	 *
	 * @param input selected provider and its typed parameters
	 * @param exceptionFactory API-specific diagnostics
	 * @param context phase restricting the permitted providers
	 * @return declarative step with persisted provider parameter names
	 */
	@Nonnull
	public static AnalysisStep fromInput(
		@Nonnull Input input,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory,
		@Nonnull AnalysisStepInputDescriptors.Context context
	) {
		return fromInput(input, exceptionFactory, context, AnalysisStepInputDescriptors.getRegistry());
	}

	/**
	 * Converts a declaration against a specific server's validated provider registry.
	 *
	 * @param input selected provider and its typed parameter values
	 * @param exceptionFactory API-specific invalid-input diagnostics
	 * @param context phase restricting the accepted providers
	 * @param registry validated providers used to generate the input types
	 * @return step declaration with the provider's original parameter spellings
	 */
	@Nonnull
	public static AnalysisStep fromInput(
		@Nonnull Input input, @Nonnull MutationResolvingExceptionFactory exceptionFactory,
		@Nonnull AnalysisStepInputDescriptors.Context context, @Nonnull AnalysisStepRegistry registry
	) {
		return fromInput(input, exceptionFactory, context, AnalysisStepInputDescriptors.getSteps(registry, context),
			(step, parameter) -> AnalysisStepInputDescriptors.getFulltextParameterName(registry, step, parameter));
	}

	/**
	 * Applies selector and parameter validation to the metadata supplied by the server registry.
	 *
	 * @param input selected provider and its typed parameter values
	 * @param exceptionFactory API-specific invalid-input diagnostics
	 * @param context phase restricting the accepted providers
	 * @param steps provider metadata accepted by that phase
	 * @param parameterName restores the original spelling of a fulltext provider parameter
	 * @return immutable declaration preserving supplied parameter values
	 */
	@Nonnull
	private static AnalysisStep fromInput(
		@Nonnull Input input, @Nonnull MutationResolvingExceptionFactory exceptionFactory,
		@Nonnull AnalysisStepInputDescriptors.Context context, @Nonnull List<StepInput> steps,
		@Nonnull BiFunction<String, String, String> parameterName
	) {
		final String usage =
			context == AnalysisStepInputDescriptors.Context.NORMALIZATION ? "normalization" : "fulltext";
		final Object value = input.getRequiredValue();
		if (!(value instanceof Map<?, ?> selection) || selection.size() != 1) {
			throw exceptionFactory.createInvalidArgumentException(
				"A " + usage + " step must select exactly one installed step."
			);
		}
		final Map.Entry<?, ?> selected = selection.entrySet().iterator().next();
		for (final StepInput step : steps) {
			if (step.fieldName().equals(selected.getKey())) {
				if (step.parameters() == null) {
					if (!Boolean.TRUE.equals(selected.getValue())) {
						throw exceptionFactory.createInvalidArgumentException(
							"Parameterless " + usage + " step `" + step.fieldName() + "` requires `true`."
						);
					}
					return AnalysisStep.of(step.stepName(), Map.of());
				}
				if (!(selected.getValue() instanceof Map<?, ?> values)) {
					throw exceptionFactory.createInvalidArgumentException("Step parameters must be a non-null object.");
				}
				final Map<String, Serializable> parameters = CollectionUtils.createLinkedHashMap(values.size());
				final Input parameterInput = Input.from(input, "AnalysisStep", values, exceptionFactory);
				for (final PropertyDescriptor descriptor : step.parameters().staticProperties()) {
					verifyScalarShape(
						values.get(descriptor.name()), descriptor.primitiveType().javaType(), exceptionFactory
					);
					final Serializable parameter = parameterInput.getProperty(descriptor);
					if (parameter != null) {
						parameters.put(
							context == AnalysisStepInputDescriptors.Context.NORMALIZATION ? descriptor.name() :
								parameterName.apply(step.stepName(), descriptor.name()), parameter);
					}
				}
				for (final Object name : values.keySet()) {
					if (step.parameters().staticProperties().stream().noneMatch(it -> it.name().equals(name))) {
						throw exceptionFactory.createInvalidArgumentException("Unknown analysis step parameter `" + name + "`.");
					}
				}
				return AnalysisStep.of(step.stepName(), parameters);
			}
		}
		throw exceptionFactory.createInvalidArgumentException("Unknown " +
			(context == AnalysisStepInputDescriptors.Context.NORMALIZATION ? "normalizing" : "fulltext") +
			" step input `" + selected.getKey() + "`.");
	}

	/**
	 * Preserves typed JSON string/Boolean boundaries while the existing mapper handles numeric and temporal types.
	 *
	 * @param value            raw parameter value from the input, `null` when the parameter was not supplied
	 * @param type             declared Java type of the parameter; for an array type each item is checked against
	 *                         the component type
	 * @param exceptionFactory API-specific diagnostics for a value of the wrong shape
	 */
	private static void verifyScalarShape(
		@Nullable Object value,
		@Nonnull Class<?> type,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		if (value == null) {
			return;
		}
		if ((type == String.class && !(value instanceof String)) ||
			(type == Boolean.class && !(value instanceof Boolean))) {
			throw exceptionFactory.createInvalidArgumentException(
				"Expected analysis parameter type `" + type.getSimpleName() + "`."
			);
		}
		if (type.isArray()) {
			if (value instanceof List<?> values) {
				for (final Object item : values) {
					verifyScalarShape(item, type.getComponentType(), exceptionFactory);
				}
			} else if (value.getClass().isArray()) {
				for (int i = 0; i < Array.getLength(value); i++) {
					verifyScalarShape(Array.get(value, i), type.getComponentType(), exceptionFactory);
				}
			}
		}
	}

	/**
	 * Serializes the declared descriptions in order, keeping every parameter's evitaDB value type.
	 *
	 * @param steps declared normalization chain
	 * @return generic objects containing `step` and `parameters`
	 */
	@Nonnull
	public static List<Map<String, Object>> toOutput(@Nonnull List<AnalysisStep> steps) {
		final List<Map<String, Object>> result = new ArrayList<>(steps.size());
		for (final AnalysisStep step : steps) {
			result.add(Map.of(
				AnalysisStepDescriptor.STEP.name(), step.name(),
				AnalysisStepDescriptor.PARAMETERS.name(), step.parameters()
			));
		}
		return result;
	}
}
