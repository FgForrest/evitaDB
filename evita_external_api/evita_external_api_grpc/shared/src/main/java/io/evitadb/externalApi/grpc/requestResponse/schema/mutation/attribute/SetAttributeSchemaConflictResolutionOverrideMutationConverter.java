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

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toConflictResolutionOverride;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcConflictResolutionOverride;

/**
 * Converts between {@link SetAttributeSchemaConflictResolutionOverrideMutation} and
 * {@link GrpcSetAttributeSchemaConflictResolutionOverrideMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaConflictResolutionOverrideMutationConverter implements SchemaMutationConverter<SetAttributeSchemaConflictResolutionOverrideMutation, GrpcSetAttributeSchemaConflictResolutionOverrideMutation> {
	public static final SetAttributeSchemaConflictResolutionOverrideMutationConverter INSTANCE = new SetAttributeSchemaConflictResolutionOverrideMutationConverter();

	@Nonnull
	public SetAttributeSchemaConflictResolutionOverrideMutation convert(@Nonnull GrpcSetAttributeSchemaConflictResolutionOverrideMutation mutation) {
		return new SetAttributeSchemaConflictResolutionOverrideMutation(
			mutation.getName(),
			toConflictResolutionOverride(mutation.getConflictResolutionOverride())
		);
	}

	@Nonnull
	public GrpcSetAttributeSchemaConflictResolutionOverrideMutation convert(@Nonnull SetAttributeSchemaConflictResolutionOverrideMutation mutation) {
		return GrpcSetAttributeSchemaConflictResolutionOverrideMutation.newBuilder()
			.setName(mutation.getName())
			.setConflictResolutionOverride(toGrpcConflictResolutionOverride(mutation.getConflictResolutionOverride()))
			.build();
	}
}
