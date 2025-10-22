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
import lombok.Singular;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API-independent descriptor of single object in schema-based external APIs. This generic object description can be transformed
 * to actual API-specific object definition using {@link #to(ObjectDescriptorTransformer)}.
 *
 * @param name name of object, if starts with *, it is treated only as suffix to the full name, if ends with *, it is treated only as prefix to the full name
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 * @param staticProperties list of static fields that can be safely added to built object without additional configuration
 * @param interfaceDescriptor if present, the object will implement the specified interface
 * @param representedClass reference to a class that this descriptor represents in API
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record ObjectDescriptor(@Nonnull String name,
                               @Nullable String description,
                               @Nonnull List<PropertyDescriptor> staticProperties,
                               @Nullable ObjectDescriptor interfaceDescriptor,
                               @Nullable Class<?> representedClass) implements TypeDescriptor {

	@Builder
	public ObjectDescriptor(
		@Nullable String name,
        @Nullable String description,
        @Nullable @Singular List<PropertyDescriptor> staticProperties,
		@Nullable ObjectDescriptor interfaceDescriptor,
		@Nullable Class<?> representedClass
	) {
		Assert.isPremiseValid(
			(name != null && !name.isBlank()) || representedClass != null,
			() -> new ExternalApiInternalError("Name of object must be specified directly or via represented class.")
		);
		this.name = name != null ? name : representedClass.getSimpleName();
		this.description = description;
		this.staticProperties = staticProperties != null ? staticProperties : List.of();
		this.interfaceDescriptor = interfaceDescriptor;
		this.representedClass = representedClass;
	}

	/**
	 * Creates a new descriptor of an object implementing a specified interface.
	 *
	 * Note: to add additional static fields instead of replacing extended ones, use the `staticField` builder method instead
	 * of the `staticFields` builder method.
	 */
	@Nonnull
	public static ObjectDescriptorBuilder implementing(@Nonnull ObjectDescriptor interfaceDescriptor) {
		return from(interfaceDescriptor)
			.interfaceDescriptor(interfaceDescriptor);
	}

	/**
	 * Creates a new descriptor with all the properties of the specified one. Note that {@link ObjectDescriptor#name} is not
	 * being transferred to prevent name duplication.
	 *
	 * Note: to add additional static fields instead of replacing extended ones, use the `staticField` builder method instead
	 * of the `staticFields` builder method.
	 */
	@Nonnull
	public static ObjectDescriptorBuilder from(@Nonnull ObjectDescriptor objectDescriptor) {
		return from(objectDescriptor, null);
	}

	/**
	 * Creates a new descriptor with all the properties of the specified one. Note that {@link ObjectDescriptor#name} is not
	 * being transferred to prevent name duplication.
	 *
	 * Note: to add additional static fields instead of replacing extended ones, use the `staticField` builder method instead
	 * of the `staticFields` builder method.
	 */
	@Nonnull
	public static ObjectDescriptorBuilder from(
		@Nonnull ObjectDescriptor objectDescriptor,
		@Nullable Predicate<PropertyDescriptor> withoutProperties
	) {
		return builder()
			.description(objectDescriptor.description())
			.staticProperties(new ArrayList<>(
				withoutProperties == null
					? objectDescriptor.staticProperties()
					: objectDescriptor.staticProperties()
						.stream()
						.filter(it -> !withoutProperties.test(it))
						.collect(Collectors.toList())
			));
	}

	@Nonnull
	public String name() {
		Assert.isPremiseValid(
			isNameStatic(),
			() -> new ExternalApiInternalError("Object name `" + this.name + "` requires you to provide schema to construct the final name.")
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
			() -> new ExternalApiInternalError("Object name `" + this.name + "` is static, thus it doesn't support provided schema.")
		);
		Assert.isPremiseValid(
			schema.length > 0,
			() -> new ExternalApiInternalError("Object name requires at least one provided schema.")
		);

		final String schemaName = Stream.concat(
				Arrays.stream(schema)
					.map(it -> it.getNameVariant(ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION)),
				Stream.of(suffix)
					.filter(Objects::nonNull)
			)
			.collect(Collectors.joining());

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

	public boolean isNameStatic() {
		return !this.name.contains(NAME_WILDCARD);
	}

	@Nullable
	public String description(@Nonnull Object... args) {
		if (this.description == null) {
			return null;
		}
		return String.format(this.description, args);
	}

	/**
	 * Whether this descriptor represents a specific class.
	 */
	public boolean representsClass() {
		return this.representedClass != null;
	}

	/**
	 * Returns the class that this descriptor represents in API. If not present, exception is thrown.
	 * To check if the descriptor represents a class, use {@link #representsClass()}.
	 */
	@Nonnull
	public Class<?> representedClass() {
		if (this.representedClass == null) {
			throw new ExternalApiInternalError("Object descriptor doesn't represent any specific class.");
		}
		return this.representedClass;
	}

	/**
	 * Transform this generic object descriptor to API-specific schema object definition.
	 */
	public <T> T to(@Nonnull ObjectDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}
}
