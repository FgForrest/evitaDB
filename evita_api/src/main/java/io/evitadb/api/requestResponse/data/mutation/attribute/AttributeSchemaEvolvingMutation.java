/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract parent for all attribute mutations that require schema validation / evolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public abstract class AttributeSchemaEvolvingMutation extends AttributeMutation implements SchemaEvolvingLocalMutation<AttributeValue, AttributeKey> {
	@Serial private static final long serialVersionUID = 2509373417337487380L;

	protected AttributeSchemaEvolvingMutation(@Nonnull AttributeKey attributeKey) {
		super(attributeKey);
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return attributeKey;
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		verifyOrEvolveSchema(
			catalogSchema,
			entitySchemaBuilder,
			entitySchemaBuilder.getAttribute(attributeKey.getAttributeName()).orElse(null),
			getAttributeValue(),
			(csb, esb) -> {
				if (attributeKey.isLocalized()) {
					esb.withLocale(attributeKey.getLocale());
				}
				if (csb.getAttribute(attributeKey.getAttributeName()).isEmpty()) {
					final Class<? extends Serializable> attributeType = getAttributeValue().getClass();
					if (esb.getAttribute(attributeKey.getAttributeName()).isEmpty()) {
						esb
							.withAttribute(
								attributeKey.getAttributeName(),
								attributeType,
								whichIs -> {
									whichIs
										.localized(attributeKey::isLocalized)
										.filterable()
										.nullable();
									if (!attributeType.isArray()) {
										whichIs.sortable();
									}
								}
							);
					}
				} else {
					esb
						.withGlobalAttribute(
							attributeKey.getAttributeName()
						);
				}
			}
		);
	}

	public abstract Serializable getAttributeValue();

}
