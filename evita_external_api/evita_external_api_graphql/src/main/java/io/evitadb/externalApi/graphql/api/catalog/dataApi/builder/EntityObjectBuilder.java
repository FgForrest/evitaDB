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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ParentsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PricesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.BigDecimalDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityDtoTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.*;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
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
		this.hierarchyRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forHierarchyRequire(
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
		final GraphQLInterfaceType entityClassifier = EntityDescriptor.THIS_CLASSIFIER.to(interfaceBuilderTransformer).build();
		buildingContext.registerType(entityClassifier);
		buildingContext.registerTypeResolver(
			entityClassifier,
			new EntityDtoTypeResolver(buildingContext.getEntityTypeToEntityObject())
		);
		buildingContext.registerType(EntityDescriptor.THIS_REFERENCE.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildPriceObject());
		buildingContext.registerType(buildGlobal());
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
			case DEFAULT -> GraphQLEntityDescriptor.THIS;
			case NON_HIERARCHICAL -> GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL;
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + variant + "`.");
		};
		final String objectName = entityDescriptor.name(entitySchema);
		final GraphQLObjectType.Builder entityObjectBuilder = entityDescriptor
			.to(objectBuilderTransformer)
			.name(objectName)
			.description(entitySchema.getDescription())
			.withInterface(typeRef(GraphQLEntityDescriptor.THIS_CLASSIFIER.name()));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			entityObjectBuilder.field(GraphQLEntityDescriptor.LOCALES.to(fieldBuilderTransformer));
			entityObjectBuilder.field(GraphQLEntityDescriptor.ALL_LOCALES.to(fieldBuilderTransformer));
		}

		// build hierarchy fields
		if (entitySchema.isWithHierarchy() && variant == EntityObjectVariant.DEFAULT) {
			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityParentPrimaryKeyField()
			);

			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityParentsField(collectionBuildingContext)
			);
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPriceForSaleField()
			);

			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPriceField()
			);

			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityPricesField()
			);

			entityObjectBuilder.field(GraphQLEntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(fieldBuilderTransformer));
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityAttributesField(collectionBuildingContext, variant)
			);
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				buildEntityAssociatedDataField(collectionBuildingContext, variant)
			);
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			final List<BuiltFieldDescriptor> referenceFieldDescriptors = buildEntityReferenceFields(collectionBuildingContext, variant);
			referenceFieldDescriptors.forEach(referenceFieldDescriptor -> buildingContext.registerFieldToObject(
				objectName,
				entityObjectBuilder,
				referenceFieldDescriptor
			));
		}

		return entityObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildGlobal() {
		final CatalogSchemaContract catalogSchema = buildingContext.getSchema();

		final GraphQLObjectType.Builder globalEntityObjectBuilder = GlobalEntityDescriptor.THIS.to(objectBuilderTransformer);

		if (!buildingContext.getSupportedLocales().isEmpty()) {
			globalEntityObjectBuilder.field(GlobalEntityDescriptor.LOCALES.to(fieldBuilderTransformer));
			globalEntityObjectBuilder.field(GlobalEntityDescriptor.ALL_LOCALES.to(fieldBuilderTransformer));
		}

		if (!catalogSchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
				GlobalEntityDescriptor.THIS,
				globalEntityObjectBuilder,
				buildGlobalEntityAttributesField()
			);
		}

		buildingContext.registerDataFetcher(
			GlobalEntityDescriptor.THIS,
			GlobalEntityDescriptor.TARGET_ENTITY,
			new TargetEntityDataFetcher()
		);

		return globalEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentPrimaryKeyField() {
		return new BuiltFieldDescriptor(
			GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.to(fieldBuilderTransformer).build(),
			new ParentPrimaryKeyDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildNonHierarchicalEntityParentPrimaryKeyField() {
		return new BuiltFieldDescriptor(
			GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.to(fieldBuilderTransformer).build(),
			new NonHierarchicalParentPrimaryKeyDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentsField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final DataLocator selfHierarchyConstraintDataLocator = new HierarchyDataLocator(entitySchema.getName());
		final GraphQLInputType stopAtConstraint = hierarchyRequireConstraintSchemaBuilder.build(
			selfHierarchyConstraintDataLocator,
			HierarchyStopAt.class
		);

		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PARENTS
			.to(fieldBuilderTransformer)
			.type(list(nonNull(typeRef(GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL.name(entitySchema)))))
			.argument(ParentsFieldHeaderDescriptor.STOP_AT
				.to(argumentBuilderTransformer)
				.type(stopAtConstraint))
			.build();

		return new BuiltFieldDescriptor(
			field,
			new ParentsDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceForSaleField() {
		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PRICE_FOR_SALE
			.to(fieldBuilderTransformer)
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(CURRENCY_ENUM.name())))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_IN
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_NOW
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())))
			.build();

		return new BuiltFieldDescriptor(field, new PriceForSaleDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceField() {
		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PRICE
			.to(fieldBuilderTransformer)
			.argument(PriceFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(CURRENCY_ENUM.name())))
			.argument(PriceFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())))
			.build();

		return new BuiltFieldDescriptor(field, new PriceDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPricesField() {
		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PRICES
			.to(fieldBuilderTransformer)
			.argument(PricesFieldHeaderDescriptor.PRICE_LISTS
				.to(argumentBuilderTransformer))
			.argument(PricesFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(CURRENCY_ENUM.name())))
			.argument(PricesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())))
			.build();

		return new BuiltFieldDescriptor(field, new PricesDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildGlobalEntityAttributesField() {
		final CatalogSchemaContract catalogSchema = buildingContext.getSchema();
		final GraphQLType attributesObject = buildAttributesObject(
			catalogSchema.getAttributes().values(),
			AttributesDescriptor.THIS_GLOBAL.name(),
			false
		);

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = GraphQLEntityDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributesObject));

		if (!buildingContext.getSupportedLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			new AttributesDataFetcher()
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

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = GraphQLEntityDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(attributesObject);

		if (!entitySchema.getLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			new AttributesDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributesObject(@Nonnull Collection<? extends AttributeSchemaContract> attributeSchemas,
	                                                @Nonnull String objectName,
	                                                boolean attributesCanBeRequired) {
		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema ->
			buildingContext.registerFieldToObject(
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
				new NullableBigDecimalFieldDecorator(argumentBuilderTransformer).accept(attributeFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(argumentBuilderTransformer).accept(attributeFieldBuilder);
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

		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = GraphQLEntityDescriptor.ASSOCIATED_DATA
			.to(fieldBuilderTransformer)
			.type(associatedDataObject);

		if (!entitySchema.getLocales().isEmpty()) {
			associatedDataFieldBuilder.argument(AssociatedDataFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(LOCALE_ENUM.name())));
		}

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			new AssociatedDataDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAssociatedDataObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final String objectName = AssociatedDataDescriptor.THIS.name(collectionBuildingContext.getSchema());
		final GraphQLObjectType.Builder associatedDataBuilder = AssociatedDataDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		collectionBuildingContext.getSchema().getAssociatedData().values().forEach(associatedDataSchema ->
			buildingContext.registerFieldToObject(
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
				new NullableBigDecimalFieldDecorator(argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			}

			associatedDataFieldDataFetcher = new ReadDataFetcher(
				new BigDecimalDataFetcher(
					new AssociatedDataValueDataFetcher<>(cdoObjectMapper, associatedDataSchema)
				),
				buildingContext.getTracingContext(),
				buildingContext.getEvitaExecutor().orElse(null)
			);
		} else {
			associatedDataFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(associatedDataType, !associatedDataSchema.isNullable())
			);
			associatedDataFieldDataFetcher = new ReadDataFetcher(
				new AssociatedDataValueDataFetcher<>(cdoObjectMapper, associatedDataSchema),
				buildingContext.getTracingContext(),
				buildingContext.getEvitaExecutor().orElse(null)
			);
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
			.map(referenceSchema -> {
				final GraphQLOutputType referenceObject = switch (version) {
					case DEFAULT -> buildReferenceObject(collectionBuildingContext, referenceSchema);
					case NON_HIERARCHICAL -> typeRef(ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema));
					default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
				};

				final ReferenceDataLocator referenceDataLocator = new ReferenceDataLocator(
					collectionBuildingContext.getSchema().getName(),
					referenceSchema.getName()
				);
				final GraphQLInputType referenceFilter = filterConstraintSchemaBuilder.build(referenceDataLocator);
				final GraphQLInputType referenceOrder = orderConstraintSchemaBuilder.build(referenceDataLocator);

				final GraphQLFieldDefinition.Builder referenceFieldBuilder = newFieldDefinition()
					.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
					.description(referenceSchema.getDescription())
					.deprecate(referenceSchema.getDeprecationNotice())
					.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
						.to(argumentBuilderTransformer)
						.type(referenceFilter))
					.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
						.to(argumentBuilderTransformer)
						.type(referenceOrder));

				switch (referenceSchema.getCardinality()) {
					case ZERO_OR_ONE -> referenceFieldBuilder.type(referenceObject);
					case EXACTLY_ONE -> referenceFieldBuilder.type(nonNull(referenceObject));
					case ZERO_OR_MORE, ONE_OR_MORE -> referenceFieldBuilder.type(nonNull(list(nonNull(referenceObject))));
				}

				final DataFetcher<?> referenceDataFetcher = switch (referenceSchema.getCardinality()) {
					case ZERO_OR_ONE, EXACTLY_ONE -> new ReferenceDataFetcher(referenceSchema);
					case ZERO_OR_MORE, ONE_OR_MORE -> new ReferencesDataFetcher(referenceSchema);
				};

				return new BuiltFieldDescriptor(
					referenceFieldBuilder.build(),
					referenceDataFetcher
				);
			})
			.toList();
	}

	@Nonnull
	private GraphQLObjectType buildReferenceObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceObjectName = ReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema);

		final GraphQLObjectType.Builder referenceObjectBuilder = ReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(referenceObjectName)
			.description(referenceSchema.getDescription());

		buildingContext.registerFieldToObject(
			referenceObjectName,
			referenceObjectBuilder,
			buildReferenceReferencedEntityField(referenceSchema)
		);

		if (referenceSchema.getReferencedGroupType() != null) {
			buildingContext.registerFieldToObject(
				referenceObjectName,
				referenceObjectBuilder,
				buildReferenceGroupEntityField(referenceSchema)
			);
		}

		if (!referenceSchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
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

		final GraphQLFieldDefinition attributesField = ReferenceDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(attributesObject)
			.build();

		return new BuiltFieldDescriptor(
			attributesField,
			new AttributesDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceReferencedEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			referencedEntitySchema = buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.REFERENCED_ENTITY
			.to(fieldBuilderTransformer)
			.type(referencedEntityObject)
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			new ReferencedEntityDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceGroupEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			referencedEntitySchema = buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedGroupType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.GROUP_ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			new ReferencedGroupDataFetcher()
		);
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(GraphQLEntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(GraphQLEntityDescriptor.THIS_REFERENCE.name());
		}
	}


	@Nonnull
	private GraphQLObjectType buildPriceObject() {
		buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITH_TAX,
			new PriceBigDecimalDataFetcher(PriceDescriptor.PRICE_WITH_TAX.name())
		);
		buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITHOUT_TAX,
			new PriceBigDecimalDataFetcher(PriceDescriptor.PRICE_WITHOUT_TAX.name())
		);
		buildingContext.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.TAX_RATE,
			new PriceBigDecimalDataFetcher(PriceDescriptor.TAX_RATE.name())
		);

		return PriceDescriptor.THIS
			.to(objectBuilderTransformer)
			.field(PriceDescriptor.PRICE_WITHOUT_TAX.to(fieldBuilderTransformer.with(priceFieldDecorator)))
			.field(PriceDescriptor.PRICE_WITH_TAX.to(fieldBuilderTransformer.with(priceFieldDecorator)))
			.field(PriceDescriptor.TAX_RATE.to(fieldBuilderTransformer.with(priceFieldDecorator)))
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
