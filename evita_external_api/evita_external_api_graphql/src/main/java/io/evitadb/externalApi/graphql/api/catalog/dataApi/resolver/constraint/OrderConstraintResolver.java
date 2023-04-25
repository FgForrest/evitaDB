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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link OrderConstraint} usually with {@link OrderBy}
 * as root container.
 *
 * It doesn't use any `wrapper container` as `order` constraints don't have any `default` container to use.
 * Also, currently, all `order` container have unique children in GraphQL formatted constraints thanks to the
 * classifier presents in keys.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class OrderConstraintResolver extends GraphQLConstraintResolver<OrderConstraint> {

	public OrderConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema) {
		super(
			catalogSchema,
			createHashMap(0) // currently, we don't support any order constraint with additional children
		);
	}

	@Nullable
	public OrderConstraint resolve(@Nonnull String rootEntityType, @Nonnull String key, @Nullable Object value) {
		return resolve(
			new EntityDataLocator(rootEntityType),
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
	protected ConstraintDescriptor getWrapperContainer() {
		throw new GraphQLInternalError("Wrapper container is not supported for `order` constraints.");
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(OrderBy.class);
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		// We don't want list of wrapper container because in "order" constraints there are no generic conjunction
		// containers (and also there is currently no need to support that). Essentially, we want order constraints
		// with children to act as if they were `ChildParameterDescriptor#uniqueChildren` as, although they are
		// originally not, in case of GraphQL where classifiers are in keys those fields are in fact unique children.
		return true;
	}
}
