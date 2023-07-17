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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents dictionary (or map) with pre-defined type of keys (which are always string) and type of values
 * and must be globally registered in OpenAPI
 * so that there are no duplicates and client can generate prettier client libraries.
 *
 * It translates into {@link ObjectSchema} additional properties.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiDictionary implements OpenApiComplexType {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nonnull
	private final OpenApiSimpleType valueType;

	/**
	 * Create new empty builder of object.
	 */
	@Nonnull
	public static Builder newDictionary() {
		return new Builder();
	}

	/**
	 * Create new builder of existing object.
	 */
	@Nonnull
	public static Builder newDictionary(@Nonnull OpenApiDictionary existingDictionary) {
		return new Builder(existingDictionary);
	}

	@Nonnull
	@Override
	public Schema<Object> toSchema() {
		final Schema<Object> schema = new ObjectSchema();
		schema.additionalProperties(valueType.toSchema());

		schema.name(this.name);
		schema.description(this.description);
		if (this.deprecationNotice != null) {
			schema.deprecated(true); // openapi doesn't support false here
		}

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
		private OpenApiSimpleType valueType;

		private Builder() {}

		private Builder(@Nonnull OpenApiDictionary existingObject) {
			this(
				existingObject.name,
				existingObject.description,
				existingObject.deprecationNotice,
				existingObject.valueType
			);
		}

		/**
		 * Sets name of the object.
		 */
		@Nonnull
		public Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets description of the object.
		 */
		@Nonnull
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets deprecation notice of the object to indicate that the object is deprecated. If null, object is not set
		 * as deprecated.
		 */
		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		/**
		 * Sets type of values.
		 */
		@Nonnull
		public Builder valueType(@Nonnull OpenApiSimpleType valueType) {
			this.valueType = valueType;
			return this;
		}

		@Nonnull
		public OpenApiDictionary build() {
			Assert.isPremiseValid(
				name != null && !name.isEmpty(),
				() -> new OpenApiBuildingError("Missing object name.")
			);
			Assert.isPremiseValid(
				valueType != null,
				() -> new OpenApiBuildingError("Missing value type.")
			);
			return new OpenApiDictionary(name, description, deprecationNotice, valueType);
		}
	}
}
