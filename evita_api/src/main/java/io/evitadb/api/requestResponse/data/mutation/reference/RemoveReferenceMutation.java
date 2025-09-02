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
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * This mutation allows to remove {@link Reference} from the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveReferenceMutation extends ReferenceMutation<ReferenceKey> {
	@Serial private static final long serialVersionUID = 5452632579216311397L;

	public RemoveReferenceMutation(@Nonnull ReferenceKey referenceKey) {
		super(referenceKey);
	}

	public RemoveReferenceMutation(@Nonnull String referenceName, int primaryKey) {
		this(new ReferenceKey(referenceName, primaryKey));
	}

	private RemoveReferenceMutation(@Nonnull ReferenceKey referenceKey, long decisiveTimestamp) {
		super(referenceKey, decisiveTimestamp);
	}

	@Nonnull
	@Override
	public ReferenceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceContract existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot remove reference " + this.referenceKey + " - reference doesn't exist!")
		);
		return new Reference(
			entitySchema,
			existingValue.getReferenceSchemaOrThrow(),
			existingValue.version() + 1,
			existingValue.getReferenceKey(),
			existingValue.getGroup()
				.filter(Droppable::exists)
				.map(it -> new GroupEntityReference(it.referencedEntity(), it.primaryKey(), it.version() + 1, true))
				.orElse(null),
			existingValue.getAttributeValues(),
			true
		);
	}

	@Override
	public long getPriority() {
		return LocalMutation.PRIORITY_REMOVAL;
	}

	@Override
	public ReferenceKey getComparableKey() {
		return this.referenceKey;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new RemoveReferenceMutation(this.referenceKey, newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "Remove reference `" + this.referenceKey + "`";
	}
}
