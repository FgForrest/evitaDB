/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLObjectType.Builder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.PriceBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AccompanyingPriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.MultiplePricesForSaleAvailableFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PricesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AccompanyingPriceDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AllPricesForSaleDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.MultiplePricesForSaleAvailableDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.PriceBigDecimalDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.PriceDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.PriceForSaleDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.PricesDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Decorates entity objects with price fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class EntityObjectPriceDecorator implements EntityObjectDecorator {

	private static final PriceBigDecimalDataFetcher PRICE_WITH_VAT_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.PRICE_WITH_TAX.name());
	private static final PriceBigDecimalDataFetcher PRICE_WITHOUT_VAT_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.PRICE_WITHOUT_TAX.name());
	private static final PriceBigDecimalDataFetcher TAX_RATE_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.TAX_RATE.name());

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final PriceBigDecimalFieldDecorator priceFieldDecorator;

	public EntityObjectPriceDecorator(
		@Nonnull CatalogGraphQLSchemaBuildingContext buildingContext,
		@Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer,
		@Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer,
		@Nonnull PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer
	) {
		this.buildingContext = buildingContext;
		this.argumentBuilderTransformer = argumentBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;
		this.fieldBuilderTransformer = fieldBuilderTransformer;

		this.priceFieldDecorator = new PriceBigDecimalFieldDecorator(this.argumentBuilderTransformer);
	}

	@Override
	public void prepare() {
		if (!this.buildingContext.getSupportedCurrencies().isEmpty()) {
			this.buildingContext.registerType(buildPriceObject());
			this.buildingContext.registerType(buildPriceForSaleObject());
		}
	}

	@Override
	public void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull Builder entityObjectBuilder
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		if (!entitySchema.getCurrencies().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityPriceForSaleField()
			);
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityMultiplePricesForSaleAvailableField()
			);
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityAllPricesForSaleField()
			);

			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityPriceField()
			);

			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityPricesField()
			);

			entityObjectBuilder.field(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(this.fieldBuilderTransformer));
		}
	}

	@Nonnull
	private GraphQLObjectType buildPriceForSaleObject() {
		this.buildingContext.registerDataFetcher(
			PriceForSaleDescriptor.THIS,
			PriceDescriptor.PRICE_WITH_TAX,
			PRICE_WITH_VAT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			PriceForSaleDescriptor.THIS,
			PriceDescriptor.PRICE_WITHOUT_TAX,
			PRICE_WITHOUT_VAT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			PriceForSaleDescriptor.THIS,
			PriceDescriptor.TAX_RATE,
			TAX_RATE_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			PriceForSaleDescriptor.THIS,
			PriceForSaleDescriptor.ACCOMPANYING_PRICE,
			AccompanyingPriceDataFetcher.getInstance()
		);

		return PriceForSaleDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(PriceDescriptor.PRICE_WITHOUT_TAX.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.field(PriceDescriptor.PRICE_WITH_TAX.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.field(PriceDescriptor.TAX_RATE.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.field(PriceForSaleDescriptor.ACCOMPANYING_PRICE.to(this.fieldBuilderTransformer)
				       .argument(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS
					                 .to(this.argumentBuilderTransformer)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildPriceObject() {
		this.buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITH_TAX,
			PRICE_WITH_VAT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITHOUT_TAX,
			PRICE_WITHOUT_VAT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.TAX_RATE,
			TAX_RATE_DATA_FETCHER
		);

		return PriceDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(PriceDescriptor.PRICE_WITHOUT_TAX.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.field(PriceDescriptor.PRICE_WITH_TAX.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.field(PriceDescriptor.TAX_RATE.to(this.fieldBuilderTransformer.with(this.priceFieldDecorator)))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceForSaleField() {
		final GraphQLFieldDefinition.Builder fieldBuilder = GraphQLEntityDescriptor.PRICE_FOR_SALE
			.to(this.fieldBuilderTransformer)
			// TOBEDONE #538: deprecated, remove
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LISTS
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.CURRENCY
				          .to(this.argumentBuilderTransformer)
				          .type(typeRef(CURRENCY_ENUM.name())))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_IN
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_NOW
				          .to(this.argumentBuilderTransformer));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			fieldBuilder.argument(PriceForSaleFieldHeaderDescriptor.LOCALE
				                      .to(this.argumentBuilderTransformer)
				                      .type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(fieldBuilder.build(), PriceForSaleDataFetcher.getInstance());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityMultiplePricesForSaleAvailableField() {
		return new BuiltFieldDescriptor(
			EntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE
				.to(this.fieldBuilderTransformer)
				.argument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.PRICE_LISTS
					          .to(this.argumentBuilderTransformer))
				.argument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.CURRENCY
					          .to(this.argumentBuilderTransformer)
					          .type(typeRef(CURRENCY_ENUM.name())))
				.argument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.VALID_IN
					          .to(this.argumentBuilderTransformer))
				.argument(MultiplePricesForSaleAvailableFieldHeaderDescriptor.VALID_NOW
					          .to(this.argumentBuilderTransformer))
				.build(),
			MultiplePricesForSaleAvailableDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAllPricesForSaleField() {
		final GraphQLFieldDefinition.Builder fieldBuilder = GraphQLEntityDescriptor.ALL_PRICES_FOR_SALE
			.to(this.fieldBuilderTransformer)
			// TOBEDONE #538: deprecated, remove
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LISTS
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.CURRENCY
				          .to(this.argumentBuilderTransformer)
				          .type(typeRef(CURRENCY_ENUM.name())))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_IN
				          .to(this.argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_NOW
				          .to(this.argumentBuilderTransformer));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			fieldBuilder.argument(PriceForSaleFieldHeaderDescriptor.LOCALE
				                      .to(this.argumentBuilderTransformer)
				                      .type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(fieldBuilder.build(), AllPricesForSaleDataFetcher.getInstance());
	}

	// TOBEDONE #538: deprecated, remove
	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceField() {
		final GraphQLFieldDefinition.Builder fieldBuilder = GraphQLEntityDescriptor.PRICE
			.to(this.fieldBuilderTransformer)
			.argument(PriceFieldHeaderDescriptor.PRICE_LIST
				          .to(this.argumentBuilderTransformer))
			.argument(PriceFieldHeaderDescriptor.CURRENCY
				          .to(this.argumentBuilderTransformer)
				          .type(typeRef(CURRENCY_ENUM.name())));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			fieldBuilder.argument(PriceFieldHeaderDescriptor.LOCALE
				                      .to(this.argumentBuilderTransformer)
				                      .type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(fieldBuilder.build(), PriceDataFetcher.getInstance());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPricesField() {
		final GraphQLFieldDefinition.Builder fieldBuilder = EntityDescriptor.PRICES
			.to(this.fieldBuilderTransformer)
			.argument(PricesFieldHeaderDescriptor.PRICE_LISTS
				          .to(this.argumentBuilderTransformer))
			.argument(PricesFieldHeaderDescriptor.CURRENCY
				          .to(this.argumentBuilderTransformer)
				          .type(typeRef(CURRENCY_ENUM.name())));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			fieldBuilder.argument(PricesFieldHeaderDescriptor.LOCALE
				                      .to(this.argumentBuilderTransformer)
				                      .type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(fieldBuilder.build(), PricesDataFetcher.getInstance());
	}
}
