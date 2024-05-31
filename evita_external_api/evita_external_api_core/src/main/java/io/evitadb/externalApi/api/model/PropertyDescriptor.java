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

package io.evitadb.externalApi.api.model;

import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Generic API-independent descriptor for properties like field or arguments in schema-based external APIs. This generic descriptor can be transformed
 * to actual API-specific definitions using {@link #to(PropertyDescriptorTransformer)}.
 *
 * @param name name of property
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 * @param deprecate if present, the property should be marked as deprecated with the specified reason
 * @param type optional type descriptor
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Builder
public record PropertyDescriptor(@Nonnull String name,
                                 @Nonnull String description,
								 @Nullable String deprecate,
                                 @Nullable PropertyDataTypeDescriptor type,
                                 @Nullable Object defaultValue) {

	public PropertyDescriptor {
		Assert.isPremiseValid(
			!name.isEmpty(),
			() -> new ExternalApiInternalError("Name of property cannot be empty.")
		);
		Assert.isPremiseValid(
			!description.isEmpty(),
			() -> new ExternalApiInternalError("Description of property `" + name() + "` cannot be empty.")
		);
	}

	public PropertyDescriptor(@Nonnull String name, @Nonnull String description) {
		this(name, description, null, null, null);
	}

	/**
	 * Creates new descriptor extending all properties of specified one.
	 */
	@Nonnull
	public static PropertyDescriptorBuilder extend(@Nonnull PropertyDescriptor propertyDescriptor) {
		return builder()
			.name(propertyDescriptor.name())
			.description(propertyDescriptor.description())
			.deprecate(propertyDescriptor.deprecate())
			.type(propertyDescriptor.type())
			.defaultValue(propertyDescriptor.defaultValue());
	}

	@Nonnull
	public String description(@Nonnull Object... args) {
		return String.format(this.description, args);
	}

	@Nullable
	public PrimitivePropertyDataTypeDescriptor primitiveType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof PrimitivePropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not primitive.")
		);
		return (PrimitivePropertyDataTypeDescriptor) type();
	}

	@Nullable
	public ObjectPropertyDataTypeDescriptor objectType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof ObjectPropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not object.")
		);
		return (ObjectPropertyDataTypeDescriptor) type();
	}

	/**
	 * Transform this generic property descriptor to API-specific schema definition.
	 */
	public <T> T to(@Nonnull PropertyDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}

	public static PropertyDescriptor nullableFromObject(@Nonnull ObjectDescriptor objectReference) {
		return fromObject(objectReference, false, false);
	}

	public static PropertyDescriptor nonNullFromObject(@Nonnull ObjectDescriptor objectReference) {
		return fromObject(objectReference, true, false);
	}

	public static PropertyDescriptor nullableListFromObject(@Nonnull ObjectDescriptor objectReference) {
		return fromObject(objectReference, false, true);
	}

	public static PropertyDescriptor nonNullListFromObject(@Nonnull ObjectDescriptor objectReference) {
		return fromObject(objectReference, true, true);
	}

	private static PropertyDescriptor fromObject(@Nonnull ObjectDescriptor objectReference, boolean nonNull, boolean list) {
		return PropertyDescriptor.builder()
			.name(StringUtils.toSpecificCase(objectReference.name(), ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION))
			.description(objectReference.description())
			.type(new ObjectPropertyDataTypeDescriptor(objectReference, nonNull, list))
			.build();
	}
}
