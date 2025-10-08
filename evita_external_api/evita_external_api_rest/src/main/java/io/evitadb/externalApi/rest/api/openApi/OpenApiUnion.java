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
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

/**
 * Represents union of objects and must be globally registered in OpenAPI
 * so that there are no duplicates and client can generate prettier client libraries.
 *
 * It translates into generic {@link Schema} with oneOf, anyOf or allOf keywords.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiUnion implements OpenApiComplexType {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	/**
	 * How referenced objects will be combined into this new one
	 */
	@Nonnull
	private final OpenApiObjectUnionType type;
	/**
	 * Name of discriminator of union objects.
	 */
	@Nullable
	private final String discriminator;
	/**
	 * Objects that are combined to form new (this) object.
	 */
	@Nonnull
	private final List<OpenApiTypeReference> objects;

	/**
	 * Create new empty builder of object.
	 */
	@Nonnull
	public static Builder newUnion() {
		return new Builder();
	}

	/**
	 * Create new builder of existing object.
	 */
	@Nonnull
	public static Builder newUnion(@Nonnull OpenApiUnion existingUnion) {
		return new Builder(existingUnion);
	}

	@Nonnull
	@Override
	public Schema<Object> toSchema() {
		final Schema<Object> schema = new Schema<>();
		switch (this.type) {
			case ONE_OF -> this.objects.forEach(it -> schema.addOneOfItem(it.toSchema()));
			case ANY_OF -> this.objects.forEach(it -> schema.addAnyOfItem(it.toSchema()));
			case ALL_OF -> this.objects.forEach(it -> schema.addAllOfItem(it.toSchema()));
		}
		if (this.discriminator != null) {
			schema.discriminator(new Discriminator().propertyName(this.discriminator));
		}

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
		@Nonnull
		private OpenApiObjectUnionType type = OpenApiObjectUnionType.ONE_OF;
		@Nullable
		private String discriminator;
		@Nonnull
		private final List<OpenApiTypeReference> objects;

		private Builder() {
			this.objects = new LinkedList<>();
		}

		private Builder(@Nonnull OpenApiUnion existingObject) {
			this(
				existingObject.name,
				existingObject.description,
				existingObject.deprecationNotice,
				existingObject.type,
				existingObject.discriminator,
				existingObject.objects
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
		 * Sets type of union (used only if {@link #object(OpenApiTypeReference)} is used as well). Default is {@link OpenApiObjectUnionType#ONE_OF}.
		 */
		@Nonnull
		public Builder type(@Nonnull OpenApiObjectUnionType unionType) {
			this.type = unionType;
			return this;
		}

		/**
		 * Sets name of union discriminator (used only if {@link #object(OpenApiTypeReference)} is used as well).
		 */
		@Nonnull
		public Builder discriminator(@Nonnull String unionDiscriminator) {
			this.discriminator = unionDiscriminator;
			return this;
		}

		/**
		 * Adds union object. Make sure to set correct {@link #type(OpenApiObjectUnionType)} and {@link #discriminator(String)}.
		 */
		@Nonnull
		public Builder object(@Nonnull OpenApiTypeReference unionObject) {
			this.objects.add(unionObject);
			return this;
		}

		@Nonnull
		public OpenApiUnion build() {
			Assert.isPremiseValid(
				this.name != null && !this.name.isEmpty(),
				() -> new OpenApiBuildingError("Missing object name.")
			);
			Assert.isPremiseValid(
				!this.objects.isEmpty(),
				() -> new OpenApiBuildingError("Missing union objects")
			);
			return new OpenApiUnion(this.name, this.description, this.deprecationNotice, this.type, this.discriminator, this.objects);
		}
	}
}
