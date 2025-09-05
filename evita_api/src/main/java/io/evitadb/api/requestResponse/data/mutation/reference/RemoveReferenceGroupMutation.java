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
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.Optional;

/**
 * This mutation allows to remove {@link GroupEntityReference} in the {@link Reference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true, exclude = "comparableKey")
public class RemoveReferenceGroupMutation extends ReferenceMutation<ComparableReferenceKey> {
	@Serial private static final long serialVersionUID = -3127011415344111902L;
	/**
	 * Full identification of the mutation that is used for sorting mutations.
	 */
	@Nonnull
	private final ComparableReferenceKey comparableKey;

	public RemoveReferenceGroupMutation(@Nonnull ReferenceKey referenceKey) {
		super(referenceKey);
		this.comparableKey = new ComparableReferenceKey(referenceKey);
	}

	private RemoveReferenceGroupMutation(@Nonnull ReferenceKey referenceKey, long decisiveTimestamp) {
		super(referenceKey, decisiveTimestamp);
		this.comparableKey = new ComparableReferenceKey(referenceKey);
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
			() -> new InvalidMutationException("Cannot remove reference " + this.referenceKey + " - reference doesn't exist!")
		);
		Assert.isTrue(
			existingValue.getGroup().filter(Droppable::exists).isPresent(),
			() -> new InvalidMutationException("Cannot remove reference " + this.referenceKey + " group - no group is currently set!")
		);

		final Optional<GroupEntityReference> existingReferenceGroup = existingValue.getGroup();
		Assert.isTrue(
			existingReferenceGroup.filter(Droppable::exists).isPresent(),
			() -> new InvalidMutationException("Cannot remove reference group - no reference group is set on reference " + this.referenceKey + "!")
		);
		return new Reference(
			entitySchema,
			existingValue.getReferenceSchemaOrThrow(),
			existingValue.version() + 1,
			existingValue.getReferenceKey(),
			existingReferenceGroup
				.filter(Droppable::exists)
				.map(it -> new GroupEntityReference(
					     it.getType(),
					     it.getPrimaryKey(),
					     it.version() + 1,
					     true
				     )
				).orElseThrow(() -> new InvalidMutationException("Cannot remove reference group - no reference group is set on reference " + this.referenceKey + "!")),
			existingValue.getAttributeValues(),
			attributeTypes,
			existingValue.dropped()
		);
	}

	@Override
	public long getPriority() {
		// we need that this mutation is before insert/remove reference itself
		return PRIORITY_REMOVAL * 2;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new RemoveReferenceGroupMutation(this.referenceKey, newDecisiveTimestamp);
	}

	@Nonnull
	@Override
	public ReferenceMutation<ComparableReferenceKey> withInternalPrimaryKey(int internalPrimaryKey) {
		return new RemoveReferenceGroupMutation(
			new ReferenceKey(this.referenceKey.referenceName(), this.referenceKey.primaryKey(), internalPrimaryKey),
			this.decisiveTimestamp
		);
	}

	@Nonnull
	@Override
	public ComparableReferenceKey getComparableKey() {
		return this.comparableKey;
	}

	@Override
	public String toString() {
		return "Remove reference group `" + this.referenceKey + "`";
	}
}
