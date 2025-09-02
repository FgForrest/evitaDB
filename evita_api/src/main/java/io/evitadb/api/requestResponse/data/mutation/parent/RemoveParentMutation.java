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

package io.evitadb.api.requestResponse.data.mutation.parent;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.OptionalInt;

/**
 * This mutation allows to remove hierachical placement (parent) from the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveParentMutation extends ParentMutation {
	@Serial private static final long serialVersionUID = 1740874836848423328L;

	public RemoveParentMutation() {
	}

	private RemoveParentMutation(long decisiveTimestamp) {
		super(decisiveTimestamp);
	}

	@Nonnull
	@Override
	public OptionalInt mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable OptionalInt existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.isPresent(),
			() -> new InvalidMutationException("Cannot remove parent that is not present!")
		);
		return OptionalInt.empty();
	}

	@Override
	public long getPriority() {
		return PRIORITY_REMOVAL;
	}

	@Override
	public Integer getComparableKey() {
		return Integer.MIN_VALUE;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new RemoveParentMutation(newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "remove parent";
	}

}
