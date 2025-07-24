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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
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
