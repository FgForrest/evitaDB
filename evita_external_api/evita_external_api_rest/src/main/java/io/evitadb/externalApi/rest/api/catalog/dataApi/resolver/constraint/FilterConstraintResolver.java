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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Operation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link FilterConstraint} usually with {@link FilterBy}
 * as root container.
 *
 * It uses {@link And} as `wrapper container`. On top of the problem with wrapper container, it can also be used as
 * shortcut for ANDing constraints together implicitly without explicit AND query.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FilterConstraintResolver extends RestConstraintResolver<FilterConstraint> {
	@Getter
	@Nonnull
	private final ConstraintDescriptor wrapperContainer;

	public FilterConstraintResolver(@Nonnull CollectionRestHandlingContext restHandlingContext, @Nonnull Operation operation) {
		super(restHandlingContext, operation);

		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(And.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new RestQueryResolvingInternalError("Could not find `and` filter constraint for wrapper container.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new RestQueryResolvingInternalError(
				"There multiple variants of `and` filter constraint, cannot decide which to choose for wrapper container."
			)
		);
		wrapperContainer = descriptors.iterator().next();
	}

	@Override
	protected Class<FilterConstraint> getConstraintClass() {
		return FilterConstraint.class;
	}

	@Nonnull
	@Override
	protected ConstraintType getConstraintType() {
		return ConstraintType.FILTER;
	}

	@Nonnull
	@Override
	protected DataLocator getRootDataLocator() {
		return new EntityDataLocator(restHandlingContext.getEntityType());
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getRootConstraintContainerDescriptor() {
		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(FilterBy.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new OpenApiBuildingError("Could not find `filterBy` filter query.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new OpenApiBuildingError("There multiple variants of `filterBy` filter query, cannot decide which to choose.")
		);
		return descriptors.iterator().next();
	}
}
