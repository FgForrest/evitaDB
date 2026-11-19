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

package io.evitadb.test.builder;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This implementation is used only when existing Evita entity from different database is required to be inserted into
 * another Evita database.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CopyExistingEntityBuilder extends InitialEntityBuilder {
	@Serial private static final long serialVersionUID = -4012506871491348685L;

	/**
	 * Copies the entity under its own schema, with its own associated data, as the given primary key. When the key
	 * differs from the entity's own, every unique `String` attribute gets the key appended, so the copy can sit next to
	 * the original in one catalog without breaking the uniqueness.
	 *
	 * @param externalEntity       the entity being copied
	 * @param overriddenPrimaryKey the primary key of the copy, `null` for a copy without one, which a target that
	 *                             generates primary keys assigns
	 * @throws EvitaInvalidUsageException when the key changes and the entity has a unique attribute that is not a
	 *                                    `String`, which cannot be made unique this way
	 */
	public CopyExistingEntityBuilder(@Nonnull EntityContract externalEntity, @Nullable Integer overriddenPrimaryKey) {
		this(
			externalEntity, overriddenPrimaryKey, externalEntity.getSchema(), externalEntity.getAssociatedDataValues()
		);
	}

	/**
	 * Copies the entity as it is - its own schema, primary key, attributes and associated data.
	 *
	 * @param externalEntity the entity being copied
	 */
	public CopyExistingEntityBuilder(@Nonnull EntityContract externalEntity) {
		this(externalEntity, externalEntity.getPrimaryKey());
	}

	/**
	 * Copies the entity under the target's schema, with its associated data replaced - for a target that declares
	 * some associated data differently from the source, such as with a locale the source did not have. The values are
	 * validated against `targetSchema`, so they must already have the shape it declares.
	 *
	 * @param externalEntity       the entity being copied
	 * @param targetSchema         schema of the entity type in the target catalog
	 * @param associatedDataValues the associated data the copy carries instead of the entity's own
	 */
	public CopyExistingEntityBuilder(
		@Nonnull EntityContract externalEntity,
		@Nonnull EntitySchemaContract targetSchema,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues
	) {
		this(externalEntity, externalEntity.getPrimaryKey(), targetSchema, associatedDataValues);
	}

	/**
	 * Copies the entity under the given schema and primary key, with the given associated data.
	 *
	 * @param externalEntity       the entity being copied
	 * @param overriddenPrimaryKey the primary key of the copy
	 * @param schema               the schema the copy is built and validated against
	 * @param associatedDataValues the associated data the copy carries
	 */
	private CopyExistingEntityBuilder(
		@Nonnull EntityContract externalEntity,
		@Nullable Integer overriddenPrimaryKey,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues
	) {
		super(
			schema,
			overriddenPrimaryKey,
			externalEntity.getScope(),
			externalEntity
				.getAttributeValues()
				.stream()
				.map(it -> {
					final String attributeName = it.key().attributeName();
					final AttributeSchemaContract attributeSchema = externalEntity.getSchema().getAttribute(attributeName)
						.orElseThrow(() -> new EvitaInvalidUsageException("Attribute schema with name `" + attributeName + "` not found!"));
					// this means that the entity might be inserted into the same Evita instance twice
					final Boolean pkIsChanged = ofNullable(overriddenPrimaryKey)
						.map(pk -> !Objects.equals(externalEntity.getPrimaryKey(), pk))
						.orElse(false);
					if (attributeSchema.isUniqueInAnyScope() && pkIsChanged) {
						Assert.isTrue(
							String.class.isAssignableFrom(attributeSchema.getType()),
							"Currently, only String unique attributes can be altered!"
						);
						// and we need to add suffix that makes this value unique
						final String value =  it.value() + "_" + overriddenPrimaryKey;
						return new AttributeValue(it.key(), value);
					} else {
						return it;
					}
				})
				.collect(Collectors.toList()),
			associatedDataValues,
			externalEntity.getReferences(),
			externalEntity.getPriceInnerRecordHandling(),
			externalEntity.getSchema().isWithPrice() ?
				externalEntity.getPrices() : List.of()
		);
	}

}
