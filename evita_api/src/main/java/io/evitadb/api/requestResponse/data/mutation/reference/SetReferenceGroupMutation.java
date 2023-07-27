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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * This mutation allows to create / update {@link GroupEntityReference} of the {@link Reference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class SetReferenceGroupMutation extends ReferenceMutation<ReferenceKey> implements SchemaEvolvingLocalMutation<ReferenceContract, ReferenceKey> {
	@Serial private static final long serialVersionUID = -8894714389485857588L;
	/**
	 * Group type is mandatory only when {@link EntitySchemaContract} hasn't yet known the group type, but can learn it due
	 * to {@link EvolutionMode#ADDING_REFERENCES}. For the first time the group is set for the reference the group
	 * type definition is required. In all other use-cases this information could (and is recommended) to be omitted.
	 */
	@Getter @Nullable private final String groupType;
	/**
	 * Primary key of the referenced group entity (or external resource).
	 */
	@Getter private final int groupPrimaryKey;
	/**
	 * Internal temporary information about the group type either copied from {@link #groupType} or retrieved from
	 * the current {@link EntitySchemaContract} definition.
	 */
	private String resolvedGroupType;

	public SetReferenceGroupMutation(@Nonnull ReferenceKey referenceKey, int groupPrimaryKey) {
		super(referenceKey);
		this.groupType = null;
		this.groupPrimaryKey = groupPrimaryKey;
	}

	public SetReferenceGroupMutation(@Nonnull ReferenceKey referenceKey, @Nullable String groupType, int groupPrimaryKey) {
		super(referenceKey);
		this.groupType = groupType;
		this.groupPrimaryKey = groupPrimaryKey;
	}

	public SetReferenceGroupMutation(@Nonnull String referenceName, int referencedEntityPrimaryKey, int groupPrimaryKey) {
		super(referenceName, referencedEntityPrimaryKey);
		this.groupType = null;
		this.groupPrimaryKey = groupPrimaryKey;
	}

	public SetReferenceGroupMutation(@Nonnull String referenceName, int referencedEntityPrimaryKey, @Nullable String groupType, int groupPrimaryKey) {
		super(referenceName, referencedEntityPrimaryKey);
		this.groupType = groupType;
		this.groupPrimaryKey = groupPrimaryKey;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		final Optional<ReferenceSchemaContract> referenceSchema = entitySchema.getReference(referenceKey.referenceName());
		final Serializable existingGroupType = referenceSchema.map(ReferenceSchemaContract::getReferencedGroupType).orElse(null);

		if (existingGroupType == null) {
			Assert.isTrue(
				groupType != null,
				() -> new InvalidMutationException(
					"Cannot set up group in schema `" + entitySchema.getName() + "` reference `" + referenceKey.referenceName() +
						"`, if the group entity type is not provided in the mutation."
				)
			);
			return new ReferenceGroupSkipToken(referenceKey.referenceName(), groupType);
		} else {
			return new ReferenceSkipToken(referenceKey.referenceName());
		}
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		final ReferenceSchemaContract referenceSchema = entitySchemaBuilder.getReferenceOrThrowException(referenceKey.referenceName());
		final Serializable existingGroupType = referenceSchema.getReferencedGroupType();

		if (existingGroupType == null) {
			Assert.isTrue(
				entitySchemaBuilder.allows(EvolutionMode.ADDING_REFERENCES),
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support groups for references of type `" + referenceKey.referenceName() +
						"`, you need to change the schema definition for it first."
				)
			);
			Assert.isTrue(
				groupType != null,
				() -> new InvalidMutationException(
					"Cannot set up group in schema `" + entitySchemaBuilder.getName() + "` reference `" + referenceKey.referenceName() +
						"`, if the group entity type is not provided in the mutation."
				)
			);

			if (referenceSchema.isReferencedEntityTypeManaged()) {
				entitySchemaBuilder.withReferenceToEntity(
						referenceSchema.getName(),
						referenceSchema.getReferencedEntityType(),
						referenceSchema.getCardinality(),
						whichIs -> whichIs.withGroupType(groupType)
					);
			} else {
				entitySchemaBuilder.withReferenceTo(
						referenceSchema.getName(),
						referenceSchema.getReferencedEntityType(),
						referenceSchema.getCardinality(),
						whichIs -> whichIs.withGroupType(groupType)
					);
			}
		} else if (groupType != null) {
			Assert.isTrue(
				existingGroupType.equals(groupType),
				() -> new InvalidMutationException(
					"Group is already related to entity `" + existingGroupType +
						"`. It is not possible to change it to `" + groupType + "`!"
				)
			);
		}
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceContract existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot set reference group " + referenceKey + " - reference doesn't exist!")
		);

		final Optional<GroupEntityReference> existingReferenceGroup = existingValue.getGroup();
		if (existingReferenceGroup.map(it -> it.getPrimaryKey() == groupPrimaryKey && it.exists()).orElse(false)) {
			// no change is necessary
			return existingValue;
		} else {
			return new Reference(
				entitySchema,
				existingValue.version() + 1,
				existingValue.getReferenceName(), existingValue.getReferencedPrimaryKey(),
				existingValue.getReferencedEntityType(), existingValue.getReferenceCardinality(),
				existingReferenceGroup
					.map(it ->
						new GroupEntityReference(
							getGroupType(entitySchema),
							groupPrimaryKey,
							it.version() + 1,
							false
						)
					)
					.orElseGet(() ->
						new GroupEntityReference(
							getGroupType(entitySchema),
							groupPrimaryKey,
							1, false
						)
					),
				existingValue.getAttributeValues(),
				existingValue.dropped()
			);
		}
	}

	@Nonnull
	private String getGroupType(@Nonnull EntitySchemaContract entitySchema) {
		if (resolvedGroupType == null) {
			if (groupType == null) {
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
				resolvedGroupType = referenceSchema.getReferencedGroupType();
				Assert.isTrue(
					resolvedGroupType != null,
					() -> new InvalidMutationException(
						"Cannot update reference group - no group type defined in schema and also not provided in the mutation!"
					)
				);
			} else {
				resolvedGroupType = groupType;
			}
		}
		return resolvedGroupType;
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
		return "Set reference group to `" + referenceKey + "`: " + groupPrimaryKey +
			(groupType == null ? "" : " of type `" + groupType + "`");
	}
}
