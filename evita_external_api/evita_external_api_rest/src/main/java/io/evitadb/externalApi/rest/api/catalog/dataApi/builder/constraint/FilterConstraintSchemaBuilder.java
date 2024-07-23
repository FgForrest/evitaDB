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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link OpenApiConstraintSchemaBuilder} for building filter constraint tree starting from {@link FilterBy}.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FilterConstraintSchemaBuilder extends OpenApiConstraintSchemaBuilder {

	private static final Set<Class<? extends Constraint<?>>> FORBIDDEN_CONSTRAINTS = Set.of(FilterBy.class, FilterGroupBy.class);
	private static final Set<Class<? extends Constraint<?>>> FORBIDDEN_CONSTRAINTS_WITH_LOCALE = Set.of(FilterBy.class, FilterGroupBy.class, EntityLocaleEquals.class);

	public FilterConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
	                                     boolean forbidLocaleInQuery) {
		super(
			constraintSchemaBuildingCtx,
			createHashMap(0), // currently, we don't support any filter constraint with additional children
			Set.of(),
			forbidLocaleInQuery?FORBIDDEN_CONSTRAINTS_WITH_LOCALE:FORBIDDEN_CONSTRAINTS
		);
	}

	@Nonnull
	public OpenApiSimpleType build(@Nonnull String rootEntityType) {
		return build(new EntityDataLocator(rootEntityType));
	}

	@Nonnull
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
