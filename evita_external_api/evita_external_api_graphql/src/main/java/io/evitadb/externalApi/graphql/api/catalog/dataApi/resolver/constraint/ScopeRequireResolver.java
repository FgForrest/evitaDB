/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.DataChunkFieldHeaderDescriptor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.scope;

/**
 * Resolves {@link io.evitadb.api.query.require.EntityScope} constraint from data chunk output fields
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class ScopeRequireResolver {

	@Nonnull
	public Optional<RequireConstraint> resolve(@Nonnull SelectedField recordField) {
		//noinspection unchecked
		final List<Scope> requestedScopes = (List<Scope>) recordField.getArguments().get(DataChunkFieldHeaderDescriptor.SCOPE.name());
		return Optional.ofNullable(requestedScopes)
			.map(scopes -> scope(requestedScopes.toArray(Scope[]::new)));
	}
}
