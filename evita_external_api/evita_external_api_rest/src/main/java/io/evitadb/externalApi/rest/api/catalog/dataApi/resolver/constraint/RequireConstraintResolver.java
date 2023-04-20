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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link RequireConstraint} usually with {@link io.evitadb.api.query.require.Require}
 * as root container.
 *
 * It doesn't use any `wrapper container` as `require` constraints don't have any `default` container to use, and it is
 * not needed.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RequireConstraintResolver extends RestConstraintResolver<RequireConstraint> {

	public RequireConstraintResolver(@Nonnull CollectionRestHandlingContext restHandlingContext,
	                                 @Nonnull AtomicReference<FilterConstraintResolver> filterConstraintResolver,
									 @Nonnull AtomicReference<OrderConstraintResolver> orderConstraintResolver) {
		super(
			restHandlingContext,
			Map.of(
				ConstraintType.FILTER, filterConstraintResolver,
				ConstraintType.ORDER, orderConstraintResolver
			)
		);
	}

	@Nullable
	public RequireConstraint resolve(@Nonnull String key, @Nullable Object value) {
		return resolve(
			new GenericDataLocator(restHandlingContext.getEntityType()),
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
		throw new RestInternalError("Wrapper container is not supported for `require` constraints.");
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(Require.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new OpenApiBuildingError("Could not find `require` require query.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new OpenApiBuildingError(
				"There multiple variants of `require` require query, cannot decide which to choose."
			)
		);
		return descriptors.iterator().next();
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		return true;
	}
}
