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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.map.LazyHashMap;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Extension of {@link Attributes} for entity attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityAttributes extends Attributes<EntityAttributeSchemaContract> {
	@Serial private static final long serialVersionUID = -8659752208984874674L;

	public EntityAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Map<AttributeKey, AttributeValue> attributeValues,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, attributeValues, attributeTypes);
	}

	public EntityAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, attributeValues, attributeTypes);
	}

	public EntityAttributes(
		@Nonnull EntitySchemaContract entitySchema
	) {
		super(
			entitySchema,
			Collections.emptyList(),
			new LazyHashMap<>(4)
		);
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.entitySchema.getAttribute(attributeName)
	                            .or(() -> ofNullable(this.attributeTypes.get(attributeName)));
	}

	@Nonnull
	@Override
	protected AttributeNotFoundException createAttributeNotFoundException(@Nonnull String attributeName) {
		return new AttributeNotFoundException(attributeName, this.entitySchema);
	}

}
