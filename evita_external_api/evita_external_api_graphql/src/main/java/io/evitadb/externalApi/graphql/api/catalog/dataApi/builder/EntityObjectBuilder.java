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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.InlineReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferencePageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceStripDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.PaginatedListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.StripListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.BigDecimalDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityDtoTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.*;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Builds object representing specific {@link io.evitadb.api.requestResponse.data.EntityContract} of specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntityObjectBuilder {

	private static final PriceBigDecimalDataFetcher PRICE_WITH_VAT_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.PRICE_WITH_TAX.name());
	private static final PriceBigDecimalDataFetcher PRICE_WITHOUT_VAT_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.PRICE_WITHOUT_TAX.name());
	private static final PriceBigDecimalDataFetcher TAX_RATE_DATA_FETCHER = new PriceBigDecimalDataFetcher(
		PriceDescriptor.TAX_RATE.name());

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder hierarchyRequireConstraintSchemaBuilder;
	@Nonnull private final ObjectMapper cdoObjectMapper;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final PriceBigDecimalFieldDecorator priceFieldDecorator;

	public EntityObjectBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext buildingContext,
							   @Nonnull GraphQLConstraintSchemaBuildingContext constraintSchemaBuildingContext,
	                           @Nonnull FilterConstraintSchemaBuilder filterConstraintSchemaBuilder,
	                           @Nonnull OrderConstraintSchemaBuilder orderConstraintSchemaBuilder,
	                           @Nonnull ObjectMapper cdoObjectMapper,
	                           @Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer,
	                           @Nonnull ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer,
	                           @Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer,
	                           @Nonnull PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer) {
		this.buildingContext = buildingContext;
		this.filterConstraintSchemaBuilder = filterConstraintSchemaBuilder;
		this.orderConstraintSchemaBuilder = orderConstraintSchemaBuilder;
		this.hierarchyRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forComplementaryRequire(
			constraintSchemaBuildingContext,
			new AtomicReference<>(filterConstraintSchemaBuilder)
		);
		this.cdoObjectMapper = cdoObjectMapper;
		this.argumentBuilderTransformer = argumentBuilderTransformer;
		this.interfaceBuilderTransformer = interfaceBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;
		this.fieldBuilderTransformer = fieldBuilderTransformer;

		this.priceFieldDecorator = new PriceBigDecimalFieldDecorator(argumentBuilderTransformer);
	}

	public void buildCommonTypes() {
		final GraphQLInterfaceType entityClassifier = EntityDescriptor.THIS_CLASSIFIER.to(this.interfaceBuilderTransformer).build();
		this.buildingContext.registerType(entityClassifier);
		this.buildingContext.registerTypeResolver(
			entityClassifier,
			new EntityDtoTypeResolver(this.buildingContext.getEntityTypeToEntityObject())
		);
		this.buildingContext.registerType(EntityDescriptor.THIS_REFERENCE.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildGlobalEntity());
		if (!this.buildingContext.getSupportedCurrencies().isEmpty()) {
			this.buildingContext.registerType(buildPriceObject());
			this.buildingContext.registerType(buildPriceForSaleObject());
		}
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		return build(collectionBuildingContext, EntityObjectVariant.DEFAULT);
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                               @Nonnull EntityObjectVariant variant) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		// build specific entity object
		final ObjectDescriptor entityDescriptor = switch (variant) {
			case DEFAULT -> EntityDescriptor.THIS;
			case NON_HIERARCHICAL -> GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL;
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + variant + "`.");
		};
		final String objectName = entityDescriptor.name(entitySchema);
		final GraphQLObjectType.Builder entityObjectBuilder = entityDescriptor
			.to(this.objectBuilderTransformer)
			.name(objectName)
			.description(entitySchema.getDescription())
			.withInterface(typeRef(EntityDescriptor.THIS_CLASSIFIER.name()));

		// build top level fields
		entityObjectBuilder.field(EntityDescriptor.SCOPE.to(this.fieldBuilderTransformer));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			entityObjectBuilder.field(EntityDescriptor.LOCALES.to(this.fieldBuilderTransformer));
			entityObjectBuilder.field(EntityDescriptor.ALL_LOCALES.to(this.fieldBuilderTransformer));
		}

		// build hierarchy fields
		if (entitySchema.isWithHierarchy() && variant == EntityObjectVariant.DEFAULT) {
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityParentPrimaryKeyField()
			);

			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityParentsField(collectionBuildingContext)
			);
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPriceForSaleField()
			);
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityMultiplePricesForSaleAvailableField()
			);
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityAllPricesForSaleField()
			);

			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPriceField()
			);

			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPricesField()
			);

			entityObjectBuilder.field(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(this.fieldBuilderTransformer));
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityAttributesField(collectionBuildingContext, variant)
			);
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityAssociatedDataField(collectionBuildingContext, variant)
			);
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			final List<BuiltFieldDescriptor> referenceFieldDescriptors = buildEntityReferenceFields(collectionBuildingContext, variant);
			referenceFieldDescriptors.forEach(referenceFieldDescriptor -> this.buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				referenceFieldDescriptor
			));
		}

		return entityObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildGlobalEntity() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();

		final GraphQLObjectType.Builder globalEntityObjectBuilder = GlobalEntityDescriptor.THIS.to(this.objectBuilderTransformer);

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			globalEntityObjectBuilder.field(EntityDescriptor.LOCALES.to(this.fieldBuilderTransformer));
			globalEntityObjectBuilder.field(EntityDescriptor.ALL_LOCALES.to(this.fieldBuilderTransformer));
		}

		if (!catalogSchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				GlobalEntityDescriptor.THIS,
				globalEntityObjectBuilder,
				buildGlobalEntityAttributesField()
			);
		}

		this.buildingContext.registerDataFetcher(
			GlobalEntityDescriptor.THIS,
			GlobalEntityDescriptor.TARGET_ENTITY,
			TargetEntityDataFetcher.getInstance()
		);

		return globalEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentPrimaryKeyField() {
		return new BuiltFieldDescriptor(
			GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.to(this.fieldBuilderTransformer).build(),
			ParentPrimaryKeyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentsField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final DataLocator selfHierarchyConstraintDataLocator = new HierarchyDataLocator(
			new ManagedEntityTypePointer(entitySchema.getName())
		);
		final GraphQLInputType stopAtConstraint = this.hierarchyRequireConstraintSchemaBuilder.build(
			selfHierarchyConstraintDataLocator,
			HierarchyStopAt.class
		);

		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PARENTS
			.to(this.fieldBuilderTransformer)
			.type(list(nonNull(typeRef(GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL.name(entitySchema)))))
			.argument(ParentsFieldHeaderDescriptor.STOP_AT
				.to(this.argumentBuilderTransformer)
				.type(stopAtConstraint))
			.build();

		return new BuiltFieldDescriptor(
			field,
			ParentsDataFetcher.getInstance()
		);
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

	@Nonnull
	private BuiltFieldDescriptor buildGlobalEntityAttributesField() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();
		final GraphQLType attributesObject = buildAttributesObject(
			catalogSchema.getAttributes().values(),
			AttributesDescriptor.THIS_GLOBAL.name(),
			false
		);

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(nonNull(attributesObject));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(this.argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			AttributesDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAttributesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                        @Nonnull EntityObjectVariant version) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLOutputType attributesObject = switch (version) {
			case DEFAULT -> buildAttributesObject(
				entitySchema.getAttributes().values(),
				AttributesDescriptor.THIS.name(entitySchema),
				true
			);
			case NON_HIERARCHICAL -> typeRef(AttributesDescriptor.THIS.name(entitySchema));
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
		};

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(attributesObject);

		if (!entitySchema.getLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(this.argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			AttributesDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributesObject(@Nonnull Collection<? extends AttributeSchemaContract> attributeSchemas,
	                                                @Nonnull String objectName,
	                                                boolean attributesCanBeRequired) {
		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema ->
			this.buildingContext.registerFieldToObject(
				objectName,
				attributesBuilder,
				buildAttributeField(attributeSchema, attributesCanBeRequired)
			)
		);

		return attributesBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildAttributeField(@Nonnull AttributeSchemaContract attributeSchema, boolean canBeRequired) {
		final GraphQLFieldDefinition.Builder attributeFieldBuilder = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice());
		final DataFetcher<?> attributeFieldDataFetcher;

		final Class<? extends Serializable> attributeType = attributeSchema.getType();
		if (BigDecimal.class.isAssignableFrom(attributeType)) {
			if (attributeSchema.isNullable() || !canBeRequired) {
				new NullableBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(attributeFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(attributeFieldBuilder);
			}

			attributeFieldDataFetcher = new BigDecimalDataFetcher(new AttributeValueDataFetcher<>(attributeSchema));
		} else {
			attributeFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(attributeType, canBeRequired && !attributeSchema.isNullable())
			);
			attributeFieldDataFetcher = new AttributeValueDataFetcher<>(attributeSchema);
		}

		return new BuiltFieldDescriptor(
			attributeFieldBuilder.build(),
			attributeFieldDataFetcher
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAssociatedDataField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                            @Nonnull EntityObjectVariant version) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLOutputType associatedDataObject = switch (version) {
			case DEFAULT -> buildAssociatedDataObject(collectionBuildingContext);
			case NON_HIERARCHICAL -> typeRef(AssociatedDataDescriptor.THIS.name(collectionBuildingContext.getSchema()));
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
		};

		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = EntityDescriptor.ASSOCIATED_DATA
			.to(this.fieldBuilderTransformer)
			.type(associatedDataObject);

		if (!entitySchema.getLocales().isEmpty()) {
			associatedDataFieldBuilder.argument(AssociatedDataFieldHeaderDescriptor.LOCALE
				.to(this.argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			AssociatedDataDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAssociatedDataObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final String objectName = AssociatedDataDescriptor.THIS.name(collectionBuildingContext.getSchema());
		final GraphQLObjectType.Builder associatedDataBuilder = AssociatedDataDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		collectionBuildingContext.getSchema().getAssociatedData().values().forEach(associatedDataSchema ->
			this.buildingContext.registerFieldToObject(
				objectName,
				associatedDataBuilder,
				buildSingleAssociatedDataField(associatedDataSchema)
			)
		);

		return associatedDataBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSingleAssociatedDataField(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = newFieldDefinition();
		final DataFetcher<?> associatedDataFieldDataFetcher;

		final Class<? extends Serializable> associatedDataType = associatedDataSchema.getType();
		if (BigDecimal.class.isAssignableFrom(associatedDataType)) {
			if (associatedDataSchema.isNullable()) {
				new NullableBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			}

			associatedDataFieldDataFetcher = new BigDecimalDataFetcher(
				new AssociatedDataValueDataFetcher<>(this.cdoObjectMapper, associatedDataSchema)
			);
		} else {
			associatedDataFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(associatedDataType, !associatedDataSchema.isNullable())
			);
			associatedDataFieldDataFetcher = new AssociatedDataValueDataFetcher<>(this.cdoObjectMapper, associatedDataSchema);
		}

		associatedDataFieldBuilder
			.name(associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecate(associatedDataSchema.getDeprecationNotice());

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			associatedDataFieldDataFetcher
		);
	}

	@Nonnull
	private List<BuiltFieldDescriptor> buildEntityReferenceFields(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                              @Nonnull EntityObjectVariant version) {
		final Collection<ReferenceSchemaContract> referenceSchemas = collectionBuildingContext.getSchema().getReferences().values();

		return referenceSchemas.stream()
			.flatMap(referenceSchema -> {
				final List<BuiltFieldDescriptor> fields = new ArrayList<>(3);

				final GraphQLOutputType referenceObject = switch (version) {
					case DEFAULT -> {
						final GraphQLObjectType object = buildReferenceObject(collectionBuildingContext, referenceSchema);
						this.buildingContext.registerType(object);
						yield object;
					}
					case NON_HIERARCHICAL ->
						typeRef(ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema));
					default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
				};

				// inline query args
				final InlineReferenceDataLocator referenceDataLocator = new InlineReferenceDataLocator(
					new ManagedEntityTypePointer(collectionBuildingContext.getSchema().getName()),
					referenceSchema.getName()
				);
				final GraphQLInputType referenceFilter = this.filterConstraintSchemaBuilder.build(referenceDataLocator);
				final GraphQLInputType referenceOrder = this.orderConstraintSchemaBuilder.build(referenceDataLocator);

				final Cardinality referenceCardinality = referenceSchema.getCardinality();
				final boolean referenceIsList = referenceCardinality.getMax() > 1;

				{ // base reference field
					final GraphQLFieldDefinition.Builder referenceFieldBuilder = EntityDescriptor.REFERENCE
						.to(this.fieldBuilderTransformer)
						.name(EntityDescriptor.REFERENCE.name(referenceSchema))
						.description(referenceSchema.getDescription())
						.deprecate(referenceSchema.getDeprecationNotice())
						.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
							.to(this.argumentBuilderTransformer)
							.type(referenceFilter))
						.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
							.to(this.argumentBuilderTransformer)
							.type(referenceOrder));

					if (referenceIsList) {
						referenceFieldBuilder.argument(ReferencesFieldHeaderDescriptor.LIMIT
							.to(this.argumentBuilderTransformer));
					}

					final DataFetcher<?> referenceDataFetcher;
					if (referenceCardinality.getMax() > 1) {
						referenceFieldBuilder.type(nonNull(list(nonNull(referenceObject))));
						referenceDataFetcher = new ReferencesDataFetcher(referenceSchema);
					} else if (referenceCardinality.getMin() == 1) {
						referenceFieldBuilder.type(nonNull(referenceObject));
						referenceDataFetcher = new ReferenceDataFetcher(referenceSchema);
					} else {
						referenceFieldBuilder.type(referenceObject);
						referenceDataFetcher = new ReferenceDataFetcher(referenceSchema);
					}

					fields.add(new BuiltFieldDescriptor(
						referenceFieldBuilder.build(),
						referenceDataFetcher
					));
				}

				{ // chunked reference fields
					if (EntityObjectVariant.DEFAULT.equals(version) && referenceIsList) {
						fields.add(new BuiltFieldDescriptor(
							EntityDescriptor.REFERENCE_PAGE
								.to(this.fieldBuilderTransformer)
								.name(EntityDescriptor.REFERENCE_PAGE.name(referenceSchema))
								.description(referenceSchema.getDescription())
								.deprecate(referenceSchema.getDeprecationNotice())
								.type(buildReferencePageObject(collectionBuildingContext, referenceSchema))
								.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
									.to(this.argumentBuilderTransformer)
									.type(referenceFilter))
								.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
									.to(this.argumentBuilderTransformer)
									.type(referenceOrder))
								.argument(PaginatedListFieldHeaderDescriptor.NUMBER
									.to(this.argumentBuilderTransformer))
								.argument(PaginatedListFieldHeaderDescriptor.SIZE
									.to(this.argumentBuilderTransformer))
								.build(),
							new ReferencePageDataFetcher(referenceSchema)
						));

						fields.add(new BuiltFieldDescriptor(
							EntityDescriptor.REFERENCE_STRIP
								.to(this.fieldBuilderTransformer)
								.name(EntityDescriptor.REFERENCE_STRIP.name(referenceSchema))
								.description(referenceSchema.getDescription())
								.deprecate(referenceSchema.getDeprecationNotice())
								.type(buildReferenceStripObject(collectionBuildingContext, referenceSchema))
								.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
									.to(this.argumentBuilderTransformer)
									.type(referenceFilter))
								.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
									.to(this.argumentBuilderTransformer)
									.type(referenceOrder))
								.argument(StripListFieldHeaderDescriptor.OFFSET
									.to(this.argumentBuilderTransformer))
								.argument(StripListFieldHeaderDescriptor.LIMIT
									.to(this.argumentBuilderTransformer))
								.build(),
							new ReferenceStripDataFetcher(referenceSchema)
						));
					}
				}

				return fields.stream();
			})
			.toList();
	}

	@Nonnull
	private GraphQLObjectType buildReferenceObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceObjectName = ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema);

		final GraphQLObjectType.Builder referenceObjectBuilder = ReferenceDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(referenceObjectName)
			.description(referenceSchema.getDescription());

		this.buildingContext.registerFieldToObject(
			referenceObjectName,
			referenceObjectBuilder,
			buildReferenceReferencedEntityField(referenceSchema)
		);

		if (referenceSchema.getReferencedGroupType() != null) {
			this.buildingContext.registerFieldToObject(
				referenceObjectName,
				referenceObjectBuilder,
				buildReferenceGroupEntityField(referenceSchema)
			);
		}

		if (!referenceSchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				referenceObjectName,
				referenceObjectBuilder,
				buildReferenceAttributesField(collectionBuildingContext, referenceSchema)
			);
		}

		return referenceObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceAttributesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                           @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceAttributesObjectName = AttributesDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema);

		final GraphQLObjectType attributesObject = buildAttributesObject(
			referenceSchema.getAttributes().values(),
			referenceAttributesObjectName,
			true
		);

		final GraphQLFieldDefinition attributesField = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(attributesObject)
			.build();

		return new BuiltFieldDescriptor(
			attributesField,
			AttributesDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceReferencedEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			referencedEntitySchema = this.buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.REFERENCED_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(referencedEntityObject)
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			ReferencedEntityDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceGroupEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			referencedEntitySchema = this.buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(Objects.requireNonNull(referenceSchema.getReferencedGroupType()));
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.GROUP_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			ReferencedGroupDataFetcher.getInstance()
		);
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}
	}

	@Nonnull
	private GraphQLObjectType buildReferencePageObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                   @Nonnull ReferenceSchemaContract referenceSchema) {
		return ReferencePageDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(ReferencePageDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema))
			.description(referenceSchema.getDescription())
			.field(DataChunkDescriptor.DATA
				.to(this.fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema)))))))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildReferenceStripObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                    @Nonnull ReferenceSchemaContract referenceSchema) {
		return ReferenceStripDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(ReferenceStripDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema))
			.description(referenceSchema.getDescription())
			.field(DataChunkDescriptor.DATA
				.to(this.fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema)))))))
			.build();
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

	/**
	 * Defines if entity object will have all possible fields for specified schema or there will be some restrictions.
	 */
	public enum EntityObjectVariant {
		/**
		 * Full entity object with all possible fields.
		 */
		DEFAULT,
		/**
		 * Restricted entity object which is same as {@link #DEFAULT} only without list of parent entities so that it
		 * cannot form recursive structure.
		 */
		NON_HIERARCHICAL
	}
}
