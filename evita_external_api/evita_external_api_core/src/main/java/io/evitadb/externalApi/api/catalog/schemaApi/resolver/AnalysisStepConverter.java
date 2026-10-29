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
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
		final Object value = input.getRequiredValue();
		if (!(value instanceof Map<?, ?> selection) || selection.size() != 1) {
			throw exceptionFactory.createInvalidArgumentException(
				"A normalization step must select exactly one installed step."
			);
		}
		final Map.Entry<?, ?> selected = selection.entrySet().iterator().next();
		for (final StepInput step : AnalysisStepInputDescriptors.getSteps()) {
			if (step.fieldName().equals(selected.getKey())) {
				if (step.parameters() == null) {
					if (!Boolean.TRUE.equals(selected.getValue())) {
						throw exceptionFactory.createInvalidArgumentException(
							"Parameterless normalization step `" + step.fieldName() + "` requires `true`."
						);
					}
					return new AnalysisStep(step.stepName(), Map.of());
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
						parameters.put(descriptor.name(), parameter);
					}
				}
				for (final Object name : values.keySet()) {
					if (step.parameters().staticProperties().stream().noneMatch(it -> it.name().equals(name))) {
						throw exceptionFactory.createInvalidArgumentException("Unknown analysis step parameter `" + name + "`.");
					}
				}
				return new AnalysisStep(step.stepName(), parameters);
			}
		}
		throw exceptionFactory.createInvalidArgumentException("Unknown normalizing step input `" + selected.getKey() + "`.");
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
