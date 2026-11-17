/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData;

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateAssociatedDataSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toConflictResolutionOverride;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcConflictResolutionOverride;

/**
 * Converts between {@link CreateAssociatedDataSchemaMutation} and {@link GrpcCreateAssociatedDataSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateAssociatedDataSchemaMutationConverter implements SchemaMutationConverter<CreateAssociatedDataSchemaMutation, GrpcCreateAssociatedDataSchemaMutation> {
	public static final CreateAssociatedDataSchemaMutationConverter INSTANCE = new CreateAssociatedDataSchemaMutationConverter();

	@Nonnull
	public CreateAssociatedDataSchemaMutation convert(@Nonnull GrpcCreateAssociatedDataSchemaMutation mutation) {
		return new CreateAssociatedDataSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			EvitaDataTypesConverter.toEvitaDataType(mutation.getType()),
			mutation.getLocalized(),
			mutation.getNullable(),
			mutation.getSearchableInScopesList().stream().map(EvitaEnumConverter::toScope).toArray(Scope[]::new),
			mutation.hasTextExpression() ? ExpressionFactory.parse(mutation.getTextExpression().getValue()) : null,
			toConflictResolutionOverride(mutation.getConflictResolutionOverride()),
			mutation.hasAnalyzer() ? mutation.getAnalyzer().getValue() : null,
			mutation.hasLengthPivot() ? mutation.getLengthPivot().getValue() : null,
			mutation.hasSearchWeight() ? EvitaDataTypesConverter.toBigDecimal(mutation.getSearchWeight()) : null
		);
	}

	@Nonnull
	public GrpcCreateAssociatedDataSchemaMutation convert(@Nonnull CreateAssociatedDataSchemaMutation mutation) {
		final GrpcCreateAssociatedDataSchemaMutation.Builder builder = GrpcCreateAssociatedDataSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setType(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(mutation.getType()))
			.setLocalized(mutation.isLocalized())
			.setNullable(mutation.isNullable())
			.setConflictResolutionOverride(toGrpcConflictResolutionOverride(mutation.getConflictResolutionOverride()));

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}

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
		if (mutation.getTextExpression() != null) {
			builder.setTextExpression(StringValue.of(mutation.getTextExpression().toExpressionString()));
		}

		return builder.build();
	}
}
