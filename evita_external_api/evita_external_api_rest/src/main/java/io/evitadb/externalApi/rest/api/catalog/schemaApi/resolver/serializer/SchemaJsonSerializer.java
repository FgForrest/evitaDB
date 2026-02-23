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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntityAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Ancestor for evitaDB internal schema serialization to JSON.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class SchemaJsonSerializer {

	@Nonnull
	protected final ObjectJsonSerializer objectJsonSerializer;

	/**
	 * Creates and populates an {@link ObjectNode} with the common properties shared by all
	 * {@link NamedSchemaWithDeprecationContract} implementations: name, name variants, description,
	 * and deprecation notice. Callers can add type-specific fields to the returned node.
	 *
	 * @param schema the named schema with deprecation to serialize
	 * @return a pre-populated {@link ObjectNode} with the common schema properties
	 */
	@Nonnull
	protected ObjectNode serializeNamedSchemaBase(@Nonnull NamedSchemaWithDeprecationContract schema) {
		final ObjectNode node = this.objectJsonSerializer.objectNode();
		node.putIfAbsent(NamedSchemaDescriptor.NAME.name(), this.objectJsonSerializer.serializeObject(schema.getName()));
		node.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(schema.getNameVariants()));
		node.putIfAbsent(NamedSchemaDescriptor.DESCRIPTION.name(), schema.getDescription() != null ? this.objectJsonSerializer.serializeObject(schema.getDescription()) : null);
		node.putIfAbsent(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), schema.getDeprecationNotice() != null ? this.objectJsonSerializer.serializeObject(schema.getDeprecationNotice()) : null);
		return node;
	}

	/**
	 * Serializes an {@link AttributeSchemaContract} into a JSON object node with all common attribute properties.
	 * Handles subtype-specific fields via {@code instanceof} checks for {@link GlobalAttributeSchemaContract}
	 * (global uniqueness type) and {@link EntityAttributeSchemaContract} (representative flag).
	 *
	 * @param attributeSchema the attribute schema to serialize
	 * @return an {@link ObjectNode} containing all serialized attribute schema properties
	 */
	@Nonnull
	protected ObjectNode serializeAttributeSchema(@Nonnull AttributeSchemaContract attributeSchema) {
		final ObjectNode attributeSchemaNode = serializeNamedSchemaBase(attributeSchema);
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), serializeUniquenessType(attributeSchema::getUniquenessType));
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			attributeSchemaNode.putIfAbsent(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), serializeGlobalUniquenessType(globalAttributeSchema::getGlobalUniquenessType));
		}
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.FILTERABLE.name(), serializeFlagInScopes(attributeSchema::isFilterableInScope));
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.SORTABLE.name(), serializeFlagInScopes(attributeSchema::isSortableInScope));
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.LOCALIZED.name(), this.objectJsonSerializer.serializeObject(attributeSchema.isLocalized()));
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.NULLABLE.name(), this.objectJsonSerializer.serializeObject(attributeSchema.isNullable()));
		if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			attributeSchemaNode.put(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), entityAttributeSchema.isRepresentative());
		}
		attributeSchemaNode.put(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()));
		attributeSchemaNode.set(
			AttributeSchemaDescriptor.DEFAULT_VALUE.name(),
			Optional.ofNullable(attributeSchema.getDefaultValue())
				.map(this.objectJsonSerializer::serializeObject)
				.orElse(null)
		);
		attributeSchemaNode.put(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces());

		return attributeSchemaNode;
	}

	/**
	 * Serializes a map of naming conventions and associated name variants into a JSON object node.
	 * Each entry in the map is represented as a JSON property, where the property name corresponds to the naming convention,
	 * and the value corresponds to the associated name variant.
	 *
	 * @param nameVariants a map where the key is a {@link NamingConvention} and the value is the corresponding name variant
	 * @return an {@link ObjectNode} containing the serialized name variants
	 */
	@Nonnull
	protected ObjectNode serializeNameVariants(@Nonnull Map<NamingConvention, String> nameVariants) {
		final ObjectNode nameVariantsNode = this.objectJsonSerializer.objectNode();
		nameVariantsNode.put(NameVariantsDescriptor.CAMEL_CASE.name(), nameVariants.get(NamingConvention.CAMEL_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.PASCAL_CASE.name(), nameVariants.get(NamingConvention.PASCAL_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.SNAKE_CASE.name(), nameVariants.get(NamingConvention.SNAKE_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), nameVariants.get(NamingConvention.UPPER_SNAKE_CASE));
		nameVariantsNode.put(NameVariantsDescriptor.KEBAB_CASE.name(), nameVariants.get(NamingConvention.KEBAB_CASE));

		return nameVariantsNode;
	}


	/**
	 * Serializes the scopes filtered by the provided predicate into a JSON array structure.
	 * The resulting array includes only the scopes that satisfy the given predicate.
	 *
	 * @param flagPredicate a predicate to filter the scopes to serialize
	 * @return a JSON node representing the serialized array of filtered scopes
	 */
	@Nonnull
	protected JsonNode serializeFlagInScopes(@Nonnull Predicate<Scope> flagPredicate) {
		return this.objectJsonSerializer.serializeArray(Arrays.stream(Scope.values()).filter(flagPredicate).toArray(Scope[]::new));
	}

	/**
	 * Serializes the reference index types within the given {@link ReferenceSchemaContract} into a JSON array structure.
	 * Each entry in the resulting array represents a reference index type described by its scope and index type.
	 *
	 * @param referenceSchema the reference schema containing the reference index types to be serialized
	 * @return an {@link ArrayNode} representing the serialized reference index types
	 */
	@Nonnull
	protected ArrayNode serializeReferenceIndexTypes(@Nonnull ReferenceSchemaContract referenceSchema) {
		final ArrayNode referenceIndexesArray = this.objectJsonSerializer.arrayNode();
		referenceSchema.getReferenceIndexTypeInScopes()
			.entrySet()
			.stream()
			.map(it -> {
				final ObjectNode referenceIndexTypeNode = this.objectJsonSerializer.objectNode();
				referenceIndexTypeNode.put(ScopedDataDescriptor.SCOPE.name(), it.getKey().name());
				referenceIndexTypeNode.put(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), it.getValue().name());
				return referenceIndexTypeNode;
			})
			.forEach(referenceIndexesArray::add);
		return referenceIndexesArray;
	}

	/**
	 * Serializes the indexed components within the given {@link ReferenceSchemaContract} into a JSON array structure.
	 * Each entry in the resulting array represents a scoped set of indexed components described by its scope
	 * and an array of {@link ReferenceIndexedComponents} values.
	 *
	 * @param referenceSchema the reference schema containing the indexed components to be serialized
	 * @return an {@link ArrayNode} representing the serialized indexed components
	 */
	@Nonnull
	protected ArrayNode serializeReferenceIndexedComponents(@Nonnull ReferenceSchemaContract referenceSchema) {
		final ArrayNode indexedComponentsArray = this.objectJsonSerializer.arrayNode();
		final Map<Scope, Set<ReferenceIndexedComponents>> components = referenceSchema.getIndexedComponentsInScopes();
		for (Map.Entry<Scope, Set<ReferenceIndexedComponents>> entry : components.entrySet()) {
			final ObjectNode componentNode = this.objectJsonSerializer.objectNode();
			componentNode.put(ScopedDataDescriptor.SCOPE.name(), entry.getKey().name());
			componentNode.set(
				ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
				this.objectJsonSerializer.serializeArray(entry.getValue().toArray(ReferenceIndexedComponents[]::new))
			);
			indexedComponentsArray.add(componentNode);
		}
		return indexedComponentsArray;
	}

	/**
	 * Serializes the uniqueness type of attributes for different scopes into a JSON array structure.
	 * Each scope is represented as a JSON object containing the scope name and its corresponding uniqueness type.
	 *
	 * @param uniquenessTypeAccessor a function that provides the {@link AttributeUniquenessType} for a given {@link Scope}
	 * @return a {@link JsonNode} representing an array of JSON objects, where each object contains a scope name and the associated uniqueness type
	 */
	@Nonnull
	protected JsonNode serializeUniquenessType(@Nonnull Function<Scope, AttributeUniquenessType> uniquenessTypeAccessor) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final ObjectNode attributeUniquenessType = this.objectJsonSerializer.objectNode();
				attributeUniquenessType.put(ScopedDataDescriptor.SCOPE.name(), scope.name());
				attributeUniquenessType.put(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), uniquenessTypeAccessor.apply(scope).name());
				return attributeUniquenessType;
			})
			.collect(this.objectJsonSerializer::arrayNode, ArrayNode::add, ArrayNode::addAll);
	}

	/**
	 * Serializes the global uniqueness types of attributes for different scopes into a JSON array structure.
	 * Each scope is represented as a JSON object containing the scope name and its corresponding global attribute uniqueness type.
	 *
	 * @param uniquenessTypeAccessor a function that provides the {@link GlobalAttributeUniquenessType} for a given {@link Scope}
	 * @return a {@link JsonNode} representing an array of JSON objects, where each object contains a scope name and the associated global attribute uniqueness type
	 */
	@Nonnull
	protected JsonNode serializeGlobalUniquenessType(@Nonnull Function<Scope, GlobalAttributeUniquenessType> uniquenessTypeAccessor) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final ObjectNode attributeUniquenessType = this.objectJsonSerializer.objectNode();
				attributeUniquenessType.put(ScopedDataDescriptor.SCOPE.name(), scope.name());
				attributeUniquenessType.put(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), uniquenessTypeAccessor.apply(scope).name());
				return attributeUniquenessType;
			})
			.collect(this.objectJsonSerializer::arrayNode, ArrayNode::add, ArrayNode::addAll);
	}
}
