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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.EntityUpsertMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectParser;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Implementation of {@link EntityUpsertMutationConverter} for input object structure specific to REST.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class RestEntityUpsertMutationConverter extends EntityUpsertMutationConverter<JsonNode> {

	public RestEntityUpsertMutationConverter(@Nonnull ObjectMapper objectMapper, @Nonnull EntitySchemaContract entitySchema) {
		super(entitySchema, objectMapper, new RestMutationObjectParser(objectMapper), new RestMutationResolvingExceptionFactory());
	}

	@Nonnull
	@Override
	protected List<Object> convertAggregates(@Nonnull JsonNode inputLocalMutationAggregates) {
		final List<Object> actualAggregates = new LinkedList<>();
		for (Iterator<JsonNode> elementsIterator = inputLocalMutationAggregates.elements(); elementsIterator.hasNext(); ) {
			actualAggregates.add(elementsIterator.next());
		}
		return actualAggregates;
	}
}
