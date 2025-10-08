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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Single named property of {@link OpenApiObject}. Because of limitations of OpenAPI, a property is represented by
 * {@link Schema} with copied data from {@link OpenApiProperty#type}, and thus the type needs to be a {@link OpenApiSimpleType}
 * to not rewrite any global data of the referenced object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiProperty {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nonnull
	private final OpenApiSimpleType type;

	/**
	 * Create new empty builder of property.
	 */
	@Nonnull
	public static Builder newProperty() {
		return new Builder();
	}

	/**
	 * Create new builder of existing property.
	 */
	@Nonnull
	public static Builder newProperty(@Nonnull OpenApiProperty existingProperty) {
		return new Builder(existingProperty);
	}

	public boolean isNonNull() {
		return this.type instanceof OpenApiNonNull;
	}

	/**
	 * Returns OpenAPI schema representing property type
	 */
	@Nonnull
	public Schema<?> getSchema() {
		return toSchema(this.type);
	}

	/**
	 * Converts type to OpenAPI schema equivalent.
	 */
	@Nonnull
	private Schema<?> toSchema(@Nonnull OpenApiSimpleType type) {
		final Schema<Object> schema = new Schema<>();
		schema.name(this.name);

		if (type instanceof OpenApiScalar scalarType) {
			final Schema<?> scalarTypeSchema = scalarType.toSchema();

			schema.description(this.description);
			if (this.deprecationNotice != null) {
				schema.deprecated(true); // openapi doesn't support false here
			}

			if (scalarTypeSchema.getType() != null) {
				schema.type(scalarTypeSchema.getType());
				schema.addType(scalarTypeSchema.getType());
			}
			if (!scalarType.isRange()) {
				schema.format(scalarTypeSchema.getFormat());
				schema.pattern(scalarTypeSchema.getPattern());
				if (scalarTypeSchema.getExample() != null) {
					schema.example(scalarTypeSchema.getExample());
				}

				schema.minimum(scalarTypeSchema.getMinimum());
				schema.maximum(scalarTypeSchema.getMaximum());

				schema.minLength(scalarTypeSchema.getMinLength());
				schema.maxLength(scalarTypeSchema.getMaxLength());

				schema.oneOf(scalarTypeSchema.getOneOf());
				schema.anyOf(scalarTypeSchema.getAnyOf());
				schema.allOf(scalarTypeSchema.getAllOf());
			} else {
				// scalar may be range, which is represented by OpenAPI array
				schema.items(scalarTypeSchema.getItems());

				schema.minItems(scalarTypeSchema.getMinItems());
				schema.maxItems(scalarTypeSchema.getMaxItems());
			}
		} else if (type instanceof OpenApiTypeReference referenceType) {
			schema.$ref(referenceType.toSchema().get$ref());
			// When using ref description and deprecated flag cannot be used because of OpenAPI limitations.
			// There is way to do that using oneOf but that generates ugly clients.
		} else if (type instanceof OpenApiArray arrayType) {
			final Schema<?> arrayTypeSchema = arrayType.toSchema();

			schema.description(this.description);
			if (this.deprecationNotice != null) {
				schema.deprecated(true); // openapi doesn't support false here
			}

			schema.type(arrayTypeSchema.getType());
			schema.items(arrayTypeSchema.getItems());

			schema.minItems(arrayTypeSchema.getMinItems());
			schema.maxItems(arrayTypeSchema.getMaxItems());
		} else if (type instanceof OpenApiNonNull nonNullType) {
			return toSchema(nonNullType.getWrappedType());
		} else {
			throw new OpenApiBuildingError("Unknown type `" + this.type.getClass().getName() + "`.");
		}

		return schema;
	}

	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Builder {

		@Nullable
		private String name;
		@Nullable
		private String description;
		@Nullable
		private String deprecationNotice;
		@Nullable
		private OpenApiSimpleType type;

		private Builder(@Nonnull OpenApiProperty existingProperty) {
			this(existingProperty.name, existingProperty.description, existingProperty.deprecationNotice, existingProperty.type);
		}

		/**
		 * Sets name of the property.
		 */
		@Nonnull
		public Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets description of the property.
		 */
		@Nonnull
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets deprecation notice of the property to indicate that the property is deprecated. If null, property is not
		 * set as deprecated.
		 */
		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		/**
		 * Sets type of the property.
		 */
		@Nonnull
		public Builder type(@Nonnull OpenApiSimpleType type) {
			this.type = type;
			return this;
		}

		@Nonnull
		public OpenApiProperty build() {
			Assert.isPremiseValid(
				this.name != null && !this.name.isEmpty(),
				() -> new OpenApiBuildingError("Missing property name.")
			);
			Assert.isPremiseValid(
				this.type != null,
				() -> new OpenApiBuildingError("Property `" + this.name + "` is missing type.")
			);
			return new OpenApiProperty(this.name, this.description, this.deprecationNotice, this.type);
		}
	}
}
