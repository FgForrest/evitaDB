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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Extension of the {@link InitialAttributesBuilder} for {@link EntityAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class InitialEntityAttributesBuilder extends InitialAttributesBuilder<EntityAttributeSchemaContract, InitialEntityAttributesBuilder> {
	@Serial private static final long serialVersionUID = 860482522446542007L;

	public InitialEntityAttributesBuilder(
		@Nonnull EntitySchemaContract schema
	) {
		super(schema);
	}

	public InitialEntityAttributesBuilder(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Collection<AttributeValue> attributeValues
	) {
		super(schema);
		for (AttributeValue attributeValue : attributeValues) {
			final AttributeKey attributeKey = attributeValue.key();
			if (attributeKey.localized()) {
				this.setAttribute(
					attributeKey.attributeName(),
					attributeKey.localeOrThrowException(),
					attributeValue.value()
				);
			} else {
				this.setAttribute(
					attributeKey.attributeName(),
					attributeValue.value()
				);
			}
		}
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return () -> "`" + this.entitySchema.getName() + "`";
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.entitySchema.getAttribute(attributeName);
	}

	@Nonnull
	@Override
	public EntityAttributes build() {
		return new EntityAttributes(
			this.entitySchema,
			this.attributeValues.values(),
			this.attributeTypes == null ?
				new LazyHashMap<>(4) :
				this.attributeTypes
		);
	}

	@Nonnull
	@Override
	protected EntityAttributeSchemaContract createImplicitSchema(@Nonnull AttributeValue theAttributeValue) {
		return AttributesBuilder.createImplicitEntityAttributeSchema(theAttributeValue);
	}
}
