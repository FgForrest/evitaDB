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

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.PROPERTY_TRANSFORMER;

/**
 * Utils used to add properties to OpenAPI {@link Schema} objects
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SchemaPropertyUtils {
	/**
	 * Create property schema based on property descriptor and adds it into object schema.
	 *
	 * @param objectSchema       target schema where propertySchema will be added
	 * @param propertyDescriptor descriptor for property
	 * @param schemaTransformer  used to generate property schema from descriptor
	 */
//	public static void createAndAddProperty(@Nonnull Schema<Object> objectSchema, @Nonnull PropertyDescriptor propertyDescriptor,
//	                                        @Nonnull PropertyDescriptorToOpenApiSchemaTransformer schemaTransformer) {
//		final var propertySchema = schemaTransformer.apply(propertyDescriptor);
//		addPropertySchema(objectSchema, propertySchema, propertyDescriptor.type() != null && propertyDescriptor.primitiveType().nonNull());
//	}

	/**
	 * Create property schema based on property descriptor using {@link io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers#PROPERTY_TRANSFORMER}
	 * and adds it into object schema.
	 *
	 * @param objectSchema       target schema where propertySchema will be added
	 * @param propertyDescriptor descriptor for property
	 */
//	public static void createAndAddProperty(@Nonnull Schema<Object> objectSchema, @Nonnull PropertyDescriptor propertyDescriptor) {
//		createAndAddProperty(objectSchema, propertyDescriptor, PROPERTY_TRANSFORMER);
//	}

	/**
	 * Adds reference into property schema using <strong>oneOf</strong>. This allows to add description to reference.
	 *
	 * @param objectSchema   target schema where propertySchema will be added
	 * @param propertySchema property schema which will contains reference
	 * @param reference      name of reference
	 * @param required       when <code>true</code> then propertySchema will be marked as <string>required</string> in objectSchema
	 */
//	public static void addReferencedPropertySchema(@Nonnull Schema<Object> objectSchema, @Nonnull Schema<Object> propertySchema,
//	                                               @Nonnull String reference, boolean required) {
//		propertySchema.setOneOf(Collections.singletonList(createReferenceSchema(reference)));
//		objectSchema.addProperty(propertySchema.getName(), propertySchema);
//		if (required) {
//			objectSchema.addRequiredItem(propertySchema.getName());
//		}
//	}

	/**
	 * Add property schema into object schema
	 *
	 * @param objectSchema   target schema where propertySchema will be added
	 * @param propertySchema schema to add as property
	 */
//	public static void addPropertySchema(@Nonnull Schema<Object> objectSchema, @Nonnull Schema<Object> propertySchema) {
//		objectSchema.addProperty(propertySchema.getName(), propertySchema);
//	}

	/**
	 * Add property schema into object schema
	 *
	 * @param objectSchema   target schema where propertySchema will be added
	 * @param propertySchema schema to add as property
	 * @param required       is property schema required
	 */
//	public static void addPropertySchema(@Nonnull Schema<Object> objectSchema, @Nonnull Schema<Object> propertySchema,
//	                                     boolean required) {
//		objectSchema.addProperty(propertySchema.getName(), propertySchema);
//		if (required) {
//			objectSchema.addRequiredItem(propertySchema.getName());
//		}
//	}

	/**
	 * Adds property schema into object schema using provided property name. This method can be used when
	 * name property schema differs from property name.
	 *
	 * @param objectSchema   target schema where propertySchema will be added
	 * @param propertySchema schema to add as property
	 * @param propertyName   name of property
	 * @param required       is property schema required
	 */
//	public static void addPropertySchema(@Nonnull Schema<Object> objectSchema, @Nonnull Schema<Object> propertySchema,
//	                                     @Nonnull String propertyName, boolean required) {
//		objectSchema.addProperty(propertyName, propertySchema);
//		if (required) {
//			objectSchema.addRequiredItem(propertyName);
//		}
//	}



//	public static void addProperty(@Nonnull Schema<Object> object,
//	                               @Nonnull Schema<Object> propertyObject) {
//		addProperty(object, propertyObject, false);
//	}

//	public static void addProperty(@Nonnull Schema<Object> object,
//	                               @Nonnull Schema<Object> propertyObject,
//	                               boolean required) {
//		object.addProperty(propertyObject.getName(), propertyObject);
//		if (required) {
//			object.addRequiredItem(propertyObject.getName());
//		}
//	}

	public static void addProperty(@Nonnull Schema<Object> object,
	                               @Nonnull PropertyDescriptor propertyDescriptor) {
		final Property property = propertyDescriptor.to(PROPERTY_TRANSFORMER);
		object.addProperty(property.schema().getName(), property.schema());
		if (property.required()) {
			object.addRequiredItem(property.schema().getName());
		}
	}

//	public static void addProperty(@Nonnull Schema<Object> object,
//	                               @Nonnull PropertyDescriptor propertyDescriptor,
//	                               @Nonnull Schema<Object> propertyObject) {
//		if (propertyObject.get$ref() == null) {
//			propertyObject.description(propertyDescriptor.description());
//		}
//
//		object.addProperty(propertyDescriptor.name(), propertyObject);
//
//		Assert.isPremiseValid(
//			propertyDescriptor.type() != null,
//			() -> new OpenApiSchemaBuildingError("Requirement of property `" + propertyDescriptor.name() + "` is not defined by descriptor. You need to define it manually.")
//		);
//		if (propertyDescriptor.type().nonNull()) {
//			object.addRequiredItem(propertyDescriptor.name());
//		}
//	}

	// todo lho this should be probably removed with builders
	public static void addProperty(@Nonnull Schema<Object> object,
	                               @Nonnull PropertyDescriptor propertyDescriptor,
	                               @Nonnull Schema<Object> propertyObject,
	                               boolean required) {
		if (propertyObject.get$ref() == null) {
			propertyObject.description(propertyDescriptor.description());
		}

		object.addProperty(propertyDescriptor.name(), propertyObject);

//		Assert.isPremiseValid(
//			propertyDescriptor.type() == null,
//			() -> new OpenApiSchemaBuildingError("Requirement of property `" + propertyDescriptor.name() + "` is already defined by descriptor.")
//		);
		if (required) {
			object.addRequiredItem(propertyDescriptor.name());
		}
	}

	public static void addProperty(@Nonnull Schema<Object> object,
	                               @Nonnull Property property) {
		object.addProperty(property.schema().getName(), property.schema());
		if (property.required()) {
			object.addRequiredItem(property.schema().getName());
		}
	}
}
