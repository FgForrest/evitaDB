/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.utils.StringUtils;
import lombok.Singular;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API-independent descriptor of union in schema-based external APIs. It joins multiple types into one union as
 * possible return types.
 *
 * @param name        name of type, if starts with *, it is treated only as suffix to the full name, if ends with *, it is treated only as prefix to the full name
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record UnionDescriptor(
	@Nonnull String name,
	@Nullable String description,
	@Nullable PropertyDescriptor discriminator,
	@Nonnull List<TypeDescriptor> types
) implements TypeDescriptor {

	public static UnionDescriptorBuilder builder() {
		return new UnionDescriptorBuilder();
	}

	public UnionDescriptor(
		@Nonnull String name,
		@Nullable String description,
		@Nullable PropertyDescriptor discriminator,
		@Nullable @Singular List<TypeDescriptor> types
	) {
		Assert.isPremiseValid(
			!name.isBlank(),
			() -> new ExternalApiInternalError("Name of union must be specified.")
		);
		this.name = name;
		this.description = description;
		this.discriminator = discriminator;
		this.types = types != null ? types : List.of();
	}

	@Nonnull
	public String name() {
		Assert.isPremiseValid(
			isNameStatic(),
			() -> new ExternalApiInternalError(
				"Object name `" + this.name + "` requires you to provide schema to construct the final name.")
		);
		return this.name;
	}

	@Nonnull
	public String name(@Nonnull Object... dynamicNames) {
		Assert.isPremiseValid(
			!isNameStatic(),
			() -> new ExternalApiInternalError("Object name `" + this.name + "` is static, thus it doesn't support provided schema.")
		);

		Assert.isPremiseValid(
			this.name.contains(NAME_WILDCARD_PLACEHOLDER),
			() -> new ExternalApiInternalError("Object name `" + this.name + "` doesn't contain wildcard placeholder but is not static. This should never happen.")
		);
		Assert.isPremiseValid(
			dynamicNames.length > 0,
			() -> new ExternalApiInternalError("Object name requires at least one dynamic name.")
		);

		final String dynamicName = Arrays.stream(dynamicNames)
			.filter(Objects::nonNull)
			.map(it -> {
				if (it instanceof NamedSchemaContract namedSchemaContract) {
					return namedSchemaContract.getNameVariant(ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION);
				} else {
					return StringUtils.toSpecificCase(it.toString(), ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION);
				}
			})
			.collect(Collectors.joining());

		if (this.name.equals(NAME_WILDCARD_PLACEHOLDER)) {
			return dynamicName;
		} else if (this.name.startsWith(NAME_WILDCARD_PLACEHOLDER)) {
			return dynamicName + this.name.substring(1);
		} else if (this.name.endsWith(NAME_WILDCARD_PLACEHOLDER)) {
			return this.name.substring(0, this.name.length() - 1) + dynamicName;
		} else {
			final String[] nameParts = NAME_WILDCARD_PLACEHOLDER_PATTERN.split(this.name);
			Assert.isPremiseValid(
				nameParts.length == 2,
				() -> new ExternalApiInternalError("There may be only one wildcard placeholder in object name.")
			);
			return nameParts[0] + dynamicName + nameParts[1];
		}
	}

	public boolean isNameStatic() {
		return !this.name.contains(NAME_WILDCARD_PLACEHOLDER);
	}

	@Nullable
	public String description(@Nonnull Object... args) {
		if (this.description == null) {
			return null;
		}
		return String.format(this.description, args);
	}

	/**
	 * Transform this generic union descriptor to API-specific schema union definition.
	 */
	public <T> T to(@Nonnull UnionDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}

	@ToString
	public static class UnionDescriptorBuilder {
		@Nullable private String name;
		@Nullable private String description;
		@Nullable private PropertyDescriptor discriminator;
		@Nonnull private final List<TypeDescriptor> types = new LinkedList<>();

		UnionDescriptorBuilder() {
		}

		@Nonnull
		public UnionDescriptorBuilder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		@Nonnull
		public UnionDescriptorBuilder description(@Nonnull String description) {
			this.description = description;
			return this;
		}

		@Nonnull
		public UnionDescriptorBuilder discriminator(@Nonnull PropertyDescriptor discriminator) {
			this.discriminator = discriminator;
			return this;
		}

		@Nonnull
		public UnionDescriptorBuilder type(@Nonnull TypeDescriptor type) {
			this.types.add(type);
			return this;
		}

		@Nonnull
		public UnionDescriptorBuilder types(@Nonnull Collection<? extends TypeDescriptor> types) {
			this.types.addAll(types);
			return this;
		}

		@Nonnull
		public UnionDescriptorBuilder typesFrom(@Nonnull UnionDescriptor unionDescriptor) {
			return types(unionDescriptor.types);
		}

		@Nonnull
		public UnionDescriptorBuilder clearTypes() {
			this.types.clear();
			return this;
		}

		@Nonnull
		public UnionDescriptor build() {
			Assert.isPremiseValid(
				this.name != null,
				"Name of union must be specified."
			);
			return new UnionDescriptor(
				this.name,
				this.description,
				this.discriminator,
				Collections.unmodifiableList(this.types)
			);
		}
	}
}
