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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.evitadb.externalApi.rest.api.catalog.model.LocalizedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.LocalizedAttributesDescriptor;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.*;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createSchemaByJavaType;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaPropertyUtils.addProperty;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;

/**
 * Builds OpenAPI entity object (schema) based on information provided in building context
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityObjectBuilder {
	private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx;

	public EntityObjectBuilder(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		this.entitySchemaBuildingCtx = entitySchemaBuildingCtx;
	}

	/**
	 * Builds entity object.<br/>
	 * Parameter <strong>distinguishLocalizedData</strong> is used to control inner structure of some fields which
	 * may contains localized data (e.g. Attributes or Associated data).<br/>
	 * When <strong>distinguishLocalizedData</strong> is equal <code>true</code> then inner structure of these fields
	 * will be separated into two groups:
	 * <ul>
	 *     <li>global - which will contain non-localized data</li>
	 *     <li>localized - which will contain localized data further split into groups by locale</li>
	 * </ul>
	 * However, when set to <code>false</code> all data will be in same group. This variant may be used only when one
	 * and only one locale will always be present in query and dataInLocales cannot be specified.
	 *
	 * @return schema for entity object
	 */
	@Nonnull
	public Schema<Object> buildEntityObject(boolean distinguishLocalizedData) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		// build specific entity object
		final Schema<Object> objectSchema = EntityDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		objectSchema.name(constructEntityName(entitySchema, distinguishLocalizedData));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			addProperty(objectSchema, buildEntityLocalesProperty(EntityDescriptor.LOCALES));
			addProperty(objectSchema, buildEntityLocalesProperty(EntityDescriptor.ALL_LOCALES));
		}

		// build hierarchy placement field
		if (entitySchema.isWithHierarchy()) {
			addProperty(objectSchema, EntityDescriptor.HIERARCHICAL_PLACEMENT);
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			addProperty(objectSchema, EntityDescriptor.PRICE_INNER_RECORD_HANDLING);
			addProperty(objectSchema, EntityDescriptor.PRICE_FOR_SALE);
			addProperty(objectSchema, EntityDescriptor.PRICES);
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			addProperty(objectSchema, buildEntityAttributesProperty(distinguishLocalizedData));
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			addProperty(objectSchema, buildEntityAssociatedDataProperty(distinguishLocalizedData));
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			buildEntityReferenceProperties(objectSchema, distinguishLocalizedData);
		}

		return objectSchema;
	}

	@Nonnull
	private Property buildEntityLocalesProperty(@Nonnull PropertyDescriptor propertyDescriptor) {
		final ArraySchema schema = buildEntityLocalesSchema();
		schema.setName(propertyDescriptor.name());
		schema.setDescription(propertyDescriptor.description());
		return new Property(schema, true);
	}

	@Nonnull
	private ArraySchema buildEntityLocalesSchema() {
		final var refSchema = createReferenceSchema(entitySchemaBuildingCtx.getLocaleEnum().getName());
		return createArraySchemaOf(refSchema);
	}

	@Nonnull
	private Property buildEntityAttributesProperty(boolean distinguishLocalizedData) {
		final Schema<Object> attributesObject = buildEntityAttributesObject(distinguishLocalizedData);
		attributesObject.name(EntityDescriptor.ATTRIBUTES.name());
		return new Property(attributesObject, true);
	}

	@Nonnull
	private Schema<Object> buildEntityAttributesObject(boolean distinguishLocalizedData) {
		final Schema<Object> attributesObject;
		if (distinguishLocalizedData) {
			attributesObject = buildAttributesObjectWithLocalizationResolution();
		} else {
			final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
			attributesObject = buildAttributesObject(entitySchema.getAttributes().values(), constructAttributesObjectName(entitySchema, false));
		}

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(attributesObject);
	}

	/**
	 * Builds object for attributes. This object can be used only in case when one language is
	 * requested. Localized attributes are not distinguished from non-localized ones.
	 */
	@Nonnull
	private static Schema<Object> buildAttributesObject(@Nonnull Collection<AttributeSchemaContract> attributeSchemas,
	                                                    @Nonnull String objectName) {
		final var attributesSchema = createObjectSchema();
		attributesSchema.name(objectName);

		attributeSchemas.forEach(attributeSchema -> {
			final var attributeFieldDescriptor = buildAttributeProperty(attributeSchema);
			addProperty(attributesSchema, new Property(attributeFieldDescriptor, !attributeSchema.isNullable()));
		});

		return attributesSchema;
	}

	/**
	 * Builds object for attributes. In this case non-localized attributes are nested under the
	 * {@link LocalizedAttributesDescriptor#GLOBAL} name, while localized attributes
	 * are nested under the {@link LocalizedAttributesDescriptor#LOCALIZED}. Moreover,
	 * separate object is created for each locale and locale tag is used as attribute name of this object.
	 */
	@Nonnull
	private Schema<Object> buildAttributesObjectWithLocalizationResolution() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final var attributesSchema = createObjectSchema();
		attributesSchema.name(constructAttributesObjectName(entitySchema, true));

		final Schema<Object> globalAttributesSchema = createObjectSchema();
		final Schema<Object> localizedAttributesSchema = createObjectSchema();

		entitySchema.getAttributes().values().forEach(attributeSchema -> {
			final var attributeFieldDescriptor = buildAttributeProperty(attributeSchema);
			if (attributeSchema.isLocalized()) {
				addProperty(localizedAttributesSchema, new Property(attributeFieldDescriptor, !attributeSchema.isNullable()));
			} else {
				addProperty(globalAttributesSchema, new Property(attributeFieldDescriptor, !attributeSchema.isNullable()));
			}
		});

		addProperty(attributesSchema, LocalizedAttributesDescriptor.GLOBAL, globalAttributesSchema, true);

		final Schema<Object> localizationContainer = createObjectSchema();
		entitySchema.getLocales().forEach(locale -> localizationContainer.addProperty(locale.toLanguageTag(), localizedAttributesSchema));
		if(!localizationContainer.getProperties().isEmpty()) {
			addProperty(attributesSchema, LocalizedAttributesDescriptor.LOCALIZED, localizationContainer, true);
		}

		return attributesSchema;
	}

	@Nonnull
	private static Schema<Object> buildAttributeProperty(@Nonnull AttributeSchemaContract attributeSchema) {
		final var attributeProperty = createSchemaByJavaType(attributeSchema.getType());
		attributeProperty
			.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription());
		if (attributeSchema.getDeprecationNotice() != null) {
			attributeProperty.deprecated(true);
		}

		return attributeProperty;
	}

	@Nonnull
	private Property buildEntityAssociatedDataProperty(boolean distinguishLocalizedData) {
		final Schema<Object> associatedDataObject = buildEntityAssociatedDataObject(distinguishLocalizedData);
		associatedDataObject.name(EntityDescriptor.ASSOCIATED_DATA.name());
		return new Property(associatedDataObject, true);
	}

	@Nonnull
	private Schema<Object> buildEntityAssociatedDataObject(boolean distinguishLocalizedData) {
		final Schema<Object> associatedDataObject;

		if (distinguishLocalizedData) {
			associatedDataObject = buildAssociatedDataObjectWithLocalizationResolution();
		} else {
			associatedDataObject = buildAssociatedDataObject();
		}

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(associatedDataObject);
	}

	/**
	 * Builds object for associated data. This method can be used only in case when one language is
	 * requested. Localized attributes are not distinguished from non-localized ones.
	 */
	@Nonnull
	private Schema<Object> buildAssociatedDataObject() {
		final String objectName = constructAssociatedDataObjectName(entitySchemaBuildingCtx.getSchema(), false);
		final var associatedDataSchema = createObjectSchema();
		associatedDataSchema.name(objectName);

		entitySchemaBuildingCtx.getSchema().getAssociatedData().values().forEach(associatedSchema -> {
			final Schema<Object> associatedDataProperty = buildSingleAssociatedDataProperty(associatedSchema);
			addProperty(associatedDataSchema, new Property(associatedDataProperty, !associatedSchema.isNullable()));
		});

		return associatedDataSchema;
	}

	/**
	 * Builds object for associated data. In this case non-localized associated data are nested under the
	 * {@link LocalizedAssociatedDataDescriptor#GLOBAL} name, while localized associated data
	 * are nested under the {@link LocalizedAssociatedDataDescriptor#LOCALIZED}. Moreover,
	 * separate object is created for each locale and locale tag is used as attribute name of this object.
	 */
	@Nonnull
	private Schema<Object> buildAssociatedDataObjectWithLocalizationResolution() {
		final var associatedDataObject = createObjectSchema();
		associatedDataObject.name(constructAssociatedDataObjectName(entitySchemaBuildingCtx.getSchema(), true));

		final Schema<Object> globalDataObject = createObjectSchema();
		final Schema<Object> localizedDataObject = createObjectSchema();

		entitySchemaBuildingCtx.getSchema().getAssociatedData().values().forEach(associatedSchema -> {
			final Schema<Object> associatedDataProperty = buildSingleAssociatedDataProperty(associatedSchema);
			if (associatedSchema.isLocalized()) {
				addProperty(localizedDataObject, new Property(associatedDataProperty, !associatedSchema.isNullable()));
			} else {
				addProperty(globalDataObject, new Property(associatedDataProperty, !associatedSchema.isNullable()));
			}
		});

		addProperty(associatedDataObject, LocalizedAssociatedDataDescriptor.GLOBAL, globalDataObject, true);

		final Schema<Object> localizationContainer = createObjectSchema();
		entitySchemaBuildingCtx.getSchema().getLocales().forEach(locale -> localizationContainer.addProperty(locale.toLanguageTag(), localizedDataObject));
		addProperty(associatedDataObject, LocalizedAssociatedDataDescriptor.LOCALIZED, localizationContainer, true);

		return associatedDataObject;
	}

	@Nonnull
	private static Schema<Object> buildSingleAssociatedDataProperty(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final var associatedDataProperty = createSchemaByJavaType(associatedDataSchema.getType());
		associatedDataProperty
			.name(associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription());
		if (associatedDataSchema.getDeprecationNotice() != null) {
			associatedDataProperty.deprecated(true);
		}

		return associatedDataProperty;
	}

	private void buildEntityReferenceProperties(@Nonnull Schema<Object> parentSchema, boolean distinguishLocalizedData) {
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchemaBuildingCtx.getSchema().getReferences().values();

		referenceSchemas.forEach(referenceSchema -> {
			final Schema<Object> referenceObject = buildReferenceObject(referenceSchema, distinguishLocalizedData);

			if (referenceSchema.getCardinality() == Cardinality.ZERO_OR_MORE || referenceSchema.getCardinality() == Cardinality.ONE_OR_MORE) {
				final Schema<Object> referenceProperty = createArraySchemaOf(referenceObject);
				referenceProperty
					.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
					.description(referenceSchema.getDescription());

				addProperty(parentSchema, new Property(referenceProperty, referenceSchema.getCardinality() == Cardinality.ONE_OR_MORE));
			} else {
				referenceObject.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
				addProperty(parentSchema, new Property(referenceObject, referenceSchema.getCardinality() == Cardinality.EXACTLY_ONE));
			}
		});
	}

	@Nonnull
	private Schema<Object> buildReferenceObject(@Nonnull ReferenceSchemaContract referenceSchema, boolean distinguishLocalizedData) {
		final Schema<Object> referenceObject = ReferenceDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		referenceObject
			.name(constructReferenceObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, distinguishLocalizedData))
			.description(referenceSchema.getDescription());

		addProperty(referenceObject, buildReferenceReferencedEntityObjectProperty(referenceSchema, distinguishLocalizedData));
		if (referenceSchema.getReferencedGroupType() != null) {
			addProperty(referenceObject, buildReferenceGroupEntityProperty(referenceSchema, distinguishLocalizedData));
		}
		if (!referenceSchema.getAttributes().isEmpty()) {
			addProperty(referenceObject, buildReferenceAttributesProperty(referenceSchema));
		}

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(referenceObject);
	}

	@Nonnull
	private Property buildReferenceReferencedEntityObjectProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                              boolean distinguishLocalizedData) {
		final Schema<Object> referencedEntityObject = buildReferenceReferencedEntityObject(referenceSchema, distinguishLocalizedData);
		referencedEntityObject.name(ReferenceDescriptor.REFERENCED_ENTITY.name());
		return new Property(referencedEntityObject, true);
	}

	@Nonnull
	private Schema<Object> buildReferenceReferencedEntityObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                            boolean distinguishLocalizedData) {
		final Schema<Object> referencedEntityObject;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
				.getSchema()
				.getEntitySchema(referenceSchema.getReferencedEntityType())
				.orElseThrow(() -> new OpenApiSchemaBuildingError("Could not find entity schema for referenced schema `" + referenceSchema.getReferencedEntityType() + "`."));

			final var entityName = constructEntityName(referencedEntitySchema, distinguishLocalizedData);
			referencedEntityObject = createReferenceSchema(entityName);
		} else {
			referencedEntityObject = createReferenceSchema(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}

		return referencedEntityObject;
	}

	@Nullable
	private Property buildReferenceGroupEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                   boolean distinguishLocalizedData) {
		final Schema<Object> groupEntityObject = buildReferenceGroupEntityObject(referenceSchema, distinguishLocalizedData);
		if (groupEntityObject == null) {
			return null;
		}
		groupEntityObject.name(ReferenceDescriptor.GROUP_ENTITY.name());
		return new Property(groupEntityObject, true);
	}

	@Nullable
	private Schema<Object> buildReferenceGroupEntityObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                       boolean distinguishLocalizedData) {
		if(referenceSchema.getReferencedGroupType() == null) {
			return null;
		}

		final Schema<Object> groupEntityObject;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			final EntitySchemaContract referencedGroupSchema = entitySchemaBuildingCtx.getCatalogCtx()
				.getSchema()
				.getEntitySchema(referenceSchema.getReferencedGroupType())
				.orElseThrow(() -> new OpenApiSchemaBuildingError("Could not find entity schema for referenced schema `" + referenceSchema.getReferencedGroupType() + "`."));

			final var groupType = constructEntityName(referencedGroupSchema, distinguishLocalizedData);
			groupEntityObject = createReferenceSchema(groupType);
		} else {
			groupEntityObject = createReferenceSchema(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}

		return groupEntityObject;
	}

	@Nonnull
	private Property buildReferenceAttributesProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final Schema<Object> referenceAttributesObject = buildReferenceAttributesObject(referenceSchema);
		referenceAttributesObject.name(ReferenceDescriptor.ATTRIBUTES.name());
		return new Property(referenceAttributesObject, true);
	}

	@Nonnull
	private Schema<Object> buildReferenceAttributesObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceAttributesObjectName = constructReferenceAttributesObjectName(
			entitySchemaBuildingCtx.getSchema(),
			referenceSchema
		);

		final Schema<Object> attributesObject;
		final Optional<Schema<Object>> existingAttributesObject = entitySchemaBuildingCtx.getCatalogCtx().getRegisteredType(referenceAttributesObjectName);
		attributesObject = existingAttributesObject.orElseGet(() ->
			entitySchemaBuildingCtx.getCatalogCtx().registerType(
				buildAttributesObject(
					referenceSchema.getAttributes().values(),
					referenceAttributesObjectName
				)
			)
		);

		return attributesObject;
	}
}
