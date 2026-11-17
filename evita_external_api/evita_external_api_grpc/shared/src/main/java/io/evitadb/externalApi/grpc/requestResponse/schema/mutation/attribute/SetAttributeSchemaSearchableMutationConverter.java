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

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaSearchableMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts fulltext searchability declarations of attributes in both directions. The optional analyzer, length pivot
 * and weight travel in wrapper messages, so an absent setting stays distinct from an explicit one such as a zero
 * weight. The scopes travel as a plain repeated field, where absent and empty cannot be told apart; nothing is lost,
 * because the mutation reads a missing scope array as an empty one - searchable nowhere.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaSearchableMutationConverter
	implements SchemaMutationConverter<SetAttributeSchemaSearchableMutation, GrpcSetAttributeSchemaSearchableMutation> {
	/** Shared mutation converter. */
	public static final SetAttributeSchemaSearchableMutationConverter INSTANCE =
		new SetAttributeSchemaSearchableMutationConverter();

	/**
	 * Reads the declaration, leaving each optional setting `null` when its wrapper is absent.
	 *
	 * @param mutation the wire mutation
	 * @return the domain mutation
	 */
	@Nonnull
	@Override
	public SetAttributeSchemaSearchableMutation convert(@Nonnull GrpcSetAttributeSchemaSearchableMutation mutation) {
		return new SetAttributeSchemaSearchableMutation(
			mutation.getName(),
			mutation.getSearchableInScopesList().stream().map(EvitaEnumConverter::toScope).toArray(Scope[]::new),
			mutation.hasAnalyzer() ? mutation.getAnalyzer().getValue() : null,
			mutation.hasLengthPivot() ? mutation.getLengthPivot().getValue() : null,
			mutation.hasSearchWeight() ? EvitaDataTypesConverter.toBigDecimal(mutation.getSearchWeight()) : null
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
	public GrpcSetAttributeSchemaSearchableMutation convert(@Nonnull SetAttributeSchemaSearchableMutation mutation) {
		final GrpcSetAttributeSchemaSearchableMutation.Builder builder =
			GrpcSetAttributeSchemaSearchableMutation.newBuilder()
				.setName(mutation.getName());
		builder.addAllSearchableInScopes(
			Arrays.stream(mutation.getSearchableInScopes()).map(EvitaEnumConverter::toGrpcScope).toList());
		if (mutation.getAnalyzer() != null) {
			builder.setAnalyzer(StringValue.of(mutation.getAnalyzer()));
		}
		if (mutation.getLengthPivot() != null) {
			builder.setLengthPivot(Int32Value.of(mutation.getLengthPivot()));
		}
		if (mutation.getSearchWeight() != null) {
			builder.setSearchWeight(EvitaDataTypesConverter.toGrpcBigDecimal(mutation.getSearchWeight()));
		}
		return builder.build();
	}
}
