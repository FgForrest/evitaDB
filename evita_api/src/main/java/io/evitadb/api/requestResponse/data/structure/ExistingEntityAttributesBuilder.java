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

import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.map.LazyHashMap;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Extension of the {@link ExistingAttributesBuilder} for {@link EntityAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ExistingEntityAttributesBuilder extends ExistingAttributesBuilder<EntityAttributeSchemaContract, ExistingEntityAttributesBuilder> {
	/**
	 * Serialization identifier for {@link java.io.Serializable} support. It ensures compatibility
	 * across versions of this builder when serialized. The value is stable and must be updated only
	 * when the serialized form changes in an incompatible way.
	 */
	@Serial private static final long serialVersionUID = -9128971033768855164L;

	/**
	 * Creates a builder for editing existing entity attributes using the provided entity schema and
	 * the base set of attributes.
	 *
	 * - The builder does not duplicate data until a mutation is applied.
	 * - Resulting attributes instance is created lazily in {@link #build()} when changes exist.
	 *
	 * @param entitySchema the schema of the entity whose attributes are being edited; must not be null
	 * @param baseAttributes the current attributes that serve as the base for modifications; must not be null
	 */
	public ExistingEntityAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<EntityAttributeSchemaContract> baseAttributes
	) {
		super(entitySchema, baseAttributes);
	}

	/**
	 * Creates a builder for editing existing entity attributes with additional filtering and known
	 * attribute schemas.
	 *
	 * - The {@code attributePredicate} allows the caller to pre-filter visible attribute values.
	 * - The {@code attributeTypes} map provides explicit schemas for known attributes; if empty, schemas
	 *   can be created implicitly for unknown attributes when allowed by the entity schema evolution.
	 *
	 * @param entitySchema the schema of the entity whose attributes are being edited; must not be null
	 * @param baseAttributes the current attributes that serve as the base for modifications; must not be null
	 * @param attributePredicate predicate to filter attribute values considered by this builder; must not be null
	 * @param attributeTypes map of attribute name to attribute schema available to the builder; must not be null
	 */
	public ExistingEntityAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<EntityAttributeSchemaContract> baseAttributes,
		@Nonnull SerializablePredicate<AttributeValue> attributePredicate,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, baseAttributes, attributePredicate, attributeTypes);
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return () -> "`" + this.entitySchema.getName() + "`";
	}

	@Nonnull
	@Override
	public EntityAttributes build() {
		if (isThereAnyChangeInMutations()) {
			final Collection<AttributeValue> newAttributeValues = getAttributeValuesWithoutPredicate().collect(Collectors.toList());
			return new EntityAttributes(
				this.baseAttributes.entitySchema,
				newAttributeValues,
				this.attributeTypes == null || this.attributeTypes.isEmpty() ?
					new LazyHashMap<>(4) :
					new HashMap<>(this.attributeTypes)
			);
		} else {
			return (EntityAttributes) this.baseAttributes;
		}
	}

	@Nonnull
	@Override
	protected EntityAttributeSchemaContract createImplicitSchema(@Nonnull AttributeValue theAttributeValue) {
		return AttributesBuilder.createImplicitEntityAttributeSchema(theAttributeValue);
	}
}
