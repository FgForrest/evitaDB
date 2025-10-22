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

package io.evitadb.externalApi.graphql.api.builder;

import graphql.schema.*;
import graphql.schema.GraphQLSchema.Builder;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.resolver.dataFetcher.MappingTypeResolver;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLObjectType.newObject;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Generic context object for building GraphQL schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLSchemaBuildingContext {

	@Getter @Nonnull
    private final GraphQLOptions config;
    @Getter @Nonnull
    private final Evita evita;
    @Getter
    private final TracingContext tracingContext;
    @Nonnull
    private final List<GraphQLFieldDefinition> queryFields = new LinkedList<>();
    @Nonnull
    private final List<GraphQLFieldDefinition> mutationFields = new LinkedList<>();
    @Nonnull
    private final List<GraphQLFieldDefinition> subscriptionFields = new LinkedList<>();
    @Nonnull
    private final Builder schemaBuilder = GraphQLSchema.newSchema();
    @Nonnull
    private final GraphQLCodeRegistry.Builder registryBuilder = GraphQLCodeRegistry.newCodeRegistry();
    /**
     * Holds all globally registered custom enums that will be inserted into GraphQL schema.
     */
    @Nonnull
    private final Set<String> registeredCustomEnums = createHashSet(32);

	/**
	 * Holds {@link MappingTypeResolver}s whose type mappings are being build gradually with schema.
	 */
	@Nonnull
	private final Map<Class<MappingTypeResolver<?>>, MappingTypeResolver<?>> mappingTypeResolvers;

    public GraphQLSchemaBuildingContext(@Nonnull GraphQLOptions config, @Nonnull Evita evita) {
        this.config = config;
        this.evita = evita;
        this.tracingContext = TracingContextProvider.getContext();
		this.mappingTypeResolvers = createHashMap(5);
    }

    /**
     * Registers new custom enum if there is not enum with same name.
     */
    public void registerCustomEnumIfAbsent(@Nonnull GraphQLEnumType customEnum) {
        if (this.registeredCustomEnums.contains(customEnum.getName())) {
            return;
        }
	    this.registeredCustomEnums.add(customEnum.getName());
        registerType(customEnum);
    }

    /**
     * Register new GraphQL type to schema.
     */
    public void registerType(@Nonnull GraphQLType type) {
	    this.schemaBuilder.additionalType(type);
    }

    /**
     * Register new GraphQL types to schema.
     */
    public void registerTypes(@Nonnull Set<GraphQLType> types) {
	    this.schemaBuilder.additionalTypes(types);
    }

	/**
	 * Adds a new mapping type resolver to be able to add type mappings during schema building.
	 */
	public void addMappingTypeResolver(@Nonnull GraphQLInterfaceType interfaceType, @Nonnull MappingTypeResolver<?> resolver) {
		//noinspection unchecked
		this.mappingTypeResolvers.put((Class<MappingTypeResolver<?>>) resolver.getClass(), resolver);
		this.registryBuilder.typeResolver(interfaceType, resolver);
	}

	@Nonnull
	public <K, T extends MappingTypeResolver<K>> T getMappingTypeResolver(@Nonnull Class<? extends MappingTypeResolver<K>> resolverClass) {
		//noinspection unchecked
		final T resolver = (T) this.mappingTypeResolvers.get(resolverClass);
		if (resolver == null) {
			throw new GraphQLSchemaBuildingError("No mapping type resolver of type `" + resolverClass.getName() + "` is registered.");
		}
		return resolver;
	}

    /**
     * Registers a new GraphQL type resolver for a interface type to schema.
     */
    public void registerTypeResolver(@Nonnull GraphQLInterfaceType interfaceType, @Nonnull TypeResolver typeResolver) {
	    this.registryBuilder.typeResolver(interfaceType, typeResolver);
    }

    /**
     * Register new GraphQL type resolver for union type to schema.
     */
    public void registerTypeResolver(@Nonnull GraphQLUnionType unionType, @Nonnull TypeResolver typeResolver) {
	    this.registryBuilder.typeResolver(unionType, typeResolver);
    }

    /**
     * Register new GraphQL data fetcher to schema.
     */
    public void registerDataFetcher(@Nonnull String objectName,
                                    @Nonnull PropertyDescriptor fieldDescriptor,
                                    @Nonnull DataFetcher<?> dataFetcher) {
	    this.registryBuilder.dataFetcher(
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
	    this.registryBuilder.dataFetcher(
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

	    this.queryFields.add(queryFieldDescriptor.definition());
	    this.registryBuilder.dataFetcher(
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

	    this.mutationFields.add(mutationFieldDescriptor.definition());
	    this.registryBuilder.dataFetcher(
            coordinates("Mutation", mutationFieldDescriptor.definition().getName()),
            mutationFieldDescriptor.dataFetcher()
        );
    }

    /**
     * Register GraphQL field to the root subscription object in schema.
     */
    public void registerSubscriptionField(@Nonnull BuiltFieldDescriptor subscriptionFieldDescriptor) {
        Assert.isPremiseValid(
            subscriptionFieldDescriptor.dataFetcher() != null,
            () -> new GraphQLSchemaBuildingError("Missing data fetcher for subscription field `" + subscriptionFieldDescriptor.definition().getName() + "`.")
        );

	    this.subscriptionFields.add(subscriptionFieldDescriptor.definition());
	    this.registryBuilder.dataFetcher(
            coordinates("Subscription", subscriptionFieldDescriptor.definition().getName()),
            subscriptionFieldDescriptor.dataFetcher()
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
	        this.registryBuilder.dataFetcher(
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
	        this.registryBuilder.dataFetcher(
                coordinates(objectDescriptor.name(), fieldDescriptor.definition().getName()),
                fieldDescriptor.dataFetcher()
            );
        }
    }

    @Nonnull
    public GraphQLSchema buildGraphQLSchema() {
        final GraphQLObjectType.Builder queryObjectBuilder = newObject().name("Query");
	    this.queryFields.forEach(queryObjectBuilder::field);
	    this.schemaBuilder.query(queryObjectBuilder.build());

        if (!this.mutationFields.isEmpty()) {
            final GraphQLObjectType.Builder mutationObjectBuilder = newObject().name("Mutation");
	        this.mutationFields.forEach(mutationObjectBuilder::field);
	        this.schemaBuilder.mutation(mutationObjectBuilder.build());
        }
        if (!this.subscriptionFields.isEmpty()) {
            final GraphQLObjectType.Builder subscriptionObjectBuilder = newObject().name("Subscription");
	        this.subscriptionFields.forEach(subscriptionObjectBuilder::field);
	        this.schemaBuilder.subscription(subscriptionObjectBuilder.build());
        }

	    this.schemaBuilder.codeRegistry(this.registryBuilder.build());
        return this.schemaBuilder.build();
    }
}
