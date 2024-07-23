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
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
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
import java.util.Optional;

/**
 * This mutation allows to create {@link Reference} in the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class InsertReferenceMutation extends ReferenceMutation<ReferenceKey> implements SchemaEvolvingLocalMutation<ReferenceContract, ReferenceKey> {
	@Serial private static final long serialVersionUID = 6295749367283283232L;

	/**
	 * Contains primary unique identifier of the Reference. The business key consists of
	 * {@link ReferenceSchemaContract#getName()} and {@link Entity#getPrimaryKey()}.
	 */
	@Nonnull
	private final ReferenceKey referenceKey;
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

	public InsertReferenceMutation(@Nonnull ReferenceKey referenceKey, @Nullable Cardinality referenceCardinality, @Nullable String referencedEntityType) {
		super(referenceKey);
		this.referenceKey = referenceKey;
		this.referenceCardinality = referenceCardinality;
		this.referencedEntityType = referencedEntityType;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.CREATE;
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		final Optional<ReferenceSchemaContract> existingSchema = entitySchemaBuilder.getReference(referenceKey.referenceName());
		if (existingSchema.isEmpty()) {
			Assert.isTrue(
				entitySchemaBuilder.allows(EvolutionMode.ADDING_REFERENCES),
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support references of type `" + referenceKey.referenceName() +
						"`, you need to change the schema definition for it first."
				)
			);
			Assert.isTrue(
				referencedEntityType != null && referenceCardinality != null,
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't know the reference of type `" + referenceKey.referenceName() +
						"` but it can be automatically created, in order to do so, you need to specify the cardinality and referenced entity type."
				)
			);
			final Optional<EntitySchemaContract> targetEntity = catalogSchema.getEntitySchema(referencedEntityType);
			if (targetEntity.isEmpty()) {
				entitySchemaBuilder.withReferenceTo(
					referenceKey.referenceName(),
					referencedEntityType,
					referenceCardinality,
					ReferenceSchemaEditor::indexed
				);
			} else {
				entitySchemaBuilder.withReferenceToEntity(
					referenceKey.referenceName(),
					referencedEntityType,
					referenceCardinality,
					ReferenceSchemaEditor::indexed
				);
			}
		} else {
			final ReferenceSchemaContract referenceSchema = existingSchema.get();
			if (referencedEntityType != null) {
				Assert.isTrue(
					referencedEntityType.equals(referenceSchema.getReferencedEntityType()),
					() -> new InvalidMutationException(
						"Entity `" + entitySchemaBuilder.getName() + "` has got the reference of type `" + referenceKey.referenceName() +
							"` already linked to entity type `" + referenceSchema.getReferencedEntityType() + "`, but passed mutation declares relation to type `" + referencedEntityType + "`."
					)
				);
			}
			if (referenceCardinality != null) {
				Assert.isTrue(
					referenceCardinality.equals(referenceSchema.getCardinality()),
					() -> new InvalidMutationException(
						"Entity `" + entitySchemaBuilder.getName() + "` has got the reference of type `" + referenceKey.referenceName() +
							"` already linked to cardinality `" + referenceSchema.getCardinality() + "`, but passed mutation declares cardinality `" + referenceCardinality + "`."
					)
				);
			}
		}
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return new ReferenceSkipToken(referenceKey.referenceName());
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceContract existingValue) {
		if (existingValue == null) {
			return new Reference(
				entitySchema,
				1,
				referenceKey.referenceName(),
				referenceKey.primaryKey(),
				referencedEntityType,
				referenceCardinality,
				null,
				// attributes are inserted in separate mutation
				Collections.emptyList(),
				false
			);
		} else if (existingValue.dropped()) {
			return new Reference(
				entitySchema,
				existingValue.version() + 1,
				referenceKey.referenceName(), referenceKey.primaryKey(),
				existingValue.getReferencedEntityType(),
				existingValue.getReferenceCardinality(),
				existingValue.getGroup()
					.filter(Droppable::exists)
					.map(it -> new GroupEntityReference(it.referencedEntity(), it.primaryKey(), it.version() + 1, true))
					.orElse(null),
				// attributes are inserted in separate mutation
				Collections.emptyList(),
				false
			);
		} else {
			/* SHOULD NOT EVER HAPPEN */
			throw new InvalidMutationException(
				"This mutation cannot be used for updating reference."
			);
		}
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Override
	public ReferenceKey getComparableKey() {
		return referenceKey;
	}

	@Override
	public String toString() {
		return "insert reference `" + referenceKey + "` " +
			(referenceCardinality == null ? "" : " with cardinality `" + referenceCardinality + "`") +
			(referencedEntityType == null ? "" : " referencing type `" + referencedEntityType + "`");
	}
}
