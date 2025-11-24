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

package io.evitadb.api.requestResponse.data.mutation.attribute;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

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

	protected AttributeSchemaEvolvingMutation(@Nonnull AttributeKey attributeKey, long decisiveTimestamp) {
		super(attributeKey, decisiveTimestamp);
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return this.attributeKey;
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		verifyOrEvolveSchema(
			catalogSchema,
			entitySchemaBuilder,
			entitySchemaBuilder.getAttribute(this.attributeKey.attributeName()).orElse(null),
			getAttributeValue(),
			(csb, esb) -> {
				if (this.attributeKey.localized()) {
					esb.withLocale(this.attributeKey.locale());
				}
				if (csb.getAttribute(this.attributeKey.attributeName()).isEmpty()) {
					final Class<? extends Serializable> attributeType = getAttributeValue().getClass();
					if (esb.getAttribute(this.attributeKey.attributeName()).isEmpty()) {
						esb
							.withAttribute(
								this.attributeKey.attributeName(),
								attributeType,
								whichIs -> {
									whichIs
										.localized(this.attributeKey::localized)
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
							this.attributeKey.attributeName()
						);
				}
			}
		);
	}

	public void verifyOrEvolveSchema(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaBuilder entitySchemaBuilder,
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull Serializable attributeValue,
		@Nonnull BiConsumer<CatalogSchemaContract, EntitySchemaBuilder> schemaEvolutionApplicator
	) throws InvalidMutationException {
		// when attribute definition is known execute first encounter formal verification
		if (attributeSchema != null) {
			Assert.isTrue(
				(attributeSchema.getType().isPrimitive() ?
					EvitaDataTypes.getWrappingPrimitiveClass(attributeSchema.getType()) : attributeSchema.getType())
					.isInstance(attributeValue),
				() -> new InvalidMutationException(
					"Invalid type: `" + attributeValue.getClass() + "`! " +
						"Attribute `" + this.attributeKey.attributeName() + "` in schema `" + entitySchemaBuilder.getName() + "` was already stored as type " + attributeSchema.getType() + ". " +
						"All values of attribute `" + this.attributeKey.attributeName() + "` must respect this data type!"
				)
			);
			if (attributeSchema.isLocalized()) {
				Assert.isTrue(
					this.attributeKey.localized(),
					() -> new InvalidMutationException(
						"Attribute `" + this.attributeKey.attributeName() + "` in schema `" + entitySchemaBuilder.getName() + "` was already stored as localized value. " +
							"All values of attribute `" + this.attributeKey.attributeName() + "` must be localized now " +
							"- use different attribute name for locale independent variant of attribute!"
					)
				);
				final Locale locale = this.attributeKey.locale();
				if (!entitySchemaBuilder.getLocales().contains(locale)) {
					if (entitySchemaBuilder.allows(EvolutionMode.ADDING_LOCALES)) {
						// evolve schema automatically
						schemaEvolutionApplicator.accept(catalogSchema, entitySchemaBuilder);
					} else {
						throw new InvalidMutationException(
							"Attribute `" + this.attributeKey.attributeName() + "` in schema `" + entitySchemaBuilder.getName() + "` is localized to `" + locale + "` which is not allowed by the schema" +
								" (allowed are only: " + entitySchemaBuilder.getLocales().stream().map(Locale::toString).collect(
								Collectors.joining(", ")) + "). " +
								"You must first alter entity schema to be able to add this localized attribute to the entity!"
						);
					}
				}
			} else {
				Assert.isTrue(
					!this.attributeKey.localized(),
					() -> new InvalidMutationException(
						"Attribute `" + this.attributeKey.attributeName() + "` in schema `" + entitySchemaBuilder.getName() + "` was not stored as localized value. " +
							"No values of attribute `" + this.attributeKey.attributeName() + "` can be localized now " +
							"- use different attribute name for localized variant of attribute!"
					)
				);
			}
			// else check whether adding attributes on the fly is allowed
		} else if (entitySchemaBuilder.allows(EvolutionMode.ADDING_ATTRIBUTES)) {
			// evolve schema automatically
			schemaEvolutionApplicator.accept(catalogSchema, entitySchemaBuilder);
		} else {
			throw new InvalidMutationException(
				"Unknown attribute `" + this.attributeKey.attributeName() + "` in schema `" + entitySchemaBuilder.getName() + "``! " +
					"You must first alter entity schema to be able to add this attribute to the entity!"
			);
		}
	}

	public abstract Serializable getAttributeValue();

}
