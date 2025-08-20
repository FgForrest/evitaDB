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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
public class OrderConstraintResolver extends RestConstraintResolver<OrderConstraint> {

	public OrderConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema,
	                               @Nonnull AtomicReference<FilterConstraintResolver> filterConstraintResolver) {
		super(
			catalogSchema,
			Map.of(ConstraintType.FILTER, filterConstraintResolver)
		);
	}

	@Nullable
	public OrderConstraint resolve(@Nonnull String rootEntityType, @Nonnull String key, @Nullable Object value) {
		return resolve(
			new EntityDataLocator(new ManagedEntityTypePointer(rootEntityType)),
			key,
			value
		);
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
	protected Optional<ConstraintDescriptor> getWrapperContainer() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(OrderBy.class);
	}
}
