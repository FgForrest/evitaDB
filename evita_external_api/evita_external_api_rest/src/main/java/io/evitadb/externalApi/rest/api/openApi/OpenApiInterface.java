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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.Builder;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Discriminator;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Represents object interface of OpenAPI schema and must be globally registered in OpenAPI so that there are no
 * duplicates and a client can generate prettier client libraries.
 *
 * It translates into generic {@link Schema} with mappings to implementing types.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiInterface implements OpenApiComplexType {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nonnull
	private final List<OpenApiProperty> properties;
	/**
	 * Discriminator of implementing types.
	 */
	@Nonnull
	private final PropertyDescriptor discriminator;
	/**
	 * Types that implements this interface.
	 */
	@Nonnull
	private final List<OpenApiTypeReference> implementingTypes;

	/**
	 * Creates new empty builder of interface.
	 */
	@Nonnull
	public static Builder newInterface() {
		return new Builder();
	}

	/**
	 * Creates new empty builder of existing interface.
	 */
	@Nonnull
	public static Builder newInterface(@Nonnull OpenApiInterface existingInterface) {
		return new Builder(existingInterface);
	}

	@Nonnull
	@Override
	public Schema<Object> toSchema() {
		final Schema<Object> schema = new ObjectSchema();

		schema.name(this.name);
		schema.description(this.description);
		if (this.deprecationNotice != null) {
			schema.deprecated(true); // openapi doesn't support false here
		}

		schema.setProperties(new LinkedHashMap<>(this.properties.size()));
		this.properties.forEach(prop -> {
			schema.addProperty(prop.getName(), prop.getSchema());
			if (prop.isNonNull()) {
				schema.addRequiredItem(prop.getName());
			}
		});

		final Discriminator schemaDiscriminator = new Discriminator()
			.propertyName(this.discriminator.name())
			.mapping(this.implementingTypes
                .stream()
		        .collect(Collectors.toMap(OpenApiTypeReference::getObjectName, it -> "#/components/schemas/" + it.getObjectName())));
		schema.discriminator(schemaDiscriminator);

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
		private final Map<String, OpenApiProperty> properties;
		@Nullable
		private PropertyDescriptor discriminator;
		@Nonnull
		private final List<OpenApiTypeReference> implementingTypes;

		private Builder() {
			this.properties = createHashMap(20);
			this.implementingTypes = new LinkedList<>();
		}

		private Builder(@Nonnull OpenApiInterface existingInterface) {
			this(
				existingInterface.name,
				existingInterface.description,
				existingInterface.deprecationNotice,
				new HashMap<>(existingInterface.properties.stream().collect(Collectors.toMap(OpenApiProperty::getName, Function.identity()))),
				existingInterface.discriminator,
				existingInterface.implementingTypes
			);
		}

		/**
		 * Sets name of the interface.
		 */
		@Nonnull
		public Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets description of the interface.
		 */
		@Nonnull
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets deprecation notice of the interface to indicate that the interface is deprecated. If null, interface is not set
		 * as deprecated.
		 */
		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		/**
		 * Sets name of interface discriminator for implementing types.
		 */
		@Nonnull
		public Builder discriminator(@Nonnull PropertyDescriptor interfaceDiscriminator) {
			this.discriminator = interfaceDiscriminator;
			return this;
		}

		/**
		 * Adds implementing type. Make sure to set correct {@link #discriminator(PropertyDescriptor)}.
		 */
		@Nonnull
		public Builder implementingType(@Nonnull OpenApiTypeReference implementingType) {
			this.implementingTypes.add(implementingType);
			return this;
		}

		/**
		 * Adds implementing types. Make sure to set correct {@link #discriminator(PropertyDescriptor)}.
		 */
		@Nonnull
		public Builder implementingTypes(@Nonnull OpenApiTypeReference... implementingTypes) {
			this.implementingTypes.addAll(Arrays.asList(implementingTypes));
			return this;
		}

		/**
		 * Adds property to the object.
		 */
		@Nonnull
		public Builder property(@Nonnull OpenApiProperty property) {
			this.properties.put(property.getName(), property);
			return this;
		}

		/**
		 * Adds property to the object.
		 */
		@Nonnull
		public Builder property(@Nonnull OpenApiProperty.Builder propertyBuilder) {
			return property(propertyBuilder.build());
		}

		/**
		 * Adds property to the object.
		 */
		@Nonnull
		public Builder property(@Nonnull UnaryOperator<OpenApiProperty.Builder> propertyBuilderFunction) {
			OpenApiProperty.Builder propertyBuilder = OpenApiProperty.newProperty();
			propertyBuilder = propertyBuilderFunction.apply(propertyBuilder);
			return property(propertyBuilder.build());
		}

		@Nonnull
		public OpenApiInterface build() {
			Assert.isPremiseValid(
				this.name != null && !this.name.isEmpty(),
				() -> new OpenApiBuildingError("Missing interface name.")
			);
			Assert.isPremiseValid(
				this.discriminator != null,
				() -> new OpenApiBuildingError("Missing interface discriminator.")
			);
			Assert.isPremiseValid(
				!this.implementingTypes.isEmpty(),
				() -> new OpenApiBuildingError("Missing implementing types")
			);
			return new OpenApiInterface(this.name, this.description, this.deprecationNotice, new ArrayList<>(this.properties.values()), this.discriminator, this.implementingTypes);
		}
	}
}
