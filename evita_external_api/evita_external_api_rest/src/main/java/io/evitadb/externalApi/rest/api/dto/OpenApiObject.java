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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
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
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiObject implements OpenApiComplexType {

	@Nonnull
	private final String name;
	@Nullable
	private final String description;
	@Nullable
	private final String deprecationNotice;
	@Nonnull
	private final List<OpenApiProperty> properties;

	/**
	 * How referenced objects will be combined into this new one
	 */
	@Nonnull
	private final OpenApiObjectUnionType unionType;
	/**
	 * Name of discriminator of union objects.
	 */
	@Nullable
	private final String unionDiscriminator;
	/**
	 * Objects that are combined to form new (this) object.
	 */
	@Nonnull
	private final List<OpenApiTypeReference> unionObjects;

	/**
	 * Create new empty builder of object.
	 */
	@Nonnull
	public static Builder newObject() {
		return new Builder();
	}

	/**
	 * Create new builder of existing object.
	 */
	@Nonnull
	public static Builder newObject(@Nonnull OpenApiObject existingObject) {
		return new Builder(existingObject);
	}

	@Nonnull
	@Override
	public Schema<Object> toSchema() {
		final Schema<Object> schema;

		if (!unionObjects.isEmpty()) {
			schema = new Schema<>();
			switch (unionType) {
				case ONE_OF -> unionObjects.forEach(it -> schema.addOneOfItem(it.toSchema()));
				case ANY_OF -> unionObjects.forEach(it -> schema.addAnyOfItem(it.toSchema()));
				case ALL_OF -> unionObjects.forEach(it -> schema.addAllOfItem(it.toSchema()));
			}
			schema.discriminator(new Discriminator().propertyName(unionDiscriminator));
		} else {
			schema = new ObjectSchema();
			schema.setProperties(new LinkedHashMap<>(this.properties.size()));
		}

		schema.name(this.name);
		schema.description(this.description);
		if (this.deprecationNotice != null) {
			schema.deprecated(true); // openapi doesn't support false here
		}

		this.properties.forEach(prop -> {
			schema.addProperty(prop.getName(), prop.getSchema());
			if (prop.isNonNull()) {
				schema.addRequiredItem(prop.getName());
			}
		});

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

		@Nonnull
		private OpenApiObjectUnionType unionType = OpenApiObjectUnionType.ONE_OF;
		@Nullable
		private String unionDiscriminator;
		@Nonnull
		private final List<OpenApiTypeReference> unionObjects;

		private Builder() {
			this.properties = createHashMap(20);
			this.unionObjects = new LinkedList<>();
		}

		private Builder(@Nonnull OpenApiObject existingObject) {
			this(
				existingObject.name,
				existingObject.description,
				existingObject.deprecationNotice,
				new HashMap<>(existingObject.properties.stream().collect(Collectors.toMap(OpenApiProperty::getName, Function.identity()))),
				existingObject.unionType,
				existingObject.unionDiscriminator,
				existingObject.unionObjects
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
		public Builder property(@Nonnull OpenApiProperty property) {
			properties.put(property.getName(), property);
			return this;
		}

		@Nonnull
		public Builder property(@Nonnull OpenApiProperty.Builder propertyBuilder) {
			return property(propertyBuilder.build());
		}

		@Nonnull
		public Builder property(@Nonnull UnaryOperator<OpenApiProperty.Builder> propertyBuilderFunction) {
			OpenApiProperty.Builder propertyBuilder = OpenApiProperty.newProperty();
			propertyBuilder = propertyBuilderFunction.apply(propertyBuilder);
			return property(propertyBuilder.build());
		}

		@Nonnull
		public Builder unionType(@Nonnull OpenApiObjectUnionType unionType) {
			this.unionType = unionType;
			return this;
		}

		@Nonnull
		public Builder unionDiscriminator(@Nonnull String unionDiscriminator) {
			this.unionDiscriminator = unionDiscriminator;
			return this;
		}

		@Nonnull
		public Builder unionObject(@Nonnull OpenApiTypeReference unionObject) {
			this.unionObjects.add(unionObject);
			return this;
		}

		public boolean hasProperty(@Nonnull String name) {
			return this.properties.containsKey(name);
		}

		@Nonnull
		public OpenApiObject build() {
			Assert.isPremiseValid(
				name != null && !name.isEmpty(),
				() -> new OpenApiSchemaBuildingError("Missing object name.")
			);
			if (!unionObjects.isEmpty()) {
				Assert.isPremiseValid(
					unionDiscriminator != null && !unionDiscriminator.isEmpty(),
					() -> new OpenApiSchemaBuildingError("Object `" + name + "` is supposed to be union but no discriminator was specified.")
				);
			}
			return new OpenApiObject(name, description, deprecationNotice, new ArrayList<>(properties.values()), unionType, unionDiscriminator, unionObjects);
		}
	}
}
