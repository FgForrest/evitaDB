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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.dto.ScopedAttributeUniquenessTypeDto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides complete list of {@link AttributeSchemaContract#getUniquenessType(Scope)}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AttributeSchemaUniquenessTypeDataFetcher implements DataFetcher<List<ScopedAttributeUniquenessTypeDto>> {

	@Nullable
	private static AttributeSchemaUniquenessTypeDataFetcher INSTANCE = null;

	@Nonnull
	public static AttributeSchemaUniquenessTypeDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new AttributeSchemaUniquenessTypeDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<ScopedAttributeUniquenessTypeDto> get(DataFetchingEnvironment environment) throws Exception {
		final AttributeSchemaContract attributeSchema = environment.getSource();
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final Optional<AttributeUniquenessType> uniquenessType = attributeSchema.getUniquenessType(scope);
				return uniquenessType
					.map(attributeUniquenessType -> new ScopedAttributeUniquenessTypeDto(scope, attributeUniquenessType))
					.orElse(null);
			})
			.filter(Objects::nonNull)
			.toList();
	}
}
