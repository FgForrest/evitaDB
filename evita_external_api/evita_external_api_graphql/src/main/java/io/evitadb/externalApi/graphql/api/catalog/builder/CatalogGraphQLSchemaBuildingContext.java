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

package io.evitadb.externalApi.graphql.api.catalog.builder;

import graphql.schema.GraphQLInterfaceType;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.ReferenceDefinitionAttributesKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.ReferenceDefinitionKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.ReferenceWithReferencedEntityKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.WithNamedReferenceKey;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.HelperInterfaceTypeResolver;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Specific {@link GraphQLSchemaBuildingContext} for catalog-based GraphQL schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogGraphQLSchemaBuildingContext extends GraphQLSchemaBuildingContext {

	@Getter @Nonnull private final CatalogContract catalog;
	@Getter @Nonnull private final Set<Locale> supportedLocales;
	@Getter @Nonnull private final Set<Currency> supportedCurrencies;
	@Getter @Nonnull private final Collection<EntitySchemaContract> entitySchemas;

	// todo lho check count on demo dataset, predict from input schema assuming every reference is unique

	@Nullable private GraphQLInterfaceType referenceInterface = null;
	@Nonnull private final Map<ReferenceWithReferencedEntityKey, GraphQLInterfaceType> referenceWithReferencedEntityInterfaces = createHashMap(20);
	@Nonnull private final Map<ReferenceDefinitionKey, GraphQLInterfaceType> referenceDefinitionInterfaces = createHashMap(20);
	@Nonnull private final Map<ReferenceDefinitionAttributesKey, GraphQLInterfaceType> referenceDefinitionAttributesInterfaces = createHashMap(20);

	@Nonnull private final Map<WithNamedReferenceKey, GraphQLInterfaceType> withNamedReferenceInterfaces = createHashMap(20);

	@Nullable private GraphQLInterfaceType referencePageInterface = null;
	@Nonnull private final Map<ReferenceWithReferencedEntityKey, GraphQLInterfaceType> referenceWithReferencedEntityPageInterfaces = createHashMap(20);
	@Nonnull private final Map<ReferenceDefinitionKey, GraphQLInterfaceType> referenceDefinitionPageInterfaces = createHashMap(20);

	@Nullable private GraphQLInterfaceType referenceStripInterface = null;
	@Nonnull private final Map<ReferenceWithReferencedEntityKey, GraphQLInterfaceType> referenceWithReferencedEntityStripInterfaces = createHashMap(20);
	@Nonnull private final Map<ReferenceDefinitionKey, GraphQLInterfaceType> referenceDefinitionStripInterfaces = createHashMap(20);

	public CatalogGraphQLSchemaBuildingContext(@Nonnull GraphQLOptions config,
	                                           @Nonnull Evita evita,
	                                           @Nonnull CatalogContract catalog) {
		super(config, evita);
		this.catalog = catalog;
		this.supportedLocales = createHashSet(10);
		this.supportedCurrencies = createHashSet(10);

		final CatalogContract catalogContract = evita
			.getCatalogInstance(catalog.getName())
			.orElseThrow(() -> new CatalogNotFoundException(catalog.getName()));
		this.entitySchemas = catalogContract.getSchema().getEntitySchemas();
		for (EntitySchemaContract entitySchema : this.entitySchemas) {
			this.supportedLocales.addAll(entitySchema.getLocales());
			this.supportedCurrencies.addAll(entitySchema.getCurrencies());
		}
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceInterface(
		@Nonnull Supplier<GraphQLInterfaceType> referenceInterfaceBuilder
	) {
		if (this.referenceInterface == null) {
			this.referenceInterface = referenceInterfaceBuilder.get();
			this.registerType(this.referenceInterface);
			this.registerTypeResolver(this.referenceInterface, HelperInterfaceTypeResolver.getInstance());
		}
		return this.referenceInterface;
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceWithReferencedEntityInterface(
		@Nonnull ReferenceWithReferencedEntityKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceWithReferencedEntityInterfaceBuilder
	) {
		return this.referenceWithReferencedEntityInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceWithReferencedEntityInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceDefinitionInterface(
		@Nonnull ReferenceDefinitionKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceDefinitionInterfaceBuilder
	) {
		return this.referenceDefinitionInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceDefinitionInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceDefinitionAttributesInterface(
		@Nonnull ReferenceDefinitionAttributesKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceDefinitionAttributesInterfaceBuilder
	) {
		return this.referenceDefinitionAttributesInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceDefinitionAttributesInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeWithNamedReferenceInterface(
		@Nonnull WithNamedReferenceKey key,
		@Nonnull Supplier<GraphQLInterfaceType> withNamedReferenceInterfaceBuilder
	) {
		return this.withNamedReferenceInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = withNamedReferenceInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferencePageInterface(
		@Nonnull Supplier<GraphQLInterfaceType> referencePageInterfaceBuilder
	) {
		if (this.referencePageInterface == null) {
			this.referencePageInterface = referencePageInterfaceBuilder.get();
			this.registerType(this.referencePageInterface);
			this.registerTypeResolver(this.referencePageInterface, HelperInterfaceTypeResolver.getInstance());
		}
		return this.referencePageInterface;
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceWithReferencedEntityPageInterface(
		@Nonnull ReferenceWithReferencedEntityKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceWithReferencedEntityPageInterfaceBuilder
	) {
		return this.referenceWithReferencedEntityPageInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceWithReferencedEntityPageInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceDefinitionPageInterface(
		@Nonnull ReferenceDefinitionKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceDefinitionPageInterfaceBuilder
	) {
		return this.referenceDefinitionPageInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceDefinitionPageInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceStripInterface(
		@Nonnull Supplier<GraphQLInterfaceType> referenceStripInterfaceBuilder
	) {
		if (this.referenceStripInterface == null) {
			this.referenceStripInterface = referenceStripInterfaceBuilder.get();
			this.registerType(this.referenceStripInterface);
			this.registerTypeResolver(this.referenceStripInterface, HelperInterfaceTypeResolver.getInstance());
		}
		return this.referenceStripInterface;
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceWithReferencedEntityStripInterface(
		@Nonnull ReferenceWithReferencedEntityKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceWithReferencedEntityStripInterfaceBuilder
	) {
		return this.referenceWithReferencedEntityStripInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceWithReferencedEntityStripInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public GraphQLInterfaceType getOrComputeReferenceDefinitionStripInterface(
		@Nonnull ReferenceDefinitionKey key,
		@Nonnull Supplier<GraphQLInterfaceType> referenceDefinitionStripInterfaceBuilder
	) {
		return this.referenceDefinitionStripInterfaces.computeIfAbsent(
			key,
			k -> {
				final GraphQLInterfaceType newInterface = referenceDefinitionStripInterfaceBuilder.get();
				this.registerType(newInterface);
				this.registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance());
				return newInterface;
			}
		);
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return this.catalog.getSchema();
	}
}
