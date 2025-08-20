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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;

/**
 * Ancestor for tests for GraphQL catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class CatalogGraphQLEvitaSchemaEndpointFunctionalTest extends GraphQLEndpointFunctionalTest {

	@Nonnull
	protected static List<Map<String, Object>> createAttributeUniquenessTypeDto(@Nonnull AttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.map(scope -> map()
				.e(ScopedAttributeUniquenessTypeDescriptor.SCOPE.name(), scope.name())
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
					.e(ScopedAttributeUniquenessTypeDescriptor.SCOPE.name(), scope.name())
					.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), finalUniquenessType.name())
					.build();
			})
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createGlobalAttributeUniquenessTypeDto(@Nonnull GlobalAttributeSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.map(scope -> map()
				.e(ScopedGlobalAttributeUniquenessTypeDescriptor.SCOPE.name(), scope.name())
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
					.e(ScopedGlobalAttributeUniquenessTypeDescriptor.SCOPE.name(), scope.name())
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
	protected static List<String> createReferencedFacetedDto(ReferenceSchemaContract schema) {
		return Arrays.stream(Scope.values())
			.filter(schema::isFacetedInScope)
			.map(Enum::name)
			.toList();
	}

	@Nonnull
	protected static List<Map<String, Object>> createReferenceIndexedDto(ReferenceSchemaContract schema) {
		return schema.getReferenceIndexTypeInScopes()
			.entrySet()
			.stream()
			.map(
				it -> map()
					.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), it.getKey().name())
					.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), it.getValue().name())
					.build()
			)
			.toList();
	}
}
