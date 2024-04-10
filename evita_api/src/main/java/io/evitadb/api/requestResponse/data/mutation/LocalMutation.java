/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Entity {@link Mutation} allows to execute mutation operations on {@link EntityContract} object itself. Each change
 * increments {@link Entity#version()} by one, data removal only sets
 * tombstone flag on a specific data and doesn't really remove it. Possible removal will be taken care of during
 * compaction process, leaving data in place allows to see last assigned value to the attribute and also consult
 * last version of the attribute.
 *
 * These traits should help to manage concurrent transactional process as updates to the same entity could be executed
 * safely and concurrently as long as data modification doesn't overlap. Some mutations may also overcome same
 * data concurrent modification if it's safely additive (i.e. incrementation / decrementation and so on).
 *
 * Exact mutations also allows engine implementation to safely update only those indexes that the change really affects
 * and doesn't require additional analysis.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public non-sealed interface LocalMutation<T, S extends Comparable<S>> extends Mutation, Comparable<LocalMutation<T, S>> {
	long PRIORITY_REMOVAL = 10L;
	long PRIORITY_UPSERT = 0L;

	/**
	 * Executes the real mutation. Fabricates new attribute value that will be used in next version of the entity.
	 */
	@Nonnull
	T mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable T existingValue);

	/**
	 * Returns priority of the mutation execution among multiple mutations on single entity. Higher priority means
	 * that mutation will be executed earlier than mutation with lower priority. This prioritization is required so
	 * that checks verifying uniqueness of certain value doesn't fail on scenario that new value conflict with existing
	 * which is also removed in the same run but later. Removal operations should have higher priority then insertions.
	 */
	long getPriority();

	/**
	 * Returns any {@link Comparable} business key allowing to sort mutations in a sensible and repeatable way we can
	 * rely on in tests.
	 */
	S getComparableKey();

	@Override
	default int compareTo(LocalMutation<T, S> o) {
		final int priority = Long.compare(o.getPriority(), getPriority());
		if (priority == 0) {
			final int classCmpResult = getClass().getSimpleName().compareTo(o.getClass().getSimpleName());
			if (classCmpResult == 0) {
				final int businessKeyCmpResult = getComparableKey().compareTo(o.getComparableKey());
				if (businessKeyCmpResult == 0) {
					// this should not happen
					return Integer.compare(System.identityHashCode(this), System.identityHashCode(o));
				} else {
					return businessKeyCmpResult;
				}
			} else {
				return classCmpResult;
			}
		} else {
			return priority;
		}
	}

}
