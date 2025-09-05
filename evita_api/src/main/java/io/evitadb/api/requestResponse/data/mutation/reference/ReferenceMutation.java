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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.NamedLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;

/**
 * Base mutation class for mutations that work with {@link Reference} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public abstract class ReferenceMutation<T extends Comparable<T>> implements NamedLocalMutation<ReferenceContract, T> {
	@Serial private static final long serialVersionUID = -4870057553122671488L;
	@Getter protected final long decisiveTimestamp;
	/**
	 * Identification of the reference that is being manipulated by this mutation.
	 */
	@Getter protected final ReferenceKey referenceKey;

	protected ReferenceMutation(@Nonnull ReferenceKey referenceKey) {
		this.referenceKey = referenceKey;
		this.decisiveTimestamp = System.nanoTime();
	}

	protected ReferenceMutation(@Nonnull String referenceName, int primaryKey) {
		this(new ReferenceKey(referenceName, primaryKey));
	}

	protected ReferenceMutation(@Nonnull ReferenceKey referenceKey, long decisiveTimestamp) {
		this.decisiveTimestamp = decisiveTimestamp;
		this.referenceKey = referenceKey;
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.REFERENCE;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.referenceKey.referenceName();
	}

	/**
	 * Creates a new mutation instance that is identical to the current one but contains also the internal primary key
	 * of the referenced entity.
	 *
	 * @param internalPrimaryKey - internal primary key of the referenced entity
	 * @return new mutation instance with the internal primary key set
	 */
	@Nonnull
	public abstract ReferenceMutation<T> withInternalPrimaryKey(int internalPrimaryKey);

	/**
	 * Specialized method used in local builders to apply this mutation and keep information about shared attribute
	 * schema definitions that were created implicitly on the client side and were not yet persisted in the reference
	 * schema.
	 *
	 * @param entitySchema of the entity to which the reference belongs
	 * @param existingValue current value of the reference - if the reference is not yet created, the value is null
	 * @param attributeTypes map of attribute types that were created on the client side and are not yet persisted
	 *                       in the schema
	 * @return mutated reference
	 */
	@Nonnull
	public abstract ReferenceContract mutateLocal(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceContract existingValue,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	);
}
