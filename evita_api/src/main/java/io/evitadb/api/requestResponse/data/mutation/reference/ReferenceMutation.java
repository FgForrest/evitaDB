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
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Base mutation class for mutations that work with {@link Reference} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public abstract class ReferenceMutation<T extends Comparable<T>> implements NamedLocalMutation<ReferenceContract, T> {
	@Serial private static final long serialVersionUID = -4870057553122671488L;
	@Getter private final long decisiveTimestamp;
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

}
