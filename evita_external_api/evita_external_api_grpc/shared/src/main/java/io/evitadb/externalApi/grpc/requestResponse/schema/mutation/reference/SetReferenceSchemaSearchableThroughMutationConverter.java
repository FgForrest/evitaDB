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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts reference composition declarations in both directions. The composition modes travel in a wrapper message,
 * so an absent declaration (keep the current modes) stays distinct from an empty one (remove them all); the shared
 * weight travels in a wrapper too, so absence stays distinct from zero.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaSearchableThroughMutationConverter
	implements SchemaMutationConverter<
		SetReferenceSchemaSearchableThroughMutation,
		GrpcSetReferenceSchemaSearchableThroughMutation> {
	/** Shared mutation converter. */
	public static final SetReferenceSchemaSearchableThroughMutationConverter INSTANCE =
		new SetReferenceSchemaSearchableThroughMutationConverter();

	/**
	 * Reads the declaration, leaving each optional setting `null` when its wrapper is absent.
	 *
	 * @param mutation the wire mutation
	 * @return the domain mutation
	 */
	@Nonnull
	@Override
	public SetReferenceSchemaSearchableThroughMutation convert(
		@Nonnull GrpcSetReferenceSchemaSearchableThroughMutation mutation
	) {
		return new SetReferenceSchemaSearchableThroughMutation(
			mutation.getName(),
			mutation.hasSearchableThroughInScopes()
				? EvitaEnumConverter.toScopedReferenceSearchModes(mutation.getSearchableThroughInScopes()) : null,
			mutation.hasWeight() ? EvitaDataTypesConverter.toBigDecimal(mutation.getWeight()) : null
		);
	}

	/**
	 * Writes the declaration, setting each optional wrapper only when the setting is present.
	 *
	 * @param mutation the domain mutation
	 * @return the wire mutation
	 */
	@Nonnull
	@Override
	public GrpcSetReferenceSchemaSearchableThroughMutation convert(
		@Nonnull SetReferenceSchemaSearchableThroughMutation mutation
	) {
		final GrpcSetReferenceSchemaSearchableThroughMutation.Builder builder =
			GrpcSetReferenceSchemaSearchableThroughMutation.newBuilder()
				.setName(mutation.getName());
		if (mutation.getSearchableThroughInScopes() != null) {
			builder.setSearchableThroughInScopes(
				EvitaEnumConverter.toGrpcReferenceSearchModes(mutation.getSearchableThroughInScopes()));
		}
		if (mutation.getWeight() != null) {
			builder.setWeight(EvitaDataTypesConverter.toGrpcBigDecimal(mutation.getWeight()));
		}
		return builder.build();
	}
}
