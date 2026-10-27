/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaNormalizedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.AnalysisStepConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Output;
import io.evitadb.externalApi.api.resolver.mutation.PropertyObjectListMapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Ancestor abstract implementation for {@link AttributeSchemaMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class AttributeSchemaMutationConverter<M extends AttributeSchemaMutation> extends SchemaMutationConverter<M> {

	protected AttributeSchemaMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                           @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	/**
	 * Reads the optional typed normalization chain; omission retains the mutation's legacy default.
	 *
	 * @param input API mutation properties to convert
	 * @return defensive copy of the ordered steps, or null when omitted
	 */
	@Nullable
	protected AnalysisStep[] readNormalization(@Nonnull Input input) {
		return input.getOptionalProperty(
			SetAttributeSchemaNormalizedMutationDescriptor.NORMALIZATION.name(),
			new PropertyObjectListMapper<>(
				getMutationName(), getExceptionFactory(),
				SetAttributeSchemaNormalizedMutationDescriptor.NORMALIZATION,
				AnalysisStep.class,
				nested -> AnalysisStepConverter.fromInput(nested, getExceptionFactory())
			)
		);
	}

	/**
	 * Pre-serializes custom step descriptions before reflection inspects the serializable constructor.
	 *
	 * @param steps ordered immutable analysis-step descriptions
	 * @param output API mutation properties receiving the serialized declaration
	 */
	protected void writeNormalization(@Nullable AnalysisStep[] steps, @Nonnull Output output) {
		if (steps != null) {
			output.setProperty(
				SetAttributeSchemaNormalizedMutationDescriptor.NORMALIZATION,
				AnalysisStepConverter.toOutput(Arrays.asList(steps))
			);
		}
	}
}
