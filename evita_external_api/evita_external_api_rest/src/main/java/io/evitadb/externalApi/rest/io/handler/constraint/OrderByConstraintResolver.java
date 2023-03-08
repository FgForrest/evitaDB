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

package io.evitadb.externalApi.rest.io.handler.constraint;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.SchemaUtils;
import io.evitadb.externalApi.rest.io.handler.CollectionRestHandlingContext;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link OrderConstraint} usually with {@link OrderBy}
 * as root container.
 *
 * It doesn't use any `wrapper container` as `order` constraints don't have any `default` container to use.
 * Also, currently, all `order` container have unique children in GraphQL formatted constraints thanks to the
 * classifier presents in keys.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OrderByConstraintResolver extends RestConstraintResolver<OrderConstraint> {

	public OrderByConstraintResolver(@Nonnull CollectionRestHandlingContext restHandlingContext, @Nonnull Operation operation) {
		super(restHandlingContext, operation);
	}

	@Override
	protected Class<OrderConstraint> getConstraintClass() {
		return OrderConstraint.class;
	}

	@Override
	@Nonnull
	protected ConstraintType getConstraintType() {
		return ConstraintType.ORDER;
	}

	@Override
	@Nonnull
	protected ConstraintDescriptor getWrapperContainer() {
		throw new RestInternalError("Wrapper container is not supported for `order` constraints.");
	}

	@Nonnull
	@Override
	protected DataLocator getRootDataLocator() {
		return new EntityDataLocator(restHandlingContext.getEntityType());
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getRootConstraintContainerDescriptor() {
		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(OrderBy.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new OpenApiBuildingError("Could not find `orderBy` order query.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new OpenApiBuildingError(
				"There multiple variants of `orderBy` order query, cannot decide which to choose."
			)
		);
		return descriptors.iterator().next();
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		return true;
	}

	@Override
	protected Schema getSchemaFromOperationProperty(@Nonnull String propertyName) {
		return SchemaUtils.getSchemaFromOrderBy(restHandlingContext.getOpenApi(), operation, propertyName);
	}
}
