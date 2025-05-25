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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Single parameter of {@link OpenApiEndpoint}. Support both path parameter and query parameter as those are basically
 * same, just have different locations.
 *
 * It translates to {@link Parameter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
public class OpenApiEndpointParameter {

	private static final ObjectJsonSerializer DEFAULT_VALUE_SERIALIZER = new ObjectJsonSerializer(new ObjectMapper());

	@Nonnull
	@Getter
	private final ParameterLocation location;
	@Nonnull
	@Getter
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nonnull
	private final OpenApiSimpleType type;
	@Nullable
	private final Object defaultValue;

	@Nonnull
	public static OpenApiEndpointParameter.Builder newPathParameter() {
		return new Builder(ParameterLocation.PATH);
	}

	@Nonnull
	public static OpenApiEndpointParameter.Builder newQueryParameter() {
		return new Builder(ParameterLocation.QUERY);
	}

	@Nonnull
	public Parameter toParameter() {
		final Parameter parameter = new Parameter();
		parameter.in(this.location.getLocation());
		parameter.name(this.name);
		parameter.description(this.description);
		if (this.deprecationNotice != null) {
			parameter.deprecated(true); // openapi doesn't support false here
		}
		if (this.type instanceof OpenApiNonNull) {
			parameter.required(true); // openapi doesn't support false here
		}
		parameter.schema(this.type.toSchema());
		if (this.defaultValue != null) {
			parameter.getSchema().setDefault(DEFAULT_VALUE_SERIALIZER.serializeObject(this.defaultValue));
		}
		return parameter;
	}

	public static class Builder {

		@Nonnull
		private final ParameterLocation location;
		@Nullable
		private String name;
		@Nullable
		private String description;
		@Nullable
		private String deprecationNotice;
		@Nullable
		private OpenApiSimpleType type;
		@Nullable
		private Object defaultValue;

		public Builder(@Nonnull ParameterLocation location) {
			this.location = location;
		}

		/**
		 * Sets name of the parameter.
		 */
		@Nonnull
		public Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets description of the parameter.
		 */
		@Nonnull
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets deprecation notice of the parameter to indicate that the parameter is deprecated. If null, parameter is
		 * not set as deprecated.
		 */
		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		/**
		 * Sets type of the parameter.
		 */
		@Nonnull
		public Builder type(@Nonnull OpenApiSimpleType type) {
			this.type = type;
			return this;
		}

		/**
		 * Sets default value of the parameter. It is supported only for query parameters.
		 */
		@Nonnull
		public Builder defaultValue(@Nonnull Object defaultValue) {
			Assert.isPremiseValid(
				this.location == ParameterLocation.QUERY,
				() -> new OpenApiBuildingError("Default value is supported only for query parameters.")
			);
			this.defaultValue = defaultValue;
			return this;
		}

		@Nonnull
		public OpenApiEndpointParameter build() {
			Assert.isPremiseValid(
				this.name != null && !this.name.isEmpty(),
				() -> new OpenApiBuildingError("Missing parameter name.")
			);
			Assert.isPremiseValid(
				this.type != null,
				() -> new OpenApiBuildingError("Parameter `" + this.name + "` is missing type.")
			);
			return new OpenApiEndpointParameter(this.location, this.name, this.description, this.deprecationNotice, this.type, this.defaultValue);
		}
	}

	/**
	 * Where a {@link OpenApiEndpointParameter} will be used in an {@link OpenApiEndpoint}.
	 */
	@RequiredArgsConstructor
	public enum ParameterLocation {

		PATH("path"),
		QUERY("query");

		@Getter
		private final String location;
	}
}
