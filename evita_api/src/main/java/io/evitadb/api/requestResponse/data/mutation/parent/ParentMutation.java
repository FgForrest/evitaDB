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
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.OptionalInt;

/**
 * Base mutation class for mutations that work with parent (hierarchical placement) of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(exclude = "decisiveTimestamp")
public abstract class ParentMutation implements SchemaEvolvingLocalMutation<OptionalInt, Integer> {
	@Serial private static final long serialVersionUID = -4870057553122671488L;
	@Getter private final long decisiveTimestamp;

	protected ParentMutation() {
		this.decisiveTimestamp = System.nanoTime();
	}

	protected ParentMutation(long decisiveTimestamp) {
		this.decisiveTimestamp = decisiveTimestamp;
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.ENTITY;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return EntityContract.class;
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (!entitySchemaBuilder.isWithHierarchy()) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_HIERARCHY)) {
				entitySchemaBuilder.withHierarchy();
			} else {
				throw new InvalidMutationException("Entity type " + entitySchemaBuilder.getName() + " doesn't allow hierarchy placement!");
			}
		}
	}

}
