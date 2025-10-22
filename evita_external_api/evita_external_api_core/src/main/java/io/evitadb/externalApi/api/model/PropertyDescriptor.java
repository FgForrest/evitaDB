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

package io.evitadb.externalApi.api.model;

import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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

	public static String NAME_WILDCARD = "*";

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
	public String name() {
		Assert.isPremiseValid(
			isNameStatic(),
			() -> new ExternalApiInternalError("Property name `" + this.name + "` requires you to provide schema to construct the final name.")
		);
		return this.name;
	}

	@Nonnull
	public String name(@Nonnull NamedSchemaContract... schema) {
		return name(null, schema);
	}

	@Nonnull
	public String name(@Nullable String suffix, @Nonnull NamedSchemaContract... schema) {
		Assert.isPremiseValid(
			!isNameStatic(),
			() -> new ExternalApiInternalError("Property name `" + this.name + "` is static, thus it doesn't support provided schema.")
		);
		Assert.isPremiseValid(
			schema.length > 0,
			() -> new ExternalApiInternalError("Property name requires at least one provided schema.")
		);

		final List<String> schemaNameBuilder = new ArrayList<>(schema.length + 1);
		for (int i = 0; i < schema.length; i++) {
			final NamedSchemaContract schemaItem = schema[i];
			if (i == 0) {
				schemaNameBuilder.add(schemaItem.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION));
			} else {
				schemaNameBuilder.add(schemaItem.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_PART_NAMING_CONVENTION));
			}
		}
		if (suffix != null) {
			schemaNameBuilder.add(suffix);
		}

		final String schemaName = String.join("", schemaNameBuilder);
		if (this.name.equals(NAME_WILDCARD)) {
			return schemaName;
		} else if (this.name.startsWith(NAME_WILDCARD)) {
			return schemaName + this.name.substring(1);
		} else if (this.name.endsWith(NAME_WILDCARD)) {
			return this.name.substring(0, this.name.length() - 1) + schemaName;
		} else {
			throw new ExternalApiInternalError("Unsupported placement of name wildcard. Wildcard must be at the beginning or at the end.");
		}
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
	public TypePropertyDataTypeDescriptor objectType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof TypePropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not object.")
		);
		return (TypePropertyDataTypeDescriptor) type();
	}

	public boolean isNameStatic() {
		return !this.name.contains(NAME_WILDCARD);
	}

	/**
	 * Transform this generic property descriptor to API-specific schema definition.
	 */
	public <T> T to(@Nonnull PropertyDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}

	public static PropertyDescriptor nullableFromObject(
		@Nonnull String propertyName,
		@Nonnull ObjectDescriptor objectReference
	) {
		return fromObject(propertyName, objectReference, false, false);
	}

	public static PropertyDescriptor nonNullFromObject(
		@Nonnull String propertyName,
		@Nonnull ObjectDescriptor objectReference
	) {
		return fromObject(propertyName, objectReference, true, false);
	}

	public static PropertyDescriptor nullableListFromObject(
		@Nonnull String propertyName,
		@Nonnull ObjectDescriptor objectReference
	) {
		return fromObject(propertyName, objectReference, false, true);
	}

	public static PropertyDescriptor nonNullListFromObject(
		@Nonnull String propertyName,
		@Nonnull ObjectDescriptor objectReference
	) {
		return fromObject(propertyName, objectReference, true, true);
	}

	private static PropertyDescriptor fromObject(
		@Nonnull String propertyName,
		@Nonnull ObjectDescriptor objectReference,
		boolean nonNull,
		boolean list
	) {
		return PropertyDescriptor.builder()
			.name(propertyName)
			.description(objectReference.description())
			.type(new TypePropertyDataTypeDescriptor(objectReference, nonNull, list))
			.build();
	}
}
