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

import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.map.LazyHashMapDelegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Extension of the {@link ExistingAttributesBuilder} for {@link ReferenceAttributes}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ExistingReferenceAttributesBuilder extends ExistingAttributesBuilder<AttributeSchemaContract, ExistingReferenceAttributesBuilder> {
	@Serial private static final long serialVersionUID = 7456072599308632861L;
	/**
	 * Definition of the reference schema.
	 */
	private final ReferenceSchemaContract referenceSchema;

	public ExistingReferenceAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, new ReferenceAttributes(entitySchema, referenceSchema, attributes, attributeTypes), attributeTypes);
		this.referenceSchema = referenceSchema;
	}

	public ExistingReferenceAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes,
		@Nonnull Collection<AttributeMutation> attributeMutations
	) {
		super(entitySchema, new ReferenceAttributes(entitySchema, referenceSchema, attributes, attributeTypes), attributeTypes, attributeMutations);
		this.referenceSchema = referenceSchema;
	}

	@Nonnull
	@Override
	public Supplier<String> getLocationResolver() {
		return () -> "`" + this.entitySchema.getName() + "` reference `" + this.referenceSchema.getName() + "`";
	}

	@Nonnull
	@Override
	public Attributes<AttributeSchemaContract> build() {
		if (isThereAnyChangeInMutations()) {
			final Collection<AttributeValue> newAttributeValues = getAttributeValuesWithoutPredicate().collect(Collectors.toList());
			return new ReferenceAttributes(
				this.baseAttributes.entitySchema,
				this.referenceSchema,
				newAttributeValues,
				this.attributeTypes == null || this.attributeTypes.isEmpty() ?
					new LazyHashMapDelegate<>(4) :
					new HashMap<>(this.attributeTypes)
			);
		} else {
			return this.baseAttributes;
		}
	}

	@Nonnull
	@Override
	protected AttributeSchemaContract createImplicitSchema(@Nonnull AttributeValue theAttributeValue) {
		return AttributesBuilder.createImplicitReferenceAttributeSchema(theAttributeValue);
	}
}
