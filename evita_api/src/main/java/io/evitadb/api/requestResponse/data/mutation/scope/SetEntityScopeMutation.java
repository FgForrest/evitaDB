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

package io.evitadb.api.requestResponse.data.mutation.scope;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.ContainerType;
import io.evitadb.dataType.Scope;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Mutation that sets the scope of the target entity. It either archives or restored the entity back to the living set
 * of entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(exclude = "decisiveTimestamp")
public class SetEntityScopeMutation implements LocalMutation<Scope, Scope> {
	@Serial private static final long serialVersionUID = -8465670492875977978L;
	@Getter private final long decisiveTimestamp;
	/**
	 * Scope to be set in the target entity.
	 */
	@Getter private final Scope scope;

	public SetEntityScopeMutation(@Nonnull Scope scope) {
		this.scope = scope;
		this.decisiveTimestamp = System.nanoTime();
	}

	private SetEntityScopeMutation(@Nonnull Scope scope, long decisiveTimestamp) {
		this.scope = scope;
		this.decisiveTimestamp = decisiveTimestamp;
	}

	@Nonnull
	@Override
	public Scope mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable Scope existingValue) {
		return this.scope;
	}

	@Override
	public long getPriority() {
		return 1L;
	}

	@Nonnull
	@Override
	public Scope getComparableKey() {
		return this.scope;
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.ENTITY;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new SetEntityScopeMutation(this.scope, newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "Marking entity as `" + this.scope + "`";
	}

}
