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
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.dto.ScopedGlobalAttributeUniquenessTypeDto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Provides complete list of {@link GlobalAttributeSchemaContract#getUniquenessType(Scope)}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AttributeSchemaGlobalUniquenessTypeDataFetcher implements DataFetcher<List<ScopedGlobalAttributeUniquenessTypeDto>> {

	@Nullable
	private static AttributeSchemaGlobalUniquenessTypeDataFetcher INSTANCE = null;

	@Nonnull
	public static AttributeSchemaGlobalUniquenessTypeDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new AttributeSchemaGlobalUniquenessTypeDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<ScopedGlobalAttributeUniquenessTypeDto> get(DataFetchingEnvironment environment) throws Exception {
		final GlobalAttributeSchemaContract attributeSchema = environment.getSource();
		return Arrays.stream(Scope.values())
			.map(scope -> new ScopedGlobalAttributeUniquenessTypeDto(scope, attributeSchema.getGlobalUniquenessType(scope)))
			.toList();
	}
}
