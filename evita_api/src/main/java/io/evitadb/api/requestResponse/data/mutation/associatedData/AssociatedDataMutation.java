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

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.mutation.NamedLocalMutation;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.ContainerType;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Associated data {@link Mutation} allows to execute mutation operations on {@link AssociatedData} of
 * the {@link EntityContract} object. Each associated data change increments {@link AssociatedDataValue#version()} by
 * one, associated data removal only sets tombstone flag on a associated data value and doesn't really remove it.
 * Possible removal will be taken care of during compaction process, leaving associatedData in place allows to see last
 * assigned value to the associated data and also consult last version of the associated data.
 *
 * These traits should help to manage concurrent transactional process as updates to the same entity could be executed
 * safely and concurrently as long as associated data modification doesn't overlap. Some mutations may also overcome same
 * associated data concurrent modification if it's safely additive (i.e. incrementation / decrementation and so on).
 *
 * Exact mutations also allows engine implementation to safely update only those indexes that the change really affects
 * and doesn't require additional analysis.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(exclude = "decisiveTimestamp")
public abstract class AssociatedDataMutation implements NamedLocalMutation<AssociatedDataValue, AssociatedDataKey> {
	@Serial private static final long serialVersionUID = 2877681453791825337L;
	@Getter private final long decisiveTimestamp;
	/**
	 * Identification of the associated data that the mutation affects.
	 */
	@Nonnull
	@Getter protected final AssociatedDataKey associatedDataKey;

	protected AssociatedDataMutation(@Nonnull AssociatedDataKey associatedDataKey) {
		Assert.isTrue(associatedDataKey != null, "Associated data key cannot be null for set associated data mutation!");
		this.associatedDataKey = associatedDataKey;
		this.decisiveTimestamp = System.nanoTime();
	}

	protected AssociatedDataMutation(@Nonnull AssociatedDataKey associatedDataKey, long decisiveTimestamp) {
		Assert.isTrue(associatedDataKey != null, "Associated data key cannot be null for set associated data mutation!");
		this.associatedDataKey = associatedDataKey;
		this.decisiveTimestamp = decisiveTimestamp;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.associatedDataKey.associatedDataName();
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.ASSOCIATED_DATA;
	}

	@Nonnull
	@Override
	public AssociatedDataKey getComparableKey() {
		return this.associatedDataKey;
	}

	protected void verifyOrEvolveSchema(
		@Nonnull EntitySchemaBuilder entitySchemaBuilder,
		@Nonnull Serializable associatedDataValue,
		@Nonnull Consumer<EntitySchemaBuilder> schemaEvolutionApplicator
	) throws InvalidMutationException {
		final Optional<AssociatedDataSchemaContract> associatedDataOpt = entitySchemaBuilder.getAssociatedData(this.associatedDataKey.associatedDataName());
		associatedDataOpt
			.ifPresent(associatedDataSchema -> {
				// when associated data definition is known execute first encounter formal verification
				Assert.isTrue(
					associatedDataSchema.getType().isInstance(associatedDataValue),
					() -> new InvalidMutationException(
						"Invalid type: `" + associatedDataValue.getClass() + "`! " +
							"Associated data `" + this.associatedDataKey.associatedDataName() + "` in schema `" + entitySchemaBuilder.getName() + "` was already stored as type `" + associatedDataSchema.getType() + "`. " +
							"All values of associated data `" + this.associatedDataKey.associatedDataName() + "` must respect this data type!"
					)
				);
				if (associatedDataSchema.isLocalized()) {
					Assert.isTrue(
						this.associatedDataKey.localized(),
						() -> new InvalidMutationException(
							"Associated data `" + this.associatedDataKey.associatedDataName() + "` in schema `" + entitySchemaBuilder.getName() + "` was already stored as localized value. " +
								"All values of associated data `" + this.associatedDataKey.associatedDataName() + "` must be localized now " +
								"- use different associated data name for locale independent variant of associated data!!"
						)
					);
				} else {
					Assert.isTrue(
						!this.associatedDataKey.localized(),
						() -> new InvalidMutationException(
							"Associated data `" + this.associatedDataKey.associatedDataName() + "` in schema `" + entitySchemaBuilder.getName() + "` was not stored as localized value. " +
								"No values of associated data `" + this.associatedDataKey.associatedDataName() + "` can be localized now " +
								"- use different associated data name for localized variant of associated data!"
						)
					);
				}
			});

		// else check whether adding associated data on the fly is allowed
		if (associatedDataOpt.isEmpty()) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
				// evolve schema automatically
				schemaEvolutionApplicator.accept(entitySchemaBuilder);
			} else {
				throw new InvalidMutationException(
					"Unknown associated data `" + this.associatedDataKey.associatedDataName() + "` in entity `" + entitySchemaBuilder.getName() + "`!" +
						" You must first alter entity schema to be able to add this associated data to the entity!"
				);
			}
		}
	}

}
