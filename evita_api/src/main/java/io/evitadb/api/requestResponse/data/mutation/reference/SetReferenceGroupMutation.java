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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
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
import java.util.Map;
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
	@Nullable private String resolvedGroupType;

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

	private SetReferenceGroupMutation(
		@Nonnull ReferenceKey referenceKey, @Nullable String groupType, int groupPrimaryKey, long decisiveTimestamp) {
		super(referenceKey, decisiveTimestamp);
		this.groupType = groupType;
		this.groupPrimaryKey = groupPrimaryKey;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		final Optional<ReferenceSchemaContract> referenceSchema = entitySchema.getReference(this.referenceKey.referenceName());
		final Serializable existingGroupType = referenceSchema.map(ReferenceSchemaContract::getReferencedGroupType).orElse(null);

		if (existingGroupType == null) {
			Assert.isTrue(
				this.groupType != null,
				() -> new InvalidMutationException(
					"Cannot set up group in schema `" + entitySchema.getName() + "` reference `" + this.referenceKey.referenceName() +
						"`, if the group entity type is not provided in the mutation."
				)
			);
			return new ReferenceGroupSkipToken(this.referenceKey.referenceName(), this.groupType);
		} else {
			return new ReferenceSkipToken(this.referenceKey.referenceName());
		}
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		final ReferenceSchemaContract referenceSchema = entitySchemaBuilder.getReferenceOrThrowException(this.referenceKey.referenceName());
		final Serializable existingGroupType = referenceSchema.getReferencedGroupType();

		if (existingGroupType == null) {
			Assert.isTrue(
				entitySchemaBuilder.allows(EvolutionMode.ADDING_REFERENCES),
				() -> new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support groups for references of type `" + this.referenceKey.referenceName() +
						"`, you need to change the schema definition for it first."
				)
			);
			Assert.isTrue(
				this.groupType != null,
				() -> new InvalidMutationException(
					"Cannot set up group in schema `" + entitySchemaBuilder.getName() + "` reference `" + this.referenceKey.referenceName() +
						"`, if the group entity type is not provided in the mutation."
				)
			);

			if (referenceSchema.isReferencedEntityTypeManaged()) {
				entitySchemaBuilder.withReferenceToEntity(
						referenceSchema.getName(),
						referenceSchema.getReferencedEntityType(),
						referenceSchema.getCardinality(),
						whichIs -> whichIs.withGroupType(this.groupType)
					);
			} else {
				entitySchemaBuilder.withReferenceTo(
						referenceSchema.getName(),
						referenceSchema.getReferencedEntityType(),
						referenceSchema.getCardinality(),
						whichIs -> whichIs.withGroupType(this.groupType)
					);
			}
		} else if (this.groupType != null) {
			Assert.isTrue(
				existingGroupType.equals(this.groupType),
				() -> new InvalidMutationException(
					"Group is already related to entity `" + existingGroupType +
						"`. It is not possible to change it to `" + this.groupType + "`!"
				)
			);
		}
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceContract existingValue) {
		return mutateLocal(entitySchema, existingValue, Map.of());
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceContract existingValue, @Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot set reference group " + this.referenceKey + " - reference doesn't exist!")
		);

		final Optional<GroupEntityReference> existingReferenceGroup = existingValue.getGroup();
		if (existingReferenceGroup.map(it -> it.getPrimaryKey() == this.groupPrimaryKey && it.exists()).orElse(false)) {
			// no change is necessary
			return existingValue;
		} else {
			return new Reference(
				entitySchema,
				existingValue.getReferenceSchemaOrThrow(),
				existingValue.version() + 1,
				existingValue.getReferenceKey(),
				existingReferenceGroup
					.map(it ->
						     new GroupEntityReference(
							     getGroupType(entitySchema),
							     this.groupPrimaryKey,
							     it.version() + 1,
							     false
						     )
					)
					.orElseGet(() ->
						           new GroupEntityReference(
							           getGroupType(entitySchema),
							           this.groupPrimaryKey,
							           1, false
						           )
					),
				existingValue.getAttributeValues(),
				attributeTypes,
				existingValue.dropped()
			);
		}
	}

	@Nonnull
	private String getGroupType(@Nonnull EntitySchemaContract entitySchema) {
		if (this.resolvedGroupType == null) {
			if (this.groupType == null) {
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(this.referenceKey.referenceName());
				this.resolvedGroupType = referenceSchema.getReferencedGroupType();
				Assert.isTrue(
					this.resolvedGroupType != null,
					() -> new InvalidMutationException(
						"Cannot update the reference group - no group type defined in schema and also not provided in the mutation!"
					)
				);
			} else {
				this.resolvedGroupType = this.groupType;
			}
		}
		return this.resolvedGroupType;
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
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new SetReferenceGroupMutation(this.referenceKey, this.groupType, this.groupPrimaryKey,
		                                     newDecisiveTimestamp
		);
	}

	@Nonnull
	@Override
	public ReferenceMutation<ReferenceKey> withInternalPrimaryKey(int internalPrimaryKey) {
		return new SetReferenceGroupMutation(
			new ReferenceKey(this.referenceKey.referenceName(), this.referenceKey.primaryKey(), internalPrimaryKey),
			this.groupType, this.groupPrimaryKey, this.decisiveTimestamp
		);
	}

	@Override
	public String toString() {
		return "Set the reference group to `" + this.referenceKey + "`: " + this.groupPrimaryKey +
			(this.groupType == null ? "" : " of type `" + this.groupType + "`");
	}
}
