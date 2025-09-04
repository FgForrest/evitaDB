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
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * This mutation allows to create {@link Reference} in the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class InsertReferenceMutation extends ReferenceMutation<ReferenceKey>
	implements SchemaEvolvingLocalMutation<ReferenceContract, ReferenceKey> {
	@Serial private static final long serialVersionUID = 6295749367283283232L;

	/**
	 * Contains information about reference cardinality. This value is usually NULL except the case when the reference
	 * is created for the first time and {@link io.evitadb.api.requestResponse.schema.EvolutionMode#ADDING_REFERENCES} is allowed.
	 */
	@Getter
	@Nullable
	private final Cardinality referenceCardinality;
	/**
	 * Contains information about target entity type. This value is usually NULL except the case when the reference
	 * is created for the first time and {@link io.evitadb.api.requestResponse.schema.EvolutionMode#ADDING_REFERENCES} is allowed.
	 */
	@Getter
	@Nullable
	private final String referencedEntityType;

	public InsertReferenceMutation(@Nonnull ReferenceKey referenceKey) {
		super(referenceKey);
		this.referenceCardinality = null;
		this.referencedEntityType = null;
	}

	public InsertReferenceMutation(
		@Nonnull ReferenceKey referenceKey,
		@Nullable Cardinality referenceCardinality,
		@Nullable String referencedEntityType
	) {
		super(referenceKey);
		this.referenceCardinality = referenceCardinality;
		this.referencedEntityType = referencedEntityType;
	}

	public InsertReferenceMutation(
		@Nonnull ReferenceKey referenceKey,
		@Nullable Cardinality referenceCardinality,
		@Nullable String referencedEntityType,
		long decisiveTimestamp
	) {
		super(referenceKey, decisiveTimestamp);
		this.referenceCardinality = referenceCardinality;
		this.referencedEntityType = referencedEntityType;
	}

	@Override
	public void verifyOrEvolveSchema(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaBuilder entitySchemaBuilder
	) throws InvalidMutationException {
		final Optional<ReferenceSchemaContract> existingSchema = entitySchemaBuilder.getReference(
			this.referenceKey.referenceName());
		if (existingSchema.isEmpty()) {
			Assert.isTrue(
				entitySchemaBuilder.allows(EvolutionMode.ADDING_REFERENCES),
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support references of type `" + this.referenceKey.referenceName() +
						"`, you need to change the schema definition for it first."
				)
			);
			Assert.isTrue(
				this.referencedEntityType != null && this.referenceCardinality != null,
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't know the reference of type `" + this.referenceKey.referenceName() +
						"` but it can be automatically created, in order to do so, you need to specify the cardinality and referenced entity type."
				)
			);
			final Optional<EntitySchemaContract> targetEntity = catalogSchema.getEntitySchema(
				this.referencedEntityType);
			if (targetEntity.isEmpty()) {
				entitySchemaBuilder.withReferenceTo(
					this.referenceKey.referenceName(),
					this.referencedEntityType,
					this.referenceCardinality,
					ReferenceSchemaEditor::indexed
				);
			} else {
				entitySchemaBuilder.withReferenceToEntity(
					this.referenceKey.referenceName(),
					this.referencedEntityType,
					this.referenceCardinality,
					ReferenceSchemaEditor::indexed
				);
			}
		} else {
			final ReferenceSchemaContract referenceSchema = existingSchema.get();
			if (this.referencedEntityType != null) {
				Assert.isTrue(
					this.referencedEntityType.equals(referenceSchema.getReferencedEntityType()),
					() -> new InvalidMutationException(
						"Entity `" + entitySchemaBuilder.getName() + "` has got the reference of type `" + this.referenceKey.referenceName() +
							"` already linked to entity type `" + referenceSchema.getReferencedEntityType() + "`, but passed mutation declares relation to type `" + this.referencedEntityType + "`."
					)
				);
			}
			if (this.referenceCardinality != null) {
				final boolean cardinalitySame = this.referenceCardinality.equals(referenceSchema.getCardinality());
				if (cardinalitySame) {
					// everything is fine
				} else if (entitySchemaBuilder.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
					final boolean currentDoesntAllowDuplicates = referenceSchema.getCardinality().allowsDuplicates();
					final boolean bothAllowDuplicates = this.referenceCardinality.allowsDuplicates() == currentDoesntAllowDuplicates;
					if (
						referenceSchema.getCardinality().getMin() >= this.referenceCardinality.getMin() &&
						referenceSchema.getCardinality().getMax() <= this.referenceCardinality.getMax() &&
						(!currentDoesntAllowDuplicates || bothAllowDuplicates)
					) {
						// we can change the cardinality only in towards less restrictive one
						entitySchemaBuilder.withReferenceToEntity(
							referenceSchema.getName(),
							referenceSchema.getReferencedEntityType(),
							this.referenceCardinality
						);
					} else {
						throw new InvalidMutationException(
							"Entity `" + entitySchemaBuilder.getName() + "` has got the reference of type `" + this.referenceKey.referenceName() +
								"` already linked to cardinality `" + referenceSchema.getCardinality() + "`, but passed mutation declares cardinality `" + this.referenceCardinality + "`."
						);
					}
				}
			}
		}
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return new ReferenceSkipToken(this.referenceKey.referenceName());
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceContract existingValue
	) {
		return mutateLocal(entitySchema, existingValue, Map.of());
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Override
	public ReferenceKey getComparableKey() {
		return this.referenceKey;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new InsertReferenceMutation(
			this.referenceKey, this.referenceCardinality, this.referencedEntityType, newDecisiveTimestamp
		);
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceContract existingValue,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		if (existingValue == null) {
			return new Reference(
				entitySchema,
				getReferenceSchemaOrCreateImplicit(entitySchema),
				1,
				this.referenceKey,
				null,
				// attributes are inserted in separate mutation
				Collections.emptyList(),
				attributeTypes,
				false
			);
		} else if (existingValue.dropped()) {
			return new Reference(
				entitySchema,
				entitySchema.getReferenceOrThrowException(existingValue.getReferenceName()),
				existingValue.version() + 1,
				this.referenceKey,
				existingValue.getGroup()
				             .filter(Droppable::exists)
				             .map(it -> new GroupEntityReference(
					             it.referencedEntity(), it.primaryKey(),
					             it.version() + 1, true
				             ))
				             .orElse(null),
				// attributes are inserted in separate mutation
				Collections.emptyList(),
				attributeTypes,
				false
			);
		} else {
			/* SHOULD NOT EVER HAPPEN */
			throw new InvalidMutationException(
				"This mutation cannot be used for updating reference."
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "insert reference `" + this.referenceKey + "` " +
			(this.referenceCardinality == null ? "" : " with cardinality `" + this.referenceCardinality + "`") +
			(this.referencedEntityType == null ? "" : " referencing type `" + this.referencedEntityType + "`");
	}

	/**
	 * Creates a new instance of {@code InsertReferenceMutation} with the updated cardinality.
	 * This method is used to modify the cardinality of a reference mutation while keeping other fields unchanged.
	 *
	 * @param newCardinality the new {@link Cardinality} to be applied for this reference mutation
	 * @return a new instance of {@code InsertReferenceMutation} with the specified cardinality
	 */
	@Nonnull
	public InsertReferenceMutation withCardinality(@Nonnull Cardinality newCardinality) {
		return new InsertReferenceMutation(
			this.referenceKey, newCardinality, this.referencedEntityType
		);
	}

	/**
	 * Creates a new instance of {@code InsertReferenceMutation} with the specified referenced entity type
	 * and cardinality. This method allows updating the associated entity type and cardinality for a reference mutation,
	 * while keeping other fields unchanged.
	 *
	 * @param referencedEntityType the type of the entity being referenced
	 * @param cardinality          the {@link Cardinality} to be applied to the reference
	 * @return a new instance of {@code InsertReferenceMutation} with the updated referenced entity type and cardinality
	 */
	@Nonnull
	public InsertReferenceMutation withReferenceTo(
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality
	) {
		return new InsertReferenceMutation(
			this.referenceKey, cardinality, referencedEntityType
		);
	}

	/**
	 * Retrieves the reference schema associated with the given entity schema and reference key.
	 * If the reference schema is not explicitly defined in the entity schema, this method attempts
	 * to create an implicit reference schema based on the reference key, referenced entity type,
	 * and reference cardinality. If the referenced entity type and cardinality are not provided,
	 * it throws an {@link InvalidMutationException}.
	 *
	 * @param entitySchema the entity schema from which to resolve or implicitly create the reference schema
	 * @return the resolved or newly created reference schema
	 * @throws InvalidMutationException if the reference schema cannot be created implicitly due to missing
	 *                                  referenced entity type or cardinality
	 */
	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrCreateImplicit(@Nonnull EntitySchemaContract entitySchema) {
		return entitySchema
			.getReference(this.referenceKey.referenceName())
			.orElseGet(
				() -> {
					if (this.referencedEntityType == null || this.referenceCardinality == null) {
						throw new InvalidMutationException(
							"The reference `" + this.referenceKey.referenceName() + "` is not defined " +
								"in the schema and cannot be created implicitly because " +
								"the referenced entity type and cardinality are not provided in the mutation."
						);
					}
					return ReferencesBuilder.createImplicitSchema(
						entitySchema,
						this.referenceKey.referenceName(),
						this.referencedEntityType,
						this.referenceCardinality,
						null
					);
				}
			);
	}
}
