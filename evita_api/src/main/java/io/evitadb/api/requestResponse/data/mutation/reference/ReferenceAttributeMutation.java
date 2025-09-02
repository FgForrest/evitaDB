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
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot update attributes on reference " + this.referenceKey + " - reference doesn't exist!")
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

	@Override
	public String toString() {
		return "reference `" + this.referenceKey + "` attribute mutation: " + this.attributeMutation;
	}

	@Nonnull
	@Override
	public ReferenceKeyWithAttributeKey getComparableKey() {
		return this.comparableKey;
	}

	/**
	 * Represents a composite key combining a reference key and an attribute key.
	 * This class facilitates operations that require both the {@link ReferenceKey} and the {@link AttributeKey}.
	 * The class ensures proper ordering and comparison for its instances by implementing the {@link Comparable} interface.
	 * Additionally, it provides equality checks and a hash code implementation based on its constituent keys.
	 *
	 * This class is immutable and thread-safe.
	 *
	 * Implements methods to:
	 * - Compare instances based on their reference key and attribute key.
	 * - Evaluate equality and generate hash codes consistently.
	 *
	 * Suitable for usage in scenarios where entities with associated attribute keys need to be ordered, filtered, or stored in collections requiring
	 * comparison or uniqueness constraints.
	 */
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
			final int entityReferenceComparison = this.referenceKey.compareTo(o.referenceKey);
			if (entityReferenceComparison == 0) {
				return this.attributeKey.compareTo(o.attributeKey);
			} else {
				return entityReferenceComparison;
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.referenceKey, this.attributeKey);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReferenceKeyWithAttributeKey that = (ReferenceKeyWithAttributeKey) o;
			return Objects.equals(this.referenceKey, that.referenceKey) && Objects.equals(this.attributeKey, that.attributeKey);
		}
	}

}
