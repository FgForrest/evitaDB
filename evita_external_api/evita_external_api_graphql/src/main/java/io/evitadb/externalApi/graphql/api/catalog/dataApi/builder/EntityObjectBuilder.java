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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PricesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.*;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;

/**
 * Builds object representing specific {@link io.evitadb.api.requestResponse.data.EntityContract} of specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class EntityObjectBuilder {

	private static final PriceBigDecimalFieldDecorator PRICE_FIELD_DECORATOR = new PriceBigDecimalFieldDecorator();

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectMapper cdoObjectMapper;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(objectBuilderTransformer).build());

		final GraphQLInterfaceType entityClassifierInterface = EntityDescriptor.THIS_INTERFACE.to(interfaceBuilderTransformer).build();
		buildingContext.registerType(entityClassifierInterface);
		buildingContext.registerTypeResolver(
			entityClassifierInterface,
			new EntityDtoTypeResolver(buildingContext.getEntityTypeToEntityObject())
		);

		buildingContext.registerType(HierarchicalPlacementDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildPriceObject());
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		// build specific entity object
		final GraphQLObjectType.Builder entityObjectBuilder = EntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION))
			.description(entitySchema.getDescription())
			.withInterface(typeRef(EntityDescriptor.THIS_INTERFACE.name()));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			entityObjectBuilder.field(EntityDescriptor.LOCALES.to(fieldBuilderTransformer));
			entityObjectBuilder.field(EntityDescriptor.ALL_LOCALES.to(fieldBuilderTransformer));
		}

		// build hierarchy placement field
		if (entitySchema.isWithHierarchy()) {
			entityObjectBuilder.field(EntityDescriptor.HIERARCHICAL_PLACEMENT.to(fieldBuilderTransformer));
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				buildEntityPriceForSaleField(collectionBuildingContext)
			);

			collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				buildEntityPriceField(collectionBuildingContext)
			);

			collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				buildEntityPricesField(collectionBuildingContext)
			);

			entityObjectBuilder.field(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(fieldBuilderTransformer));
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				buildEntityAttributesField(collectionBuildingContext)
			);
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				buildEntityAssociatedDataField(collectionBuildingContext)
			);
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			final List<BuiltFieldDescriptor> referenceFieldDescriptors = buildEntityReferenceFields(collectionBuildingContext);
			referenceFieldDescriptors.forEach(referenceFieldDescriptor -> collectionBuildingContext.registerEntityField(
				entityObjectBuilder,
				referenceFieldDescriptor
			));
		}

		return entityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceForSaleField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICE_FOR_SALE
			.to(fieldBuilderTransformer)
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_IN
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_NOW
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PriceForSaleDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICE
			.to(fieldBuilderTransformer)
			.argument(PriceFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PriceFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PriceDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPricesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICES
			.to(fieldBuilderTransformer)
			.argument(PricesFieldHeaderDescriptor.PRICE_LISTS
				.to(argumentBuilderTransformer))
			.argument(PricesFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PricesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PricesDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAttributesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLObjectType attributesObject = buildAttributesObject(
			entitySchema.getAttributes().values(),
			AttributesDescriptor.THIS.name(entitySchema)
		);

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = EntityDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributesObject));

		if (!entitySchema.getLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			new AttributesDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributesObject(@Nonnull Collection<AttributeSchemaContract> attributeSchemas,
	                                                @Nonnull String objectName) {
		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema ->
			buildingContext.registerFieldToObject(
				objectName,
				attributesBuilder,
				buildAttributeField(attributeSchema)
			)
		);

		return attributesBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildAttributeField(@Nonnull AttributeSchemaContract attributeSchema) {
		final GraphQLFieldDefinition.Builder attributeFieldBuilder = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice());
		final DataFetcher<?> attributeFieldDataFetcher;

		final Class<? extends Serializable> attributeType = attributeSchema.getType();
		if (BigDecimal.class.isAssignableFrom(attributeType)) {
			if (attributeSchema.isNullable()) {
				new NullableBigDecimalFieldDecorator().accept(attributeFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator().accept(attributeFieldBuilder);
			}

			attributeFieldDataFetcher = new BigDecimalDataFetcher(new AttributeValueDataFetcher<>(attributeSchema));
		} else {
			attributeFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(attributeType, !attributeSchema.isNullable())
			);
			attributeFieldDataFetcher = new AttributeValueDataFetcher<>(attributeSchema);
		}

		return new BuiltFieldDescriptor(
			attributeFieldBuilder.build(),
			attributeFieldDataFetcher
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAssociatedDataField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLObjectType associatedDataObject = buildAssociatedDataObject(collectionBuildingContext);

		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = EntityDescriptor.ASSOCIATED_DATA
			.to(fieldBuilderTransformer)
			.type(nonNull(associatedDataObject));

		if (!entitySchema.getLocales().isEmpty()) {
			associatedDataFieldBuilder.argument(AssociatedDataFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
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
				new NullableBigDecimalFieldDecorator().accept(associatedDataFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator().accept(associatedDataFieldBuilder);
			}

			associatedDataFieldDataFetcher = new BigDecimalDataFetcher(new AssociatedDataValueDataFetcher<>(cdoObjectMapper, associatedDataSchema));
		} else {
			associatedDataFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(associatedDataType, !associatedDataSchema.isNullable())
			);
			associatedDataFieldDataFetcher = new AssociatedDataValueDataFetcher<>(cdoObjectMapper, associatedDataSchema);
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
	private List<BuiltFieldDescriptor> buildEntityReferenceFields(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final Collection<ReferenceSchemaContract> referenceSchemas = collectionBuildingContext.getSchema().getReferences().values();

		return referenceSchemas.stream()
			.map(referenceSchema -> {
				final GraphQLObjectType referenceObject = buildReferenceObject(collectionBuildingContext, referenceSchema);

				final GraphQLFieldDefinition.Builder referenceFieldBuilder = newFieldDefinition()
					.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
					.description(referenceSchema.getDescription())
					.deprecate(referenceSchema.getDeprecationNotice());

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
			referenceAttributesObjectName
		);

		final GraphQLFieldDefinition attributesField = ReferenceDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributesObject))
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
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
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
			.field(PriceDescriptor.PRICE_WITHOUT_TAX.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.field(PriceDescriptor.PRICE_WITH_TAX.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.field(PriceDescriptor.TAX_RATE.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.build();
	}
}
