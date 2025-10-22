/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API-independent descriptor of union in schema-based external APIs. It joins multiple types into one union as
 * possible return types.
 *
 * @param name name of type, if starts with *, it is treated only as suffix to the full name, if ends with *, it is treated only as prefix to the full name
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record UnionDescriptor(
	@Nonnull String name,
	@Nullable String description,
	@Nonnull PropertyDescriptor discriminator,
	@Nonnull List<TypeDescriptor> types
) implements TypeDescriptor {

	@Builder
	public UnionDescriptor(
		@Nonnull String name,
		@Nullable String description,
		@Nonnull PropertyDescriptor discriminator,
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
	 * Transform this generic union descriptor to API-specific schema union definition.
	 */
	public <T> T to(@Nonnull UnionDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}
}
