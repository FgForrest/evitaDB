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


package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcAnalysisStep;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Converts ordered declarative analysis steps without consulting index policies or executing the steps.
 * Named parameter values retain their evitaDB types through the generic value converter.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AnalysisStepConverter {

	/**
	 * Converts wire descriptions in encounter order. An absent repeated field produces an empty declaration.
	 *
	 * @param steps wire descriptions
	 * @return ordered, immutable step descriptions
	 */
	@Nonnull
	public static List<AnalysisStep> toAnalysisSteps(@Nonnull List<GrpcAnalysisStep> steps) {
		final List<AnalysisStep> result = new ArrayList<>(steps.size());
		for (final GrpcAnalysisStep step : steps) {
			final Map<String, Serializable> parameters =
				CollectionUtils.createLinkedHashMap(step.getParametersCount());
			step.getParametersMap().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(
				entry -> parameters.put(entry.getKey(), EvitaDataTypesConverter.toEvitaValue(entry.getValue()))
			);
			result.add(new AnalysisStep(step.getName(), parameters));
		}
		return List.copyOf(result);
	}

	/**
	 * Converts a mutation's nullable declaration. Empty wire lists represent both null and empty declarations.
	 *
	 * @param steps mutation descriptions, or null for no normalization
	 * @return wire descriptions in declared order
	 */
	@Nonnull
	public static List<GrpcAnalysisStep> toGrpcAnalysisSteps(@Nullable AnalysisStep[] steps) {
		return steps == null ? List.of() : toGrpcAnalysisSteps(Arrays.asList(steps));
	}

	/**
	 * Converts a schema's declared chain, preserving order and sorting parameter names for deterministic output.
	 *
	 * @param steps declared schema descriptions
	 * @return wire descriptions
	 */
	@Nonnull
	public static List<GrpcAnalysisStep> toGrpcAnalysisSteps(@Nonnull List<AnalysisStep> steps) {
		final List<GrpcAnalysisStep> result = new ArrayList<>(steps.size());
		for (final AnalysisStep step : steps) {
			final GrpcAnalysisStep.Builder builder = GrpcAnalysisStep.newBuilder().setName(step.name());
			step.parameters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(
				entry -> builder.putParameters(entry.getKey(), EvitaDataTypesConverter.toGrpcEvitaValue(entry.getValue()))
			);
			result.add(builder.build());
		}
		return result;
	}
}
