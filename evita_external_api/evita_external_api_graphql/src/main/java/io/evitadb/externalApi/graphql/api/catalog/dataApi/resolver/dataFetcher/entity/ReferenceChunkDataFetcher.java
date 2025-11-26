
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Finds a data chunk of references in the parent entity that conforms to a specified name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ReferenceChunkDataFetcher extends AbstractReferenceDataFetcher<DataChunk<ReferenceContract>> {

	public ReferenceChunkDataFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		super(referenceSchema);
	}

	@Nonnull
    @Override
    protected DataChunk<ReferenceContract> doGet(
		@Nonnull DataFetchingEnvironment environment,
	    @Nonnull DataChunk<ReferenceContract> references
    ) {
        Assert.isPremiseValid(
	        this.referenceSchema.getCardinality().getMax() > 1,
            () -> new GraphQLQueryResolvingInternalError(
                "Reference `" + this.referenceSchema.getName() + "` doesn't have cardinality of more references but more references were requested."
            )
        );
        return references;
    }
}
