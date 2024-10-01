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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint;

import graphql.schema.GraphQLInputType;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link GraphQLConstraintSchemaBuilder} for building filter query tree starting from {@link FilterBy}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class FilterConstraintSchemaBuilder extends GraphQLConstraintSchemaBuilder {

	public FilterConstraintSchemaBuilder(@Nonnull GraphQLConstraintSchemaBuildingContext constraintSchemaBuildingCtx) {
		super(
			constraintSchemaBuildingCtx,
			createHashMap(0), // currently, we don't support any filter constraint with additional children
			Set.of(),
			Set.of(FilterBy.class, FilterGroupBy.class)
		);
	}

	@Nonnull
	public GraphQLInputType build(@Nonnull String rootEntityType) {
		return build(new EntityDataLocator(new ManagedEntityTypePointer(rootEntityType)));
	}

	@Nonnull
	@Override
	protected ConstraintType getConstraintType() {
		return ConstraintType.FILTER;
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(FilterBy.class);
	}

	@Nonnull
	@Override
	protected String getContainerObjectTypeName() {
		return "FilterContainer";
	}

	@Nonnull
	@Override
	protected Predicate<AttributeSchemaContract> getAttributeSchemaFilter() {
		return attributeSchema -> attributeSchema.isUnique() || attributeSchema.isFilterable();
	}
}
