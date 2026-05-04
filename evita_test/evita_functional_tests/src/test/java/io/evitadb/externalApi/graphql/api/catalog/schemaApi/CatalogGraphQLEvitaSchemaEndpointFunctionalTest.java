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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.MapBuilder.map;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Ancestor for tests for GraphQL catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
public abstract class CatalogGraphQLEvitaSchemaEndpointFunctionalTest extends GraphQLEndpointFunctionalTest {

	@Nonnull
	protected static List<Map<String, Object>> createAttributeUniquenessTypeDto(@Nonnull AttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.map(scope -> map()
				.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
				.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), schema.getUniquenessType(scope).name())
				.build())
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createAttributeUniquenessTypeDto(@Nonnull AttributeUniquenessType uniquenessType) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final AttributeUniquenessType finalUniquenessType = (scope == Scope.LIVE) ? uniquenessType : AttributeUniquenessType.NOT_UNIQUE;
				return map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), finalUniquenessType.name())
					.build();
			})
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createGlobalAttributeUniquenessTypeDto(@Nonnull GlobalAttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.map(scope -> map()
				.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
				.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), schema.getGlobalUniquenessType(scope).name())
				.build())
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createGlobalAttributeUniquenessTypeDto(@Nonnull GlobalAttributeUniquenessType uniquenessType) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final GlobalAttributeUniquenessType finalUniquenessType = (scope == Scope.LIVE) ? uniquenessType : GlobalAttributeUniquenessType.NOT_UNIQUE;
				return map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), finalUniquenessType.name())
					.build();
			})
			.toList();
	}

	@Nonnull
	protected static List<String> createAttributeSortableDto(@Nonnull AttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.filter(schema::isSortableInScope)
			.map(Enum::name)
			.toList();
	}

	@Nonnull
	protected static List<String> createAttributeFilterableDto(@Nonnull AttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.filter(schema::isFilterableInScope)
			.map(Enum::name)
			.toList();
	}

	@Nonnull
	protected static List<String> createReferencedFacetedDto(@Nonnull ReferenceSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.filter(schema::isFacetedInScope)
			.map(Enum::name)
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createReferenceIndexedDto(@Nonnull ReferenceSchemaContract schema) {
		return schema.getReferenceIndexTypeInScopes()
			.entrySet()
			.stream()
			.map(
				it -> map()
					.e(ScopedDataDescriptor.SCOPE.name(), it.getKey().name())
					.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), it.getValue().name())
					.build()
			)
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createReferenceIndexedComponentsDto(@Nonnull ReferenceSchemaContract schema) {
		return schema.getIndexedComponentsInScopes()
			.entrySet()
			.stream()
			.map(
				entry -> map()
					.e(ScopedDataDescriptor.SCOPE.name(), entry.getKey().name())
					.e(
						ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
						entry.getValue().stream().map(ReferenceIndexedComponents::name).toList()
					)
					.build()
			)
			.toList();
	}

	/**
	 * Creates a list of maps representing the bucketed histogram definitions for different scopes
	 * based on the provided {@link ReferenceSchemaContract}. Each map entry contains a scope,
	 * the histogram index name, and the optional value expression string.
	 *
	 * @param referenceSchema the reference schema containing bucketed histogram definitions
	 * @return a list of maps, where each map contains scope, nameOfTheIndex, and valueExpression fields
	 */
	@Nonnull
	protected static List<Map<String, Object>> createBucketedHistogramDto(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedHistogramDefinitions =
			referenceSchema.getAllHistogramIndexDefinitions();
		return bucketedHistogramDefinitions.entrySet()
			.stream()
			.flatMap(scopeEntry -> scopeEntry.getValue().values().stream()
				.map(def -> {
					final Expression valueExpression = def.valueExpression();
					return map()
						.e(ScopedDataDescriptor.SCOPE.name(), scopeEntry.getKey().name())
						.e(
							ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(),
							def.nameOfTheIndex()
						)
						.e(
							ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(),
							valueExpression != null ? valueExpression.toExpressionString() : null
						)
						.build();
				})
			)
			.toList();
	}

	/**
	 * Creates a list of maps representing the bucketed partially expressions for different scopes
	 * based on the provided {@link ReferenceSchemaContract}. Each map entry contains a scope
	 * and the corresponding partial-bucketing expression string.
	 *
	 * @param referenceSchema the reference schema containing bucketed partially expressions
	 * @return a list of maps, where each map contains scope and expression fields
	 */
	@Nonnull
	protected static List<Map<String, Object>> createBucketedPartiallyDto(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final Map<Scope, Expression> bucketedPartiallyInScopes = referenceSchema.getBucketedPartiallyInScopes();
		return bucketedPartiallyInScopes.entrySet()
			.stream()
			.map(entry -> map()
				.e(ScopedDataDescriptor.SCOPE.name(), entry.getKey().name())
				.e(
					ScopedBucketedPartiallyDescriptor.EXPRESSION.name(),
					entry.getValue().toExpressionString()
				)
				.build())
			.toList();
	}
}
