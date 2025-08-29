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

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Finds paginated list of references in parent entity that conforms to specified name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ReferencePageDataFetcher implements DataFetcher<PaginatedList<ReferenceContract>> {

    /**
     * Schema of reference to which this fetcher is mapped to.
     */
    @Nonnull
    private final ReferenceSchemaContract referenceSchema;

    @Nonnull
    @Override
    public PaginatedList<ReferenceContract> get(DataFetchingEnvironment environment) throws Exception {
        final EntityDecorator entity = environment.getSource();
        Assert.isPremiseValid(entity != null, "Entity must not be null");
        Assert.isPremiseValid(
	        this.referenceSchema.getCardinality().getMax() > 1,
            () -> new GraphQLQueryResolvingInternalError(
                "Reference `" + this.referenceSchema.getName() + "` doesn't have cardinality of more references but more references were requested."
            )
        );
        return entity.getReferenceChunk(this.referenceSchema.getName());
    }
}
