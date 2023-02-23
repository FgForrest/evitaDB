/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Set;

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
public class ContainerKey extends CachableElementKey {

	/**
	 * Allowed child types in the built container
	 */
	@Nonnull
	private final Set<Class<? extends Constraint<?>>> allowedChildTypes;
	/**
	 * Forbidden child types in the built container
	 */
	@Nonnull
	private final Set<Class<? extends Constraint<?>>> forbiddenChildTypes;

	public ContainerKey(@Nonnull ConstraintType containerType,
	                    @Nonnull DataLocator dataLocator,
	                    @Nonnull Set<Class<? extends Constraint<?>>> allowedChildTypes,
	                    @Nonnull Set<Class<? extends Constraint<?>>> forbiddenChildTypes) {
		super(containerType, dataLocator);
		this.allowedChildTypes = allowedChildTypes;
		this.forbiddenChildTypes = forbiddenChildTypes;
	}

	@Override
	@Nonnull
	public String toHash() {
		final LongHashFunction hashFunction = LongHashFunction.xx3();
		final long keyHash = hashFunction.hashLongs(new long[] {
			hashContainerType(hashFunction),
			hashDataLocator(hashFunction),
			hashAllowedChildTypes(hashFunction),
			hashForbiddenChildTypes(hashFunction)
		});
		return Long.toHexString(keyHash);
	}

	private long hashAllowedChildTypes(LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			getAllowedChildTypes()
				.stream()
				.map(Class::getSimpleName)
				.sorted()
				.mapToLong(hashFunction::hashChars)
				.toArray()
		);
	}

	private long hashForbiddenChildTypes(LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			getForbiddenChildTypes()
				.stream()
				.map(Class::getSimpleName)
				.sorted()
				.mapToLong(hashFunction::hashChars)
				.toArray()
		);
	}
}
