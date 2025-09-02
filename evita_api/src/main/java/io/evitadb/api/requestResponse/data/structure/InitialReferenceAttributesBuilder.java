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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extension of the {@link InitialAttributesBuilder} for {@link ReferenceAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class InitialReferenceAttributesBuilder
	extends InitialAttributesBuilder<AttributeSchemaContract, InitialReferenceAttributesBuilder> {
	@Serial private static final long serialVersionUID = -5627484741551461956L;
	/**
	 * Definition of the reference schema.
	 */
	private final ReferenceSchemaContract referenceSchema;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 * Map is lazily initialized when the first implicit attribute schema is created.
	 */
	@Nullable private Map<String, AttributeSchemaContract> attributeTypes;
	/**
	 * Location of the reference.
	 */
	@Getter(AccessLevel.PRIVATE) private final String location;

	public InitialReferenceAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		super(entitySchema);
		this.referenceSchema = referenceSchema;
		this.attributeTypes = null;
		this.location = "`" + entitySchema.getName() + "` reference `" + referenceSchema.getName() + "`";
	}

	public InitialReferenceAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean suppressVerification
	) {
		super(entitySchema, suppressVerification);
		this.referenceSchema = referenceSchema;
		this.attributeTypes = null;
		this.location = "`" + entitySchema.getName() + "` reference `" + referenceSchema.getName() + "`";
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return this::getLocation;
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.referenceSchema.getAttribute(attributeName);
	}

	@Override
	@Nonnull
	public <U extends Serializable> InitialReferenceAttributesBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable U attributeValue
	) {
		createImplicitSchemaIfMissing(attributeName, attributeValue, null);
		return super.setAttribute(attributeName, attributeValue);
	}

	@Override
	@Nonnull
	public <U extends Serializable> InitialReferenceAttributesBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable U[] attributeValue
	) {
		createImplicitSchemaIfMissing(attributeName, attributeValue, null);
		return super.setAttribute(attributeName, attributeValue);
	}

	@Override
	@Nonnull
	public <U extends Serializable> InitialReferenceAttributesBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable U attributeValue
	) {
		createImplicitSchemaIfMissing(attributeName, attributeValue, locale);
		return super.setAttribute(attributeName, locale, attributeValue);
	}

	@Override
	@Nonnull
	public <U extends Serializable> InitialReferenceAttributesBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable U[] attributeValue
	) {
		createImplicitSchemaIfMissing(attributeName, attributeValue, locale);
		return super.setAttribute(attributeName, locale, attributeValue);
	}

	@Nonnull
	@Override
	public ReferenceAttributes build() {
		return new ReferenceAttributes(
			this.entitySchema,
			this.referenceSchema,
			this.attributeValues.values(),
			this.attributeTypes == null ?
				this.referenceSchema.getAttributes() :
				Stream.concat(
					      this.referenceSchema.getAttributes().entrySet().stream(),
					      this.attributeTypes.entrySet().stream()
				      )
				      .collect(
					      Collectors.toUnmodifiableMap(
						      Entry::getKey,
						      Entry::getValue
					      )
				      )
		);
	}

	/**
	 * Creates an implicit attribute schema if it is missing for the specified attribute name and value.
	 * If an attribute schema already exists, verifies that the provided value matches the expected type.
	 * Otherwise, adds a new attribute schema if the entity schema allows adding attributes dynamically.
	 *
	 * @param <U>          The type of the attribute value, which must extend {@link Serializable}.
	 * @param attributeName The name of the attribute for which the schema needs to be created or verified. Must not be null.
	 * @param attributeValue The value of the attribute to be used for schema creation or type verification. Nullable.
	 */
	private <U extends Serializable> void createImplicitSchemaIfMissing(
		@Nonnull String attributeName,
		@Nullable U attributeValue,
		@Nullable Locale locale
	) {
		if (attributeValue != null) {
			final AttributeSchemaContract attributeSchema = this.referenceSchema.getAttribute(attributeName)
				.orElseGet(() -> this.attributeTypes == null ? null : this.attributeTypes.get(attributeName));

			if (attributeSchema != null) {
				verifyAttributeIsInSchemaAndTypeMatch(
					this.entitySchema, attributeName, attributeValue.getClass(), locale, attributeSchema,
					this::getLocation
				);
			} else {
				Assert.isTrue(
					this.entitySchema.allows(EvolutionMode.ADDING_ATTRIBUTES),
					() -> new InvalidMutationException(
						"Cannot add new attribute `" + attributeName + "` to the " + getLocation() +
							" because entity schema doesn't allow adding new attributes!"
					)
				);
				if (this.attributeTypes == null) {
					this.attributeTypes = CollectionUtils.createHashMap(8);
				}
				this.attributeTypes.put(
					attributeName,
					AttributesBuilder.createImplicitReferenceAttributeSchema(
						new AttributeValue(new AttributeKey(attributeName), attributeValue)
					)
				);
			}
		}
	}

}
