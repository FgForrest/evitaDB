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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.OptionalInt;

/**
 * This mutation allows to set parent in the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class SetParentMutation extends ParentMutation {
	@Serial private static final long serialVersionUID = 8277337397634643354L;
	/**
	 * The parent primary key that needs to be set to the entity.
	 */
	@Getter private final int parentPrimaryKey;

	public SetParentMutation(int parentPrimaryKey) {
		this.parentPrimaryKey = parentPrimaryKey;
	}

	private SetParentMutation(int parentPrimaryKey, long decisiveTimestamp) {
		super(decisiveTimestamp);
		this.parentPrimaryKey = parentPrimaryKey;
	}

	@Nonnull
	@Override
	public OptionalInt mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable OptionalInt existingValue) {
		return OptionalInt.of(this.parentPrimaryKey);
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Override
	public Integer getComparableKey() {
		return this.parentPrimaryKey;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new SetParentMutation(this.parentPrimaryKey, newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "set parent to: `" + this.parentPrimaryKey + "`";
	}

}
