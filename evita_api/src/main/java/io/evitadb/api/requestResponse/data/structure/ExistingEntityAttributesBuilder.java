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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extension of the {@link ExistingAttributesBuilder} for {@link EntityAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ExistingEntityAttributesBuilder extends ExistingAttributesBuilder<EntityAttributeSchemaContract, ExistingEntityAttributesBuilder> {
	@Serial private static final long serialVersionUID = -9128971033768855164L;
	@Getter(AccessLevel.PRIVATE) private final String location;

	public ExistingEntityAttributesBuilder(@Nonnull EntitySchemaContract entitySchema, @Nonnull Attributes<EntityAttributeSchemaContract> baseAttributes) {
		super(entitySchema, baseAttributes);
		this.location = "`" + entitySchema.getName() + "`";
	}

	public ExistingEntityAttributesBuilder(@Nonnull EntitySchemaContract entitySchema, @Nonnull Attributes<EntityAttributeSchemaContract> baseAttributes, @Nonnull SerializablePredicate<AttributeValue> attributePredicate) {
		super(entitySchema, baseAttributes, attributePredicate);
		this.location = "`" + entitySchema.getName() + "`";
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return this::getLocation;
	}

	@Nonnull
	@Override
	public Attributes<EntityAttributeSchemaContract> build() {
		if (isThereAnyChangeInMutations()) {
			final Collection<AttributeValue> newAttributeValues = getAttributeValuesWithoutPredicate().collect(Collectors.toList());
			final Map<String, EntityAttributeSchemaContract> newAttributeTypes = Stream.concat(
					this.baseAttributes.attributeTypes.values().stream(),
					// we don't check baseAttributes.allowUnknownAttributeTypes here because it gets checked on adding a mutation
					newAttributeValues
						.stream()
						// filter out new attributes that has no type yet
						.filter(it -> !this.baseAttributes.attributeTypes.containsKey(it.key().attributeName()))
						// create definition for them on the fly
						.map(AttributesBuilder::createImplicitEntityAttributeSchema)
				)
				.collect(
					Collectors.toUnmodifiableMap(
						AttributeSchemaContract::getName,
						Function.identity(),
						(attributeSchema, attributeSchema2) -> {
							Assert.isTrue(
								attributeSchema.equals(attributeSchema2),
								"Attribute " + attributeSchema.getName() + " has incompatible types in the same entity!"
							);
							return attributeSchema;
						}
					)
				);
			return new EntityAttributes(
				this.baseAttributes.entitySchema,
				newAttributeValues,
				newAttributeTypes
			);
		} else {
			return this.baseAttributes;
		}
	}

	@Nonnull
	@Override
	protected EntityAttributes createAttributesContainer(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributeTypes
	) {
		return new EntityAttributes(entitySchema, attributes, attributeTypes);
	}

}
