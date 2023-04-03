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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.GlobalAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.GlobalAttributesDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.LocalizedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.LocalizedAssociatedDataForLocaleDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.LocalizedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.LocalizedAttributesForLocaleDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructReferenceObjectName;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builds OpenAPI entity object (schema) based on information provided in building context
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EntityObjectBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(HierarchicalPlacementDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(PriceDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(objectBuilderTransformer).build());
	}

	/**
	 * Builds entity object.<br/>
	 * Parameter <strong>localized</strong> is used to control inner structure of some fields which
	 * may contains localized data (e.g. Attributes or Associated data).<br/>
	 * When <strong>localized</strong> is equal <code>false</code> then inner structure of these fields
	 * will be separated into two groups:
	 * <ul>
	 *     <li>global - which will contain non-localized data</li>
	 *     <li>localized - which will contain localized data further split into groups by locale</li>
	 * </ul>
	 * However, when set to <code>true</code> all data will be in same group (global and data of specific locale).
	 * This variant may be used only when one and only one locale will always be present in query and dataInLocales
	 * cannot be specified.
	 *
	 * @return schema for entity object
	 */
	@Nonnull
	public OpenApiObject buildEntityObject(@Nonnull EntitySchemaContract entitySchema,
	                                       boolean localized) {
		// build specific entity object
		final OpenApiObject.Builder entityObject = EntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityObjectName(entitySchema, localized));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			entityObject.property(EntityDescriptor.LOCALES.to(propertyBuilderTransformer));
			entityObject.property(EntityDescriptor.ALL_LOCALES.to(propertyBuilderTransformer));
		}

		// build hierarchy placement field
		if (entitySchema.isWithHierarchy()) {
			entityObject.property(EntityDescriptor.HIERARCHICAL_PLACEMENT.to(propertyBuilderTransformer));
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			entityObject.property(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(propertyBuilderTransformer));
			entityObject.property(EntityDescriptor.PRICE_FOR_SALE.to(propertyBuilderTransformer));
			entityObject.property(EntityDescriptor.PRICES.to(propertyBuilderTransformer));
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			entityObject.property(buildEntityAttributesProperty(entitySchema, localized));
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			entityObject.property(buildEntityAssociatedDataProperty(entitySchema, localized));
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			buildEntityReferenceProperties(entitySchema, localized).forEach(entityObject::property);
		}

		return entityObject.build();
	}

	@Nonnull
	private OpenApiProperty buildEntityAttributesProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                      boolean localized) {
		final OpenApiTypeReference attributesObject = buildEntityAttributesObject(entitySchema, localized);
		return EntityDescriptor.ATTRIBUTES
			.to(propertyBuilderTransformer)
			.type(nonNull(attributesObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildEntityAttributesObject(@Nonnull EntitySchemaContract entitySchema,
	                                                         boolean localized) {
		final OpenApiObject attributesObject;
		if (localized) {
			attributesObject = buildLocalizedAttributesObject(entitySchema.getAttributes().values(), entitySchema);
		} else {
			attributesObject = buildNonLocalizedAttributesObject(
				entitySchema,
				entitySchema.getAttributes().values(),
				entitySchema
			);
		}

		return buildingContext.registerType(attributesObject);
	}

	/**
	 * Builds object for attributes. This object can be used only in case when one language is
	 * requested. Localized attributes are not distinguished from non-localized ones.
	 */
	@Nonnull
	private OpenApiObject buildLocalizedAttributesObject(@Nonnull Collection<AttributeSchemaContract> attributeSchemas,
	                                                     @Nonnull NamedSchemaContract... objectNameSchemas) {
		final OpenApiObject.Builder attributesObject = AttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(AttributesDescriptor.THIS.name(objectNameSchemas));
		attributeSchemas.forEach(attributeSchema -> attributesObject.property(buildAttributeProperty(attributeSchema)));
		return attributesObject.build();
	}

	/**
	 * Builds object for attributes. In this case non-localized attributes are nested under the
	 * {@link SectionedAttributesDescriptor#GLOBAL} name, while localized attributes
	 * are nested under the {@link SectionedAttributesDescriptor#LOCALIZED}. Moreover,
	 * separate object is created for each locale and locale tag is used as attribute name of this object.
	 */
	@Nonnull
	private OpenApiObject buildNonLocalizedAttributesObject(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull Collection<AttributeSchemaContract> attributeSchemas,
	                                                        @Nonnull NamedSchemaContract... objectNameSchemas) {
		final OpenApiObject.Builder attributesObject = SectionedAttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(SectionedAttributesDescriptor.THIS.name(objectNameSchemas));

		final OpenApiObject.Builder globalAttributesObjectBuilder = GlobalAttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(GlobalAttributesDescriptor.THIS.name(objectNameSchemas));
		final OpenApiObject.Builder localizedAttributesForLocaleObjectBuilder = LocalizedAttributesForLocaleDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(LocalizedAttributesForLocaleDescriptor.THIS.name(objectNameSchemas));

		attributeSchemas.forEach(attributeSchema -> {
			final OpenApiProperty attributeProperty = buildAttributeProperty(attributeSchema);
			if (attributeSchema.isLocalized()) {
				localizedAttributesForLocaleObjectBuilder.property(attributeProperty);
			} else {
				globalAttributesObjectBuilder.property(attributeProperty);
			}
		});

		final OpenApiTypeReference globalAttributesObject = buildingContext.registerType(globalAttributesObjectBuilder.build());
		final OpenApiTypeReference localizedAttributesForLocaleObject = buildingContext.registerType(localizedAttributesForLocaleObjectBuilder.build());

		final OpenApiProperty globalAttributesProperty = SectionedAttributesDescriptor.GLOBAL
			.to(propertyBuilderTransformer)
			.type(nonNull(globalAttributesObject))
			.build();
		attributesObject.property(globalAttributesProperty);

		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiObject.Builder localizedAttributesObjectBuilder = LocalizedAttributesDescriptor.THIS
				.to(objectBuilderTransformer)
				.name(LocalizedAttributesDescriptor.THIS.name(objectNameSchemas));
			entitySchema.getLocales().forEach(locale ->
				localizedAttributesObjectBuilder.property(p -> p
					.name(locale.toLanguageTag())
					.type(nonNull(localizedAttributesForLocaleObject)))
			);
			final OpenApiTypeReference localizedAttributesObject = buildingContext.registerType(localizedAttributesObjectBuilder.build());

			attributesObject.property(SectionedAttributesDescriptor.LOCALIZED
				.to(propertyBuilderTransformer)
				.type(nonNull(localizedAttributesObject)));
		}

		return attributesObject.build();
	}

	@Nonnull
	private static OpenApiProperty buildAttributeProperty(@Nonnull AttributeSchemaContract attributeSchema) {
		final OpenApiSimpleType attributeType = DataTypesConverter.getOpenApiScalar(attributeSchema.getType(), !attributeSchema.isNullable());
		return newProperty()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecationNotice(attributeSchema.getDeprecationNotice())
			.type(attributeType)
			.build();
	}

	@Nonnull
	private OpenApiProperty buildEntityAssociatedDataProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                          boolean distinguishLocalizedData) {
		final OpenApiTypeReference associatedDataObject = buildEntityAssociatedDataObject(
			entitySchema,
			distinguishLocalizedData
		);

		return EntityDescriptor.ASSOCIATED_DATA
			.to(propertyBuilderTransformer)
			.type(nonNull(associatedDataObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildEntityAssociatedDataObject(@Nonnull EntitySchemaContract entitySchema,
	                                                             boolean localized) {
		final OpenApiObject associatedDataObject;

		if (localized) {
			associatedDataObject = buildLocalizedAssociatedDataObject(entitySchema);
		} else {
			associatedDataObject = buildNonLocalizedAssociatedDataObject(entitySchema);
		}

		return buildingContext.registerType(associatedDataObject);
	}

	/**
	 * Builds object for associated data. This method can be used only in case when one language is
	 * requested. Localized attributes are not distinguished from non-localized ones.
	 */
	@Nonnull
	private OpenApiObject buildLocalizedAssociatedDataObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder associatedDataObjectBuilder = AssociatedDataDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(AssociatedDataDescriptor.THIS.name(entitySchema));

		entitySchema.getAssociatedData().values().forEach(associatedSchema ->
			associatedDataObjectBuilder.property(buildSingleAssociatedDataProperty(associatedSchema)));

		return associatedDataObjectBuilder.build();
	}

	/**
	 * Builds object for associated data. In this case non-localized associated data are nested under the
	 * {@link SectionedAssociatedDataDescriptor#GLOBAL} name, while localized associated data
	 * are nested under the {@link SectionedAssociatedDataDescriptor#LOCALIZED}. Moreover,
	 * separate object is created for each locale and locale tag is used as attribute name of this object.
	 */
	@Nonnull
	private OpenApiObject buildNonLocalizedAssociatedDataObject(@Nonnull EntitySchemaContract entitySchema) {
		final var associatedDataObject = SectionedAssociatedDataDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(SectionedAssociatedDataDescriptor.THIS.name(entitySchema));

		final OpenApiObject.Builder globalDataObjectBuilder = GlobalAssociatedDataDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(GlobalAssociatedDataDescriptor.THIS.name(entitySchema));
		final OpenApiObject.Builder localizedDataForLocaleObjectBuilder = LocalizedAssociatedDataForLocaleDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(LocalizedAssociatedDataForLocaleDescriptor.THIS.name(entitySchema));

		entitySchema.getAssociatedData().values().forEach(associatedSchema -> {
			final OpenApiProperty associatedDataProperty = buildSingleAssociatedDataProperty(associatedSchema);
			if (associatedSchema.isLocalized()) {
				localizedDataForLocaleObjectBuilder.property(associatedDataProperty);
			} else {
				globalDataObjectBuilder.property(associatedDataProperty);
			}
		});

		final OpenApiTypeReference globalDataObject = buildingContext.registerType(globalDataObjectBuilder.build());
		final OpenApiProperty globalDataProperty = SectionedAssociatedDataDescriptor.GLOBAL
			.to(propertyBuilderTransformer)
			.type(nonNull(globalDataObject))
			.build();
		associatedDataObject.property(globalDataProperty);

		final OpenApiTypeReference localizedDataForLocaleObject = buildingContext.registerType(localizedDataForLocaleObjectBuilder.build());


		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiObject.Builder localizedDataObjectBuilder = LocalizedAssociatedDataDescriptor.THIS
				.to(objectBuilderTransformer)
				.name(LocalizedAssociatedDataDescriptor.THIS.name(entitySchema));
			entitySchema.getLocales().forEach(locale ->
				localizedDataObjectBuilder.property(p -> p
					.name(locale.toLanguageTag())
					.type(nonNull(localizedDataForLocaleObject)))
			);
			final OpenApiTypeReference localizedDataObject = buildingContext.registerType(localizedDataObjectBuilder.build());

			associatedDataObject.property(SectionedAssociatedDataDescriptor.LOCALIZED
				.to(propertyBuilderTransformer)
				.type(nonNull(localizedDataObject)));
		}

		return associatedDataObject.build();
	}

	@Nonnull
	private static OpenApiProperty buildSingleAssociatedDataProperty(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final OpenApiSimpleType associatedDataType = DataTypesConverter.getOpenApiScalar(
			associatedDataSchema.getType(),
			!associatedDataSchema.isNullable()
		);
		return newProperty()
			.name(associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecationNotice(associatedDataSchema.getDeprecationNotice())
			.type(associatedDataType)
			.build();
	}

	@Nonnull
	private List<OpenApiProperty> buildEntityReferenceProperties(@Nonnull EntitySchemaContract entitySchema,
	                                                             boolean localized) {
		return entitySchema
			.getReferences()
			.values()
			.stream()
			.map(referenceSchema -> {
				final OpenApiTypeReference referenceObject = buildReferenceObject(entitySchema, referenceSchema, localized);

				final OpenApiProperty.Builder referencePropertyBuilder = newProperty()
					.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
					.description(referenceSchema.getDescription())
					.deprecationNotice(referenceSchema.getDeprecationNotice());

				if (referenceSchema.getCardinality() == Cardinality.ZERO_OR_MORE || referenceSchema.getCardinality() == Cardinality.ONE_OR_MORE) {
					referencePropertyBuilder.type(referenceSchema.getCardinality() == Cardinality.ONE_OR_MORE ? nonNull(arrayOf(referenceObject)) : arrayOf(referenceObject));
				} else {
					referencePropertyBuilder.type(referenceSchema.getCardinality() == Cardinality.EXACTLY_ONE ? nonNull(referenceObject) : referenceObject);
				}

				return referencePropertyBuilder.build();
			})
			.toList();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceObject(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		final OpenApiObject.Builder referenceObject = ReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructReferenceObjectName(entitySchema, referenceSchema, localized))
			.description(referenceSchema.getDescription());

		referenceObject.property(buildReferenceReferencedEntityObjectProperty(referenceSchema, localized));
		if (referenceSchema.getReferencedGroupType() != null) {
			referenceObject.property(buildReferenceGroupEntityProperty(referenceSchema, localized));
		}
		if (!referenceSchema.getAttributes().isEmpty()) {
			referenceObject.property(buildReferenceAttributesProperty(entitySchema, referenceSchema, localized));
		}

		return buildingContext.registerType(referenceObject.build());
	}

	@Nonnull
	private OpenApiProperty buildReferenceReferencedEntityObjectProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                     boolean localized) {
		final OpenApiTypeReference referencedEntityObject = buildReferenceReferencedEntityObject(referenceSchema, localized);
		return ReferenceDescriptor.REFERENCED_ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceReferencedEntityObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                  boolean localized) {
		final OpenApiTypeReference referencedEntityObject;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			final EntitySchemaContract referencedEntitySchema = buildingContext
				.getSchema()
				.getEntitySchema(referenceSchema.getReferencedEntityType())
				.orElseThrow(() -> new OpenApiBuildingError("Could not find entity schema for referenced schema `" + referenceSchema.getReferencedEntityType() + "`."));

			final var entityName = constructEntityObjectName(referencedEntitySchema, localized);
			referencedEntityObject = typeRefTo(entityName);
		} else {
			referencedEntityObject = typeRefTo(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}

		return referencedEntityObject;
	}

	@Nullable
	private OpenApiProperty buildReferenceGroupEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                   boolean localized) {
		final OpenApiTypeReference groupEntityObject = buildReferenceGroupEntityObject(referenceSchema, localized);
		if (groupEntityObject == null) {
			return null;
		}
		return ReferenceDescriptor.GROUP_ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(groupEntityObject))
			.build();
	}

	@Nullable
	private OpenApiTypeReference buildReferenceGroupEntityObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		if(referenceSchema.getReferencedGroupType() == null) {
			return null;
		}

		final OpenApiTypeReference groupEntityObject;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			final EntitySchemaContract referencedGroupSchema = buildingContext
				.getSchema()
				.getEntitySchema(referenceSchema.getReferencedGroupType())
				.orElseThrow(() -> new OpenApiBuildingError("Could not find entity schema for referenced schema `" + referenceSchema.getReferencedGroupType() + "`."));

			final var groupType = constructEntityObjectName(referencedGroupSchema, localized);
			groupEntityObject = typeRefTo(groupType);
		} else {
			groupEntityObject = typeRefTo(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}

		return groupEntityObject;
	}

	@Nonnull
	private OpenApiProperty buildReferenceAttributesProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                         @Nonnull ReferenceSchemaContract referenceSchema,
	                                                         boolean localized) {
		final OpenApiTypeReference referenceAttributesObject = buildReferenceAttributesObject(
			entitySchema,
			referenceSchema,
			localized
		);
		return ReferenceDescriptor.ATTRIBUTES
			.to(propertyBuilderTransformer)
			.type(nonNull(referenceAttributesObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceAttributesObject(@Nonnull EntitySchemaContract entitySchema,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema,
	                                                            boolean localized) {
		final OpenApiObject attributesObject;
		if (localized) {
			attributesObject = buildLocalizedAttributesObject(
				entitySchema.getAttributes().values(),
				entitySchema, referenceSchema
			);
		} else {
			attributesObject = buildNonLocalizedAttributesObject(
				entitySchema,
				entitySchema.getAttributes().values(),
				entitySchema, referenceSchema
			);
		}

		return buildingContext.registerType(attributesObject);
	}
}
