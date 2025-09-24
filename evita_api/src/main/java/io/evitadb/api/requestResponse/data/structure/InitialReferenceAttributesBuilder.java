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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.map.LazyHashMap;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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

	public InitialReferenceAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, attributeTypes);
		this.referenceSchema = referenceSchema;
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return () -> "`" + this.entitySchema.getName() + "` reference `" + this.referenceSchema.getName() + "`";
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.referenceSchema.getAttribute(attributeName);
	}

	@Nonnull
	@Override
	public ReferenceAttributes build() {
		return new ReferenceAttributes(
			this.entitySchema,
			this.referenceSchema,
			this.attributeValues.values(),
			this.attributeTypes == null ?
				new LazyHashMap<>(4) :
				this.attributeTypes
		);
	}

	@Nonnull
	@Override
	protected AttributeSchemaContract createImplicitSchema(@Nonnull AttributeValue theAttributeValue) {
		return AttributesBuilder.createImplicitReferenceAttributeSchema(theAttributeValue);
	}
}
