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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for building order query tree starting from {@link OrderBy}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class OrderConstraintSchemaBuilder extends OpenApiConstraintSchemaBuilder {

	public OrderConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx) {
		super(
			constraintSchemaBuildingCtx,
			createHashMap(0), // currently, we don't support any filter constraint with additional children
			Set.of(),
			Set.of(OrderBy.class, OrderGroupBy.class)
		);
	}

	@Nonnull
	public OpenApiSimpleType build(@Nonnull String rootEntityType) {
		return build(new EntityDataLocator(rootEntityType));
	}

	@Nonnull
	@Override
	protected ConstraintType getConstraintType() {
		return ConstraintType.ORDER;
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(OrderBy.class);
	}

	@Nonnull
	@Override
	protected String getContainerObjectTypeName() {
		return "OrderContainer";
	}

	@Nonnull
	@Override
	protected Predicate<AttributeSchemaContract> getAttributeSchemaFilter() {
		return AttributeSchemaContract::isSortable;
	}
}
