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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Identifies single created constraint container (usually JSON object representing some {@link io.evitadb.api.query.ConstraintContainer})
 * mainly for reuse by those who need the same container. These metadata
 * must ensure that two containers with these metadata are ultimately same.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ContainerKey extends CacheableElementKey {

	/**
	 * Predicate defining allowed constraints in the container.
	 */
	@Nonnull
	private final AllowedConstraintPredicate allowedConstraintPredicate;

	public ContainerKey(@Nonnull ConstraintType containerType,
	                    @Nonnull DataLocator dataLocator,
	                    @Nonnull AllowedConstraintPredicate allowedConstraintPredicate) {
		super(containerType, dataLocator);
		this.allowedConstraintPredicate = allowedConstraintPredicate;
	}

	@Override
	@Nonnull
	public String toHash() {
		final LongHashFunction hashFunction = LongHashFunction.xx3();
		final long keyHash = hashFunction.hashLongs(new long[] {
			hashContainerType(hashFunction),
			hashDataLocator(hashFunction),
			hashAllowedConstraintPredicate(hashFunction, this.allowedConstraintPredicate)
		});
		return Long.toHexString(keyHash);
	}
}
