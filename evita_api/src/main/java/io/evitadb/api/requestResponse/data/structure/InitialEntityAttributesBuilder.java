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
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extension of the {@link InitialAttributesBuilder} for {@link EntityAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class InitialEntityAttributesBuilder extends InitialAttributesBuilder<EntityAttributeSchemaContract, InitialEntityAttributesBuilder> {
	@Serial private static final long serialVersionUID = 860482522446542007L;
	@Getter(AccessLevel.PRIVATE) private final String location;

	public InitialEntityAttributesBuilder(
		@Nonnull EntitySchemaContract schema
	) {
		super(schema);
		this.location = "`" + schema.getName() + "`";
	}

	public InitialEntityAttributesBuilder(
		@Nonnull EntitySchemaContract schema,
		boolean suppressVerification
	) {
		super(schema, suppressVerification);
		this.location = "`" + schema.getName() + "`";
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
		this.location = "`" + schema.getName() + "`";
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return this::getLocation;
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.entitySchema.getAttribute(attributeName);
	}

	@Nonnull
	@Override
	public EntityAttributes build() {
		final Map<String, EntityAttributeSchemaContract> newAttributes = this.attributeValues
			.entrySet()
			.stream()
			.filter(entry -> this.entitySchema.getAttribute(entry.getKey().attributeName()).isEmpty())
			.map(Entry::getValue)
			.map(AttributesBuilder::createImplicitEntityAttributeSchema)
			.collect(
				Collectors.toUnmodifiableMap(
					AttributeSchemaContract::getName,
					Function.identity(),
					(attributeType, attributeType2) -> {
						Assert.isTrue(
							Objects.equals(attributeType, attributeType2),
							"Ambiguous situation - there are two attributes with the same name and different definition:\n" +
								attributeType + "\n" +
								attributeType2
						);
						return attributeType;
					}
				)
			);
		return new EntityAttributes(
			this.entitySchema,
			this.attributeValues.values(),
			newAttributes.isEmpty() ?
				this.entitySchema.getAttributes() :
				Stream.concat(
						this.entitySchema.getAttributes().entrySet().stream(),
						newAttributes.entrySet().stream()
					)
					.collect(
						Collectors.toUnmodifiableMap(
							Entry::getKey,
							Entry::getValue
						)
					)
		);
	}

}
