/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.data.mutation.NamedLocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;

import javax.annotation.Nonnull;

/**
 * Predicate filters out only mutations that are related to local mutations that target classifier with a particular name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SingleContainerNamePredicate extends MutationPredicate {
	private final String classifierName;

	public SingleContainerNamePredicate(@Nonnull MutationPredicateContext context, @Nonnull String classifierName) {
		super(context);
		this.classifierName = classifierName;
	}

	@Override
	public boolean test(Mutation mutation) {
		if (mutation instanceof NamedLocalMutation<?, ?> namedMutation) {
			return this.classifierName.contains(namedMutation.containerName());
		} else if (mutation instanceof NamedSchemaMutation namedMutation) {
			return this.classifierName.contains(namedMutation.containerName());
		} else {
			return true;
		}
	}
}
