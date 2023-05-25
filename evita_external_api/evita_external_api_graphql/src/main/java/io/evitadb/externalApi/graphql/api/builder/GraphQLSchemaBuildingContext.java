/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.builder;

import graphql.schema.*;
import graphql.schema.GraphQLSchema.Builder;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLObjectType.newObject;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Generic context object for building GraphQL schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class GraphQLSchemaBuildingContext {

    @Getter
    @Nonnull
    private final Evita evita;

    @Nonnull
    private final List<GraphQLFieldDefinition> queryFields = new LinkedList<>();
    @Nonnull
    private final List<GraphQLFieldDefinition> mutationFields = new LinkedList<>();
    @Nonnull
    private final Builder schemaBuilder = GraphQLSchema.newSchema();
    @Nonnull
    private final GraphQLCodeRegistry.Builder registryBuilder = GraphQLCodeRegistry.newCodeRegistry();
    /**
     * Holds all globally registered custom enums that will be inserted into GraphQL schema.
     */
    @Nonnull
    private final Set<String> registeredCustomEnums = createHashSet(32);

    @Nonnull
    public Executor getEvitaExecutor() {
        return evita.getExecutor();
    }

    /**
     * Registers new custom enum if there is not enum with same name.
     */
    public void registerCustomEnumIfAbsent(@Nonnull GraphQLEnumType customEnum) {
        if (registeredCustomEnums.contains(customEnum.getName())) {
            return;
        }
        registeredCustomEnums.add(customEnum.getName());
        registerType(customEnum);
    }

    /**
     * Register new GraphQL type to schema.
     */
    public void registerType(@Nonnull GraphQLType type) {
        schemaBuilder.additionalType(type);
    }

    /**
     * Register new GraphQL types to schema.
     */
    public void registerTypes(@Nonnull Set<GraphQLType> types) {
        schemaBuilder.additionalTypes(types);
    }

    /**
     * Register new GraphQL type resolver for interface type to schema.
     */
    public void registerTypeResolver(@Nonnull GraphQLInterfaceType interfaceType, @Nonnull TypeResolver typeResolver) {
        registryBuilder.typeResolver(interfaceType, typeResolver);
    }

    /**
     * Register new GraphQL type resolver for union type to schema.
     */
    public void registerTypeResolver(@Nonnull GraphQLUnionType unionType, @Nonnull TypeResolver typeResolver) {
        registryBuilder.typeResolver(unionType, typeResolver);
    }

    /**
     * Register new GraphQL data fetcher to schema.
     */
    public void registerDataFetcher(@Nonnull String objectName,
                                    @Nonnull PropertyDescriptor fieldDescriptor,
                                    @Nonnull DataFetcher<?> dataFetcher) {
        registryBuilder.dataFetcher(
            coordinates(objectName, fieldDescriptor.name()),
            dataFetcher
        );
    }

    /**
     * Register new GraphQL data fetcher to schema.
     */
    public void registerDataFetcher(@Nonnull ObjectDescriptor objectDescriptor,
                                    @Nonnull PropertyDescriptor fieldDescriptor,
                                    @Nonnull DataFetcher<?> dataFetcher) {
        registryBuilder.dataFetcher(
            coordinates(objectDescriptor.name(), fieldDescriptor.name()),
            dataFetcher
        );
    }

    /**
     * Register GraphQL field to the root query object in schema.
     */
    public void registerQueryField(@Nullable BuiltFieldDescriptor queryFieldDescriptor) {
        if (queryFieldDescriptor == null) {
            return;
        }

        Assert.isPremiseValid(
            queryFieldDescriptor.dataFetcher() != null,
            () -> new GraphQLSchemaBuildingError("Missing data fetcher for query field `" + queryFieldDescriptor.definition().getName() + "`.")
        );

        queryFields.add(queryFieldDescriptor.definition());
        registryBuilder.dataFetcher(
            coordinates("Query", queryFieldDescriptor.definition().getName()),
            queryFieldDescriptor.dataFetcher()
        );
    }

    /**
     * Register GraphQL field to the root mutation object in schema.
     */
    public void registerMutationField(@Nonnull BuiltFieldDescriptor mutationFieldDescriptor) {
        Assert.isPremiseValid(
            mutationFieldDescriptor.dataFetcher() != null,
            () -> new GraphQLSchemaBuildingError("Missing data fetcher for mutation field `" + mutationFieldDescriptor.definition().getName() + "`.")
        );

        mutationFields.add(mutationFieldDescriptor.definition());
        registryBuilder.dataFetcher(
            coordinates("Mutation", mutationFieldDescriptor.definition().getName()),
            mutationFieldDescriptor.dataFetcher()
        );
    }

    /**
     * Registers new GraphQL field to any GraphQL object with optional data fetcher.
     *
     * @param objectName name of GraphQL object to which the field will be added
     * @param objectBuilder builder of GraphQL object to which the field will be added
     * @param fieldDescriptor field to add
     */
    public void registerFieldToObject(@Nonnull String objectName,
                                      @Nonnull GraphQLObjectType.Builder objectBuilder,
                                      @Nonnull BuiltFieldDescriptor fieldDescriptor) {
        objectBuilder.field(fieldDescriptor.definition());
        if (fieldDescriptor.dataFetcher() != null) {
            registryBuilder.dataFetcher(
                coordinates(objectName, fieldDescriptor.definition().getName()),
                fieldDescriptor.dataFetcher()
            );
        }
    }

    /**
     * Registers new GraphQL field to any GraphQL object with optional data fetcher.
     *
     * @param objectDescriptor descriptor of GraphQL object to which the field will be added
     * @param objectBuilder builder of GraphQL object to which the field will be added
     * @param fieldDescriptor field to add
     */
    public void registerFieldToObject(@Nonnull ObjectDescriptor objectDescriptor,
                                      @Nonnull GraphQLObjectType.Builder objectBuilder,
                                      @Nonnull BuiltFieldDescriptor fieldDescriptor) {
        objectBuilder.field(fieldDescriptor.definition());
        if (fieldDescriptor.dataFetcher() != null) {
            registryBuilder.dataFetcher(
                coordinates(objectDescriptor.name(), fieldDescriptor.definition().getName()),
                fieldDescriptor.dataFetcher()
            );
        }
    }

    @Nonnull
    public GraphQLSchema buildGraphQLSchema() {
        final GraphQLObjectType.Builder queryObjectBuilder = newObject().name("Query");
        queryFields.forEach(queryObjectBuilder::field);
        schemaBuilder.query(queryObjectBuilder.build());

        if (!mutationFields.isEmpty()) {
            final GraphQLObjectType.Builder mutationObjectBuilder = newObject().name("Mutation");
            mutationFields.forEach(mutationObjectBuilder::field);
            schemaBuilder.mutation(mutationObjectBuilder.build());
        }

        schemaBuilder.codeRegistry(registryBuilder.build());
        return schemaBuilder.build();
    }
}
