/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extension of the {@link InitialAttributesBuilder} for {@link ReferenceAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class InitialReferenceAttributesBuilder extends InitialAttributesBuilder<AttributeSchemaContract, InitialReferenceAttributesBuilder> {
	@Serial private static final long serialVersionUID = -5627484741551461956L;
	/**
	 * Definition of the reference schema.
	 */
	private final ReferenceSchemaContract referenceSchema;
	@Getter(AccessLevel.PRIVATE) private final String location;

	public InitialReferenceAttributesBuilder(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema) {
		super(entitySchema);
		this.referenceSchema = referenceSchema;
		this.location = "`" + entitySchema.getName() + "` reference `" + referenceSchema.getName() + "`";
	}

	public InitialReferenceAttributesBuilder(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, boolean suppressVerification) {
		super(entitySchema, suppressVerification);
		this.referenceSchema = referenceSchema;
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

	@Nonnull
	@Override
	public ReferenceAttributes build() {
		final Map<String, AttributeSchemaContract> newAttributes = this.attributeValues
			.entrySet()
			.stream()
			.filter(entry -> this.referenceSchema.getAttribute(entry.getKey().attributeName()).isEmpty())
			.map(Entry::getValue)
			.map(AttributesBuilder::createImplicitReferenceAttributeSchema)
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
		return new ReferenceAttributes(
			this.entitySchema,
			this.referenceSchema,
			this.attributeValues.values(),
			newAttributes.isEmpty() ?
				this.referenceSchema.getAttributes() :
				Stream.concat(
						this.referenceSchema.getAttributes().entrySet().stream(),
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
