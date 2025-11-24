/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.GraphQLExtraResultsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.InScopeHeaderDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

import static io.evitadb.api.query.QueryConstraints.inScope;

/**
 * Ancestor for resolvers of extra results constraints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public abstract class AbstractExtraResultConstraintResolver {

	/**
	 * Resolves in which {@link Scope} is the passed extra result field defined.
	 */
	@Nullable
	protected Scope resolveScope(@Nonnull SelectedField extraResultField) {
		final SelectedField extraResultParentField = extraResultField.getParentField();
		if (GraphQLExtraResultsDescriptor.IN_SCOPE.name().equals(extraResultParentField.getName())) {
			return (Scope) extraResultParentField.getArguments().get(InScopeHeaderDescriptor.SCOPE.name());
		} else {
			return null;
		}
	}

	/**
	 * Wraps the passed require constraint into {@link io.evitadb.api.query.require.RequireInScope} constraint if the
	 * scope is present.
	 */
	@Nonnull
	protected RequireConstraint wrapInScopeConstraint(@Nullable Scope scope, @Nonnull RequireConstraint requireConstraint) {
		if (scope != null) {
			return Objects.requireNonNull(inScope(scope, requireConstraint));
		} else {
			return requireConstraint;
		}
	}
}
