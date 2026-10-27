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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaNormalizedMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.schema.AnalysisStepConverter.toAnalysisSteps;
import static io.evitadb.externalApi.grpc.requestResponse.schema.AnalysisStepConverter.toGrpcAnalysisSteps;

/**
 * Converts replacement declarations for entity, reference and global attribute normalization.
 * Empty wire lists remove normalization, matching the mutation's empty-array semantics.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaNormalizedMutationConverter implements
	SchemaMutationConverter<SetAttributeSchemaNormalizedMutation, GrpcSetAttributeSchemaNormalizedMutation> {
	/** Shared converter for the three mutation dispatchers. */
	public static final SetAttributeSchemaNormalizedMutationConverter INSTANCE =
		new SetAttributeSchemaNormalizedMutationConverter();

	/**
	 * Reads a replacement declaration; an absent repeated field clears the declared chain.
	 *
	 * @param mutation wire mutation
	 * @return domain mutation
	 */
	@Nonnull
	@Override
	public SetAttributeSchemaNormalizedMutation convert(@Nonnull GrpcSetAttributeSchemaNormalizedMutation mutation) {
		return new SetAttributeSchemaNormalizedMutation(
			mutation.getName(), toAnalysisSteps(mutation.getNormalizationList()).toArray(AnalysisStep[]::new)
		);
	}

	/**
	 * Writes the declared replacement chain without exposing any retained index policy.
	 *
	 * @param mutation domain mutation
	 * @return wire mutation
	 */
	@Nonnull
	@Override
	public GrpcSetAttributeSchemaNormalizedMutation convert(@Nonnull SetAttributeSchemaNormalizedMutation mutation) {
		return GrpcSetAttributeSchemaNormalizedMutation.newBuilder()
			.setName(mutation.getName())
			.addAllNormalization(toGrpcAnalysisSteps(mutation.getNormalization()))
			.build();
	}
}
