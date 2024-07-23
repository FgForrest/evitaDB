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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeSchemaEvolvingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation.ReferenceKeyWithAttributeKey;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.ExistingReferenceAttributesBuilder;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * This mutation allows to create / update / remove {@link AttributeValue} of the {@link Reference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class ReferenceAttributeMutation extends ReferenceMutation<ReferenceKeyWithAttributeKey> implements SchemaEvolvingLocalMutation<ReferenceContract, ReferenceKeyWithAttributeKey> {
	@Serial private static final long serialVersionUID = -1403540167469945561L;
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
		this.comparableKey = new ReferenceKeyWithAttributeKey(referenceKey, attributeKey);
	}

	public ReferenceAttributeMutation(@Nonnull String referenceName, int primaryKey, @Nonnull AttributeMutation attributeMutation) {
		this(new ReferenceKey(referenceName, primaryKey), attributeMutation);
	}

	@Nonnull
	@Override
	public ClassifierType getClassifierType() {
		return ClassifierType.REFERENCE_ATTRIBUTE;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return new ReferenceAttributeSkipToken(referenceKey.referenceName(), attributeKey);
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (attributeMutation instanceof final AttributeSchemaEvolvingMutation schemaValidatingMutation) {
			final ReferenceSchemaContract referenceSchema = entitySchemaBuilder.getReference(referenceKey.referenceName())
				.orElseThrow(() -> new GenericEvitaInternalError("Reference to type `" + referenceKey.referenceName() + "` was not found!"));

			attributeMutation.verifyOrEvolveSchema(
				catalogSchema,
				entitySchemaBuilder,
				referenceSchema.getAttribute(attributeKey.attributeName()).orElse(null),
				schemaValidatingMutation.getAttributeValue(),
				(csb, esb) -> {
					if (attributeKey.localized()) {
						esb.withLocale(attributeKey.locale());
					}
					final Consumer<ReferenceSchemaBuilder> referenceSchemaUpdater = whichIs -> {
						final boolean attributeExists = whichIs.getAttribute(attributeKey.attributeName()).isPresent();
						whichIs.withAttribute(
							attributeKey.attributeName(),
							schemaValidatingMutation.getAttributeValue().getClass(),
							thatIs -> {
								thatIs.localized(attributeKey::localized);
								if (!attributeExists) {
									thatIs.nullable();
								}
							}
						);
					};
					if (referenceSchema.isReferencedEntityTypeManaged()) {
						esb.withReferenceToEntity(
							referenceKey.referenceName(),
							referenceSchema.getReferencedEntityType(),
							referenceSchema.getCardinality(),
							referenceSchemaUpdater
						);
					} else {
						esb.withReferenceTo(
							referenceKey.referenceName(),
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
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot update attributes on reference " + referenceKey + " - reference doesn't exist!")
		);
		// this is kind of expensive, let's hope references will not have many attributes on them that frequently change
		final ExistingReferenceAttributesBuilder attributeBuilder = new ExistingReferenceAttributesBuilder(
			entitySchema,
			existingValue.getReferenceSchema()
				.orElseGet(
					() -> Reference.createImplicitSchema(
						existingValue.getReferenceName(),
						existingValue.getReferencedEntityType(),
						existingValue.getReferenceCardinality(),
						existingValue.getGroup().orElse(null)
					)
				),
			existingValue.getAttributeValues(),
			existingValue.getReferenceSchema()
				.map(AttributeSchemaProvider::getAttributes)
				.orElse(Collections.emptyMap())
		);
		final Attributes<AttributeSchemaContract> newAttributes = attributeBuilder
			.mutateAttribute(attributeMutation)
			.build();

		if (attributeBuilder.differs(newAttributes)) {
			return new Reference(
				entitySchema,
				existingValue.version() + 1,
				existingValue.getReferenceName(), existingValue.getReferencedPrimaryKey(),
				existingValue.getReferencedEntityType(), existingValue.getReferenceCardinality(),
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
		final long priority = attributeMutation.getPriority();
		if (priority >= PRIORITY_REMOVAL) {
			return priority + 1;
		} else {
			return priority - 1;
		}
	}

	@Override
	public String toString() {
		return "reference `" + referenceKey + "` attribute mutation: " + attributeMutation;
	}

	@Nonnull
	@Override
	public ReferenceKeyWithAttributeKey getComparableKey() {
		return comparableKey;
	}

	public static class ReferenceKeyWithAttributeKey implements Comparable<ReferenceKeyWithAttributeKey>, Serializable {
		@Serial private static final long serialVersionUID = 773755868610382953L;
		private final ReferenceKey referenceKey;
		private final AttributeKey attributeKey;

		public ReferenceKeyWithAttributeKey(@Nonnull ReferenceKey referenceKey, @Nonnull AttributeKey attributeKey) {
			this.referenceKey = referenceKey;
			this.attributeKey = attributeKey;
		}

		@Override
		public int compareTo(ReferenceKeyWithAttributeKey o) {
			final int entityReferenceComparison = referenceKey.compareTo(o.referenceKey);
			if (entityReferenceComparison == 0) {
				return attributeKey.compareTo(o.attributeKey);
			} else {
				return entityReferenceComparison;
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(referenceKey, attributeKey);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReferenceKeyWithAttributeKey that = (ReferenceKeyWithAttributeKey) o;
			return Objects.equals(referenceKey, that.referenceKey) && Objects.equals(attributeKey, that.attributeKey);
		}
	}

}
