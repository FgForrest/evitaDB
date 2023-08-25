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

package io.evitadb.externalApi.lab.api.builder;

import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.SortableAttributeCompoundSchemaDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericAttributeSchemaUnionDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericEntitySchemaDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericAssociatedDataSchemasDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericAttributeSchemasDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericReferenceSchemasDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericSortableAttributeCompoundSchemasDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiDictionaryTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiUnionTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builds OpenAPI entity schema object (schema) based on information provided in building context
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GenericEntitySchemaObjectBuilder {

	@Nonnull private final LabApiBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiUnionTransformer unionBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiDictionaryTransformer dictionaryBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(AttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(GlobalAttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildAttributeSchemaUnion());
		buildingContext.registerType(AttributeElementDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SortableAttributeCompoundSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AssociatedDataSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ReferenceSchemaDescriptor.THIS_GENERIC.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildEntitySchemaObject());
	}

	/**
	 * Builds entity schema object.
	 *
	 * @return schema for entity schema object
	 */
	@Nonnull
	private OpenApiObject buildEntitySchemaObject() {
		// build specific entity schema object
		final OpenApiObject.Builder entitySchemaObjectBuilder = GenericEntitySchemaDescriptor.THIS
			.to(objectBuilderTransformer);

		entitySchemaObjectBuilder.property(buildAttributeSchemasProperty());
		entitySchemaObjectBuilder.property(buildAssociatedDataSchemasProperty());
		entitySchemaObjectBuilder.property(buildSortableAttributeCompoundSchemasProperty());
		entitySchemaObjectBuilder.property(buildReferenceSchemasProperty());

		return entitySchemaObjectBuilder.build();
	}

	@Nonnull
	private OpenApiProperty buildAttributeSchemasProperty() {
		return GenericEntitySchemaDescriptor.ATTRIBUTES
			.to(propertyBuilderTransformer)
			.type(nonNull(buildAttributeSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildAttributeSchemasObject() {
		final OpenApiDictionary attributeSchemasObjectBuilder = GenericAttributeSchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(nonNull(typeRefTo(GenericAttributeSchemaUnionDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(attributeSchemasObjectBuilder);
	}

	@Nonnull
	private OpenApiUnion buildAttributeSchemaUnion() {
		return GenericAttributeSchemaUnionDescriptor.THIS
			.to(unionBuilderTransformer)
			.type(OpenApiObjectUnionType.ONE_OF)
			.discriminator(GenericAttributeSchemaUnionDescriptor.DISCRIMINATOR.name())
			.object(typeRefTo(AttributeSchemaDescriptor.THIS.name()))
			.object(typeRefTo(GlobalAttributeSchemaDescriptor.THIS.name()))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildSortableAttributeCompoundSchemasProperty() {
		return GenericEntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS
			.to(propertyBuilderTransformer)
			.type(nonNull(buildSortableAttributeCompoundSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSortableAttributeCompoundSchemasObject() {
		final OpenApiDictionary objectBuilder = GenericSortableAttributeCompoundSchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(nonNull(typeRefTo(SortableAttributeCompoundSchemaDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(objectBuilder);
	}

	@Nonnull
	private OpenApiProperty buildAssociatedDataSchemasProperty() {
		return GenericEntitySchemaDescriptor.ASSOCIATED_DATA
			.to(propertyBuilderTransformer)
			.type(nonNull(buildAssociatedDataSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildAssociatedDataSchemasObject() {
		final OpenApiDictionary associatedDataSchemasObjectBuilder = GenericAssociatedDataSchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(nonNull(typeRefTo(AssociatedDataSchemaDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(associatedDataSchemasObjectBuilder);
	}

	@Nonnull
	private OpenApiProperty buildReferenceSchemasProperty() {
		return GenericEntitySchemaDescriptor.REFERENCES
			.to(propertyBuilderTransformer)
			.type(nonNull(buildReferenceSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceSchemasObject() {
		final OpenApiDictionary referenceSchemasObjectBuilder = GenericReferenceSchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(nonNull(typeRefTo(ReferenceSchemaDescriptor.THIS_GENERIC.name())))
			.build();

		return buildingContext.registerType(referenceSchemasObjectBuilder);
	}
}
