/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.grpc.generated.GrpcScopedFacetedPartially;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Converts between {@link SetReferenceSchemaFacetedMutation} and {@link GrpcSetReferenceSchemaFacetedMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaFacetedMutationConverter
	implements SchemaMutationConverter<SetReferenceSchemaFacetedMutation, GrpcSetReferenceSchemaFacetedMutation> {
	public static final SetReferenceSchemaFacetedMutationConverter INSTANCE = new SetReferenceSchemaFacetedMutationConverter();

	/**
	 * Converts an array of {@link ScopedFacetedPartially} to a list of {@link GrpcScopedFacetedPartially}.
	 */
	@Nonnull
	public static List<GrpcScopedFacetedPartially> toGrpcScopedFacetedPartially(
		@Nonnull ScopedFacetedPartially[] facetedPartiallyInScopes
	) {
		return Arrays.stream(facetedPartiallyInScopes)
			.map(entry -> {
				final GrpcScopedFacetedPartially.Builder fpBuilder = GrpcScopedFacetedPartially.newBuilder()
					.setScope(EvitaEnumConverter.toGrpcScope(entry.scope()));
				final Expression expression = entry.expression();
				if (expression != null) {
					fpBuilder.setExpression(StringValue.of(expression.toExpressionString()));
				}
				return fpBuilder.build();
			})
			.toList();
	}

	@Nonnull
	public GrpcSetReferenceSchemaFacetedMutation convert(@Nonnull SetReferenceSchemaFacetedMutation mutation) {
		final Builder builder = GrpcSetReferenceSchemaFacetedMutation.newBuilder().setName(mutation.getName());
		final boolean facetedInherited = mutation.getFacetedInScopes() == null;
		builder.setInherited(facetedInherited);
		if (!facetedInherited) {
			builder.setFaceted(Objects.equals(Boolean.TRUE, mutation.getFaceted()));
			ofNullable(mutation.getFacetedInScopes())
				.ifPresentOrElse(
					it -> builder.addAllFacetedInScopes(
						Arrays.stream(it)
							.map(EvitaEnumConverter::toGrpcScope)
							.toList()
					),
					() -> builder.setInherited(true)
				);
		}

		// emit facetedPartially
		final ScopedFacetedPartially[] facetedPartiallyInScopes = mutation.getFacetedPartiallyInScopes();
		if (facetedPartiallyInScopes != null) {
			builder.addAllFacetedPartially(
				toGrpcScopedFacetedPartially(facetedPartiallyInScopes)
			);
		}

		return builder.build();
	}

	@Nonnull
	public SetReferenceSchemaFacetedMutation convert(@Nonnull GrpcSetReferenceSchemaFacetedMutation mutation) {
		final Scope[] facetedInScopes;
		if (mutation.getInherited()) {
			facetedInScopes = null;
		} else if (!mutation.getFacetedInScopesList().isEmpty()) {
			facetedInScopes = mutation.getFacetedInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);
		} else {
			facetedInScopes = Boolean.TRUE.equals(mutation.getFaceted())
				? Scope.DEFAULT_SCOPES
				: Scope.NO_SCOPE;
		}

		// Parse per-scope facetedPartially expressions. An empty list means "no partial
		// faceting expressions" (i.e. not set), NOT "don't change". The client is responsible
		// for re-sending unchanged expressions if they want to retain them.
		final ScopedFacetedPartially[] facetedPartiallyInScopes;
		if (mutation.getFacetedPartiallyList().isEmpty()) {
			facetedPartiallyInScopes = null;
		} else {
			facetedPartiallyInScopes = EntitySchemaConverter.parseFacetedPartially(
				mutation.getFacetedPartiallyList()
			);
		}

		return new SetReferenceSchemaFacetedMutation(
			mutation.getName(),
			facetedInScopes,
			facetedPartiallyInScopes
		);
	}
}
