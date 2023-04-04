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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for building order query tree starting from {@link OrderBy}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class OrderConstraintSchemaBuilder extends OpenApiConstraintSchemaBuilder {

	public OrderConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
	                                    @Nonnull String rootEntityType) {
		super(
			constraintSchemaBuildingCtx,
			rootEntityType,
			Set.of(),
			Set.of(OrderBy.class)
		);
	}

	@Nonnull
	@Override
	protected ConstraintType getConstraintType() {
		return ConstraintType.ORDER;
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

	@Nonnull
	@Override
	protected DataLocator getRootDataLocator() {
		return new EntityDataLocator(rootEntityType);
	}

	@Nonnull
	@Override
	protected String getContainerObjectTypeName() {
		return "OrderContainer";
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		// We don't want list of wrapper container because in "order" constraints there are no generic conjunction
		// containers (and also there is currently no need to support that). Essentially, we want order constraints
		// with children to act as if they were `ChildParameterDescriptor#uniqueChildren` as, although they are
		// originally not, in case of GraphQL where classifiers are in keys those fields are in fact unique children.
		return true;
	}

	@Nonnull
	@Override
	protected Predicate<AttributeSchemaContract> getAttributeSchemaFilter() {
		return AttributeSchemaContract::isSortable;
	}
}
