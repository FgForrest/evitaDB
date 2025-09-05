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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeSchemaEvolvingMutation;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.ExistingReferenceAttributesBuilder;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.function.Consumer;

/**
 * This mutation allows to create / update / remove {@link AttributeValue} of the {@link Reference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true, exclude = "comparableKey")
public class ReferenceAttributeMutation extends ReferenceMutation<ReferenceKeyWithAttributeKey> implements SchemaEvolvingLocalMutation<ReferenceContract, ReferenceKeyWithAttributeKey> {
	@Serial private static final long serialVersionUID = -5135310891814031602L;
	/**
	 * Contains wrapped attribute mutation that affects the attribute of the reference.
	 */
	@Nonnull
	@Getter private final AttributeMutation attributeMutation;
	/**
	 * Identification of the attribute that the mutation affects.
	 */
	@Nonnull
	@Getter private final AttributeKey attributeKey;
	/**
	 * Full identification of the mutation that is used for sorting mutations.
	 */
	@Nonnull
	private final ReferenceKeyWithAttributeKey comparableKey;

	public ReferenceAttributeMutation(@Nonnull ReferenceKey referenceKey, @Nonnull AttributeMutation attributeMutation) {
		super(referenceKey);
		this.attributeMutation = attributeMutation;
		this.attributeKey = attributeMutation.getAttributeKey();
		this.comparableKey = new ReferenceKeyWithAttributeKey(referenceKey, this.attributeKey);
	}

	public ReferenceAttributeMutation(@Nonnull String referenceName, int primaryKey, @Nonnull AttributeMutation attributeMutation) {
		this(new ReferenceKey(referenceName, primaryKey), attributeMutation);
	}

	private ReferenceAttributeMutation(@Nonnull ReferenceKey referenceKey, @Nonnull AttributeMutation attributeMutation, long decisiveTimestamp) {
		super(referenceKey, decisiveTimestamp);
		this.attributeMutation = attributeMutation;
		this.attributeKey = attributeMutation.getAttributeKey();
		this.comparableKey = new ReferenceKeyWithAttributeKey(referenceKey, this.attributeKey);
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return new ReferenceAttributeSkipToken(this.referenceKey.referenceName(), this.attributeKey);
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (this.attributeMutation instanceof final AttributeSchemaEvolvingMutation schemaValidatingMutation) {
			final ReferenceSchemaContract referenceSchema = entitySchemaBuilder.getReference(this.referenceKey.referenceName())
				.orElseThrow(() -> new GenericEvitaInternalError("Reference to type `" + this.referenceKey.referenceName() + "` was not found!"));

			this.attributeMutation.verifyOrEvolveSchema(
				catalogSchema,
				entitySchemaBuilder,
				referenceSchema.getAttribute(this.attributeKey.attributeName()).orElse(null),
				schemaValidatingMutation.getAttributeValue(),
				(csb, esb) -> {
					if (this.attributeKey.localized()) {
						esb.withLocale(this.attributeKey.locale());
					}
					final Consumer<ReferenceSchemaBuilder> referenceSchemaUpdater = whichIs -> {
						final boolean attributeExists = whichIs.getAttribute(this.attributeKey.attributeName()).isPresent();
						whichIs.withAttribute(
							this.attributeKey.attributeName(),
							schemaValidatingMutation.getAttributeValue().getClass(),
							thatIs -> {
								thatIs.localized(this.attributeKey::localized);
								if (!attributeExists) {
									thatIs.nullable();
								}
							}
						);
					};
					if (referenceSchema.isReferencedEntityTypeManaged()) {
						esb.withReferenceToEntity(
							this.referenceKey.referenceName(),
							referenceSchema.getReferencedEntityType(),
							referenceSchema.getCardinality(),
							referenceSchemaUpdater
						);
					} else {
						esb.withReferenceTo(
							this.referenceKey.referenceName(),
							referenceSchema.getReferencedEntityType(),
							referenceSchema.getCardinality(),
							referenceSchemaUpdater
						);
					}
				}
			);
		}
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceContract existingValue) {
		return mutateLocal(
			entitySchema,
			existingValue,
			Map.of()
		);
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceContract existingValue,
		@Nonnull Map<String, AttributeSchemaContract> locallyAddedAttributeTypes
	) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot update attributes on reference " + this.referenceKey + " - reference doesn't exist!")
		);
		// this is kind of expensive, let's hope references will not have many attributes on them that frequently change
		final ExistingReferenceAttributesBuilder attributeBuilder = new ExistingReferenceAttributesBuilder(
			entitySchema,
			existingValue.getReferenceSchemaOrThrow(),
			existingValue.getAttributeValues(),
			locallyAddedAttributeTypes
		);
		final Attributes<AttributeSchemaContract> newAttributes = attributeBuilder
			.mutateAttribute(this.attributeMutation)
			.build();

		if (attributeBuilder.differs(newAttributes)) {
			return new Reference(
				existingValue.getReferenceSchemaOrThrow(),
				existingValue.version() + 1,
				existingValue.getReferenceKey(),
				existingValue.getGroup().orElse(null),
				newAttributes,
				false
			);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		// we need that attribute removals are placed before insert/remove reference itself
		final long priority = this.attributeMutation.getPriority();
		if (priority >= PRIORITY_REMOVAL) {
			return priority + 1;
		} else {
			return priority - 1;
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new ReferenceAttributeMutation(this.referenceKey, this.attributeMutation, newDecisiveTimestamp);
	}

	@Nonnull
	@Override
	public ReferenceMutation<ReferenceKeyWithAttributeKey> withInternalPrimaryKey(int internalPrimaryKey) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(this.referenceKey.referenceName(), this.referenceKey.primaryKey(), internalPrimaryKey),
			this.attributeMutation, this.decisiveTimestamp
		);
	}

	@Override
	public String toString() {
		return "reference `" + this.referenceKey + "` attribute mutation: " + this.attributeMutation;
	}

	@Nonnull
	@Override
	public ReferenceKeyWithAttributeKey getComparableKey() {
		return this.comparableKey;
	}

}
