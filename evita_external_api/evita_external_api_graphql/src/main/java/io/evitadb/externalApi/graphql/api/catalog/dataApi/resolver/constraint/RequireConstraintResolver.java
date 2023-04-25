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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link RequireConstraint} usually with {@link io.evitadb.api.query.require.Require}
 * as root container.
 *
 * It doesn't use any `wrapper container` as `require` constraints don't have any `default` container to use and it is
 * not needed.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RequireConstraintResolver extends GraphQLConstraintResolver<RequireConstraint> {

	public RequireConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema,
	                                 @Nonnull AtomicReference<FilterConstraintResolver> filterConstraintResolver) {
		super(
			catalogSchema,
			Map.of(
				ConstraintType.FILTER, filterConstraintResolver
			)
		);
	}

	@Nullable
	public RequireConstraint resolve(@Nonnull String rootEntityType, @Nonnull String key, @Nullable Object value) {
		return resolve(
			new GenericDataLocator(rootEntityType),
			key,
			value
		);
	}

	@Override
	protected Class<RequireConstraint> getConstraintClass() {
		return RequireConstraint.class;
	}

	@Override
	@Nonnull
	protected ConstraintType getConstraintType() {
		return ConstraintType.REQUIRE;
	}

	@Override
	@Nonnull
	protected ConstraintDescriptor getWrapperContainer() {
		throw new GraphQLInternalError("Wrapper container is not supported for `require` constraints.");
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(Require.class);
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		// We don't want list of wrapper container because in "require" constraints there are no generic conjunction
		// containers (and also there is currently no need to support that). Essentially, we want require constraints
		// with children to act as if they were `ChildParameterDescriptor#uniqueChildren` as, although they are
		// originally not, in case of GraphQL where classifiers are in keys those fields are in fact unique children.
		return true;
	}
}
