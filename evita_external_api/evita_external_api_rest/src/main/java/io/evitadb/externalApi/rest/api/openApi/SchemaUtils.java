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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Utils used to get/look-up schema in OpenAPI.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SchemaUtils {

	public static final int MAX_TARGET_SCHEMA_SEARCH_NESTING_COUNT = 100;

	/**
	 * Gets target schema i.e. if schema is reference to another schema then returns this referenced schema. Process is
	 * recursive so if there are nested references then the deepest non-reference schema is returned.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	public static Schema getTargetSchema(@Nonnull Schema schema, @Nonnull OpenAPI openAPI) {
		final String reference = schema.get$ref();
		if (reference != null) {
			return getTargetSchema(openAPI.getComponents().getSchemas().get(getSchemaNameFromReference(reference)), openAPI);
		} else {
			return schema;
		}
	}

	/**
	 * Gets target schema i.e. if schema is reference to another schema then returns this referenced schema. Process is
	 * recursive so if there are nested references then the deepest non-reference schema is returned.<br/>
	 * Internally are searched not only primary references of schemas (i.e. get$ref()) but also oneOf
	 * attribute of schema. OneOf is used when description for reference is needed (as it is not valid to set
	 * description in schema with reference), so it must be searched as well. It is expected, that this schema may have
	 * only one reference, so when more schemas are found, exception is thrown, because method is unable to select target schema.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	public static Schema getTargetSchemaFromRefOrOneOf(@Nonnull Schema schema, @Nonnull OpenAPI openAPI) {
		return getTargetSchemaFromRefOrOneOf(schema, openAPI, 1);
	}

	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Schema getTargetSchemaFromRefOrOneOf(@Nonnull Schema schema, @Nonnull OpenAPI openAPI, int nestingCount) {
		if(nestingCount > MAX_TARGET_SCHEMA_SEARCH_NESTING_COUNT) {
			throw new RestInternalError("Max nesting count reached when getting target schema, which probably " +
				"means that there's a cycle in schema reference structure and application is unable to find schema, which " +
				"is not an reference. Currently processing schema name: " +  schema, "Error when deserializing query.");
		}

		Schema targetSchema = null;
		if(schema.get$ref() != null) {
			targetSchema = getTargetSchemaFromRefOrOneOf(openAPI.getComponents().getSchemas().get(getSchemaNameFromReference(schema.get$ref())), openAPI, ++nestingCount);
		}
		if(schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
			if(targetSchema != null) {
				throwTargetSchemaAlreadyFound(schema);
			}
			targetSchema = getTargetSchemaFromRefOrOneOf(getFirstSchemaFromListWhenOnlyOneSchemaAllowed(schema, schema.getOneOf()), openAPI, ++nestingCount);
		}

		if (targetSchema != null) {
			return targetSchema;
		} else {
			return schema;
		}
	}

	@SuppressWarnings({"rawtypes"})
	private static Schema getFirstSchemaFromListWhenOnlyOneSchemaAllowed(@Nonnull Schema parentSchema, @Nonnull List<Schema> schemas) {
		if(schemas.size() == 1) {
			return schemas.get(0);
		}
		throw new RestInternalError("Can't get schema from list, found more than one schema in list. " +
			"Parent schema: " + parentSchema.getName(),"Error when parsing input data.");
	}

	@SuppressWarnings({"rawtypes"})
	private static void throwTargetSchemaAlreadyFound(@Nonnull Schema schema) {
		throw new RestInternalError("Can't get target schema from any inner location, schema found in " +
			"more than one place. Schema: " + schema.getName(),"Error when parsing input data.");
	}

	/**
	 * Gets schema by its name from filterBy attribute of provided operation
	 *
	 * @param openAPI OpenAPI schema
	 * @param operation operation schema
	 * @param propertyName name of searched property
	 * @throws RestQueryResolvingInternalError when property wasn't found
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	public static Schema getSchemaFromFilterBy(@Nonnull OpenAPI openAPI, @Nonnull Operation operation, @Nonnull String propertyName) {
		return getSchemaFromOperationProperty(openAPI, operation, propertyName, FetchEntityRequestDescriptor.FILTER_BY.name());
	}

	/**
	 * Gets schema by its name from orderBy attribute of provided operation
	 *
	 * @param openAPI OpenAPI schema
	 * @param operation operation schema
	 * @param propertyName name of searched property
	 * @throws RestQueryResolvingInternalError when property wasn't found
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	public static Schema getSchemaFromOrderBy(@Nonnull OpenAPI openAPI, @Nonnull Operation operation, @Nonnull String propertyName) {
		return getSchemaFromOperationProperty(openAPI, operation, propertyName, FetchEntityRequestDescriptor.ORDER_BY.name());
	}

	/**
	 * Gets schema by its name from require attribute of provided operation
	 *
	 * @param openAPI OpenAPI schema
	 * @param operation operation schema
	 * @param propertyName name of searched property
	 * @throws RestQueryResolvingInternalError when property wasn't found
	 *
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	public static Schema getSchemaFromRequire(@Nonnull OpenAPI openAPI, @Nonnull Operation operation, @Nonnull String propertyName) {
		return getSchemaFromOperationProperty(openAPI, operation, propertyName, FetchEntityRequestDescriptor.REQUIRE.name());
	}

	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Schema getSchemaFromOperationProperty(@Nonnull OpenAPI openAPI, @Nonnull Operation operation, @Nonnull String propertyName, @Nonnull String rootPropertyName) {
		final Schema rootSchema = (Schema) getTargetSchema(
			operation.getRequestBody()
				.getContent()
				.get(MimeTypes.APPLICATION_JSON)
				.getSchema(),
			openAPI
		)
			.getProperties()
			.get(rootPropertyName);

		if (rootSchema != null) {
			final Schema targetSchema = getTargetSchemaFromRefOrOneOf(rootSchema, openAPI);
			final Map<String, Schema> properties;
			if (targetSchema instanceof ArraySchema || OpenApiConstants.TYPE_ARRAY.equals(targetSchema.getType())) {
				properties = getTargetSchemaFromRefOrOneOf(targetSchema.getItems(), openAPI).getProperties();
			} else {
				properties = targetSchema.getProperties();
			}

			final Optional<Schema> propertySchema = getSchemaFromPropertiesByPropertyName(openAPI, properties, new LinkedList<>(), propertyName);
			if(propertySchema.isPresent()) {
				return propertySchema.get();
			}

			throw new RestQueryResolvingInternalError("Attribute wasn't found in Operation, unable to deserialize " +
				"attribute: " + propertyName, "Unexpected error when deserializing attribute: " + propertyName);
		} else {
			throw new RestQueryResolvingInternalError("Root attribute wasn't found in Operation, unable to deserialize " +
				"attribute: " + rootPropertyName, "Unexpected error when deserializing attribute: " + rootPropertyName);
		}
	}

	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Optional<Schema> getSchemaFromPropertiesByPropertyName(@Nonnull OpenAPI openAPI,
	                                                                      @Nullable Map<String, Schema> properties,
	                                                                      @Nonnull List<String> visitedReferences,
	                                                                      @Nonnull String propertyName) {
		if(properties == null) {
			return Optional.empty();
		}

		final Schema propertySchema = properties.get(propertyName);
		if(propertySchema != null) {
			return Optional.of(propertySchema);
		} else {
			for (Schema value : properties.values()) {
				final Schema schema;
				if(value instanceof ArraySchema arraySchema) {
					schema = arraySchema.getItems();
				} else {
					schema = value;
				}

				if (isSchemaReferenceVisited(visitedReferences, schema)) continue;

				//getTargetSchema method is used intentionally, attributes allOf, anyOf and oneOf are searched separately as in this case is legal that they will contain more than one reference
				final Optional<Schema> foundSchema = getSchemaFromPropertiesByPropertyName(openAPI, getTargetSchema(schema, openAPI).getProperties(), visitedReferences, propertyName);
				if(foundSchema.isPresent()) {
					return foundSchema;
				} else {
					if(schema.getAllOf() != null) {
						Optional<Schema> foundAllOfSchema = getSchemaFromSchemasByPropertyName(openAPI, visitedReferences, propertyName, schema.getAllOf());
						if (foundAllOfSchema.isPresent()) return foundAllOfSchema;
					}
					if(schema.getAnyOf() != null) {
						Optional<Schema> foundAnyOfSchema = getSchemaFromSchemasByPropertyName(openAPI, visitedReferences, propertyName, schema.getAnyOf());
						if (foundAnyOfSchema.isPresent()) return foundAnyOfSchema;
					}
					if(schema.getOneOf() != null) {
						Optional<Schema> oneOfSchema = getSchemaFromSchemasByPropertyName(openAPI, visitedReferences, propertyName, schema.getOneOf());
						if (oneOfSchema.isPresent()) return oneOfSchema;
					}
				}
			}

			return Optional.empty();
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static Optional<Schema> getSchemaFromSchemasByPropertyName(@Nonnull OpenAPI openAPI, @Nonnull List<String> visitedReferences,
	                                                                   @Nonnull String propertyName, @Nonnull List<Schema> schemas) {
		for (Schema schema : schemas) {
			if (isSchemaReferenceVisited(visitedReferences, schema)) continue;
			return getSchemaFromPropertiesByPropertyName(openAPI, getTargetSchemaFromRefOrOneOf(schema, openAPI).getProperties(), visitedReferences, propertyName);
		}
		return Optional.empty();
	}

	/**
	 * Checks whether schema has reference and whether that reference was already visited. If not then reference (if exists)
	 * is added into list of visited references. This is used to avoid endless loop when there's circular reference among schemas.

	 * @return <code>true</code> when reference was already visited.
	 */
	@SuppressWarnings("rawtypes")
	private static boolean isSchemaReferenceVisited(@Nonnull List<String> visitedReferences, @Nonnull Schema schema) {
		if(schema.get$ref() != null) {
			if(visitedReferences.contains(schema.get$ref())) {
				return true;
			} else {
				visitedReferences.add(schema.get$ref());
			}
		}
		return false;
	}

	@Nonnull
	public static String getSchemaNameFromReference(@Nonnull String reference) {
		return reference.substring(reference.lastIndexOf('/') + 1);
	}
}
