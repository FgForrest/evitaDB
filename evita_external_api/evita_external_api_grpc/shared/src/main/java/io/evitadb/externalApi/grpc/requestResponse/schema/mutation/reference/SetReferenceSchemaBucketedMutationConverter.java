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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaBucketedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaBucketedMutation.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcScopedHistogramIndexDefinition;
import io.evitadb.externalApi.grpc.generated.GrpcScopedBucketedPartially;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * Converts between {@link SetReferenceSchemaBucketedMutation} and
 * {@link GrpcSetReferenceSchemaBucketedMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaBucketedMutationConverter
	implements SchemaMutationConverter<SetReferenceSchemaBucketedMutation, GrpcSetReferenceSchemaBucketedMutation> {
	public static final SetReferenceSchemaBucketedMutationConverter INSTANCE =
		new SetReferenceSchemaBucketedMutationConverter();

	/**
	 * Converts an array of {@link ScopedHistogramIndexDefinition} to a list of {@link GrpcScopedHistogramIndexDefinition}.
	 *
	 * @param bucketedInScopes the array of scoped bucketed histogram definitions to convert
	 * @return a list of gRPC scoped bucketed histogram messages
	 */
	@Nonnull
	public static List<GrpcScopedHistogramIndexDefinition> toGrpcScopedHistogramIndexDefinition(
		@Nonnull ScopedHistogramIndexDefinition[] bucketedInScopes
	) {
		return Arrays.stream(bucketedInScopes)
			.map(entry -> {
				final GrpcScopedHistogramIndexDefinition.Builder bhBuilder = GrpcScopedHistogramIndexDefinition.newBuilder()
					.setScope(EvitaEnumConverter.toGrpcScope(entry.scope()))
					.setNameOfTheIndex(entry.nameOfTheIndex());
				final Expression valueExpression = entry.valueExpression();
				if (valueExpression != null) {
					bhBuilder.setValueExpression(StringValue.of(valueExpression.toExpressionString()));
				}
				final Expression assignedWhen = entry.assignedWhen();
				if (assignedWhen != null) {
					bhBuilder.setAssignedWhen(StringValue.of(assignedWhen.toExpressionString()));
				}
				return bhBuilder.build();
			})
			.toList();
	}

	/**
	 * Converts an array of {@link ScopedBucketedPartially} to a list of {@link GrpcScopedBucketedPartially}.
	 *
	 * @param bucketedPartiallyInScopes the array of scoped bucketed partially definitions to convert
	 * @return a list of gRPC scoped bucketed partially messages
	 */
	@Nonnull
	public static List<GrpcScopedBucketedPartially> toGrpcScopedBucketedPartially(
		@Nonnull ScopedBucketedPartially[] bucketedPartiallyInScopes
	) {
		return Arrays.stream(bucketedPartiallyInScopes)
			.map(entry -> {
				final GrpcScopedBucketedPartially.Builder bpBuilder = GrpcScopedBucketedPartially.newBuilder()
					.setScope(EvitaEnumConverter.toGrpcScope(entry.scope()));
				final Expression expression = entry.expression();
				if (expression != null) {
					bpBuilder.setExpression(StringValue.of(expression.toExpressionString()));
				}
				return bpBuilder.build();
			})
			.toList();
	}

	@Nonnull
	public GrpcSetReferenceSchemaBucketedMutation convert(@Nonnull SetReferenceSchemaBucketedMutation mutation) {
		final Builder builder = GrpcSetReferenceSchemaBucketedMutation.newBuilder()
			.setName(mutation.getName());
		if (mutation.getBucketedInScopes() != null) {
			builder.addAllBucketedInScopes(toGrpcScopedHistogramIndexDefinition(mutation.getBucketedInScopes()));
		}

		// emit bucketedPartially
		final ScopedBucketedPartially[] bucketedPartiallyInScopes = mutation.getBucketedPartiallyInScopes();
		if (bucketedPartiallyInScopes != null) {
			builder.addAllBucketedPartially(toGrpcScopedBucketedPartially(bucketedPartiallyInScopes));
		}

		return builder.build();
	}

	@Nonnull
	public SetReferenceSchemaBucketedMutation convert(@Nonnull GrpcSetReferenceSchemaBucketedMutation mutation) {
		final ScopedHistogramIndexDefinition[] parsed = EntitySchemaConverter.parseBucketedHistogram(
			mutation.getBucketedInScopesList()
		);
		final ScopedHistogramIndexDefinition[] bucketedInScopes =
			parsed != null ? parsed : ScopedHistogramIndexDefinition.EMPTY;

		// Parse per-scope bucketedPartially expressions
		final ScopedBucketedPartially[] bucketedPartiallyInScopes;
		if (mutation.getBucketedPartiallyCount() > 0) {
			final ScopedBucketedPartially[] parsedPartially = EntitySchemaConverter.parseBucketedPartially(
				mutation.getBucketedPartiallyList()
			);
			bucketedPartiallyInScopes = parsedPartially != null ? parsedPartially : ScopedBucketedPartially.EMPTY;
		} else {
			bucketedPartiallyInScopes = ScopedBucketedPartially.EMPTY;
		}

		return new SetReferenceSchemaBucketedMutation(
			mutation.getName(),
			bucketedInScopes,
			bucketedPartiallyInScopes
		);
	}
}
