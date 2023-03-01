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

package io.evitadb.externalApi.rest.api.dto;

import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class OpenApiEnum implements OpenApiComplexType {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nullable
	private final String format;
	@Nonnull
	private final List<String> items;

	@Nonnull
	public static OpenApiEnum enumFrom(@Nonnull Class<? extends Enum<?>> javaEnum) {
		final Builder enumBuilder = newEnum()
			.name(javaEnum.getSimpleName());

		for (Enum<?> enumItem : javaEnum.getEnumConstants()) {
			enumBuilder.item(enumItem.name());
		}

		return enumBuilder.build();
	}

	/**
	 * Create new empty builder of enum.
	 */
	@Nonnull
	public static Builder newEnum() {
		return new Builder();
	}

	/**
	 * Create new builder of existing enum.
	 */
	@Nonnull
	public static Builder newEnum(@Nonnull OpenApiEnum existingEnum) {
		return new Builder(existingEnum);
	}

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		final Schema<String> schema = new StringSchema();
		schema.format(this.format);

		schema.name(this.name);
		schema.description(this.description);
		if (this.deprecationNotice != null) {
			schema.deprecated(true);
		}

		this.items.forEach(schema::addEnumItemObject);
		schema.example(this.items.get(0));

		return schema;
	}

	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Builder {

		@Nullable
		private String name;
		@Nullable
		private String description;
		@Nullable
		private String deprecationNotice;
		@Nullable
		private String format;
		@Nonnull
		private final List<String> items;

		private Builder() {
			this.items = new LinkedList<>();
		}

		private Builder(@Nonnull OpenApiEnum existingObject) {
			this(
				existingObject.name,
				existingObject.description,
				existingObject.deprecationNotice,
				existingObject.format,
				new LinkedList<>(existingObject.items)
			);
		}

		@Nonnull
		public Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		@Nonnull
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		@Nonnull
		public Builder format(@Nullable String format) {
			this.format = format;
			return this;
		}

		@Nonnull
		public Builder item(@Nonnull String item) {
			items.add(item);
			return this;
		}

		@Nonnull
		public OpenApiEnum build() {
			Assert.isPremiseValid(
				name != null && !name.isEmpty(),
				() -> new OpenApiSchemaBuildingError("Missing enum name.")
			);
			Assert.isPremiseValid(
				!items.isEmpty(),
				() -> new OpenApiSchemaBuildingError("Enum `" + name + "` is missing items.")
			);
			return new OpenApiEnum(name, description, deprecationNotice, format, items);
		}
	}
}
