/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createHashMap;

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

	@Nonnull private final ConstraintDescriptor wrapperContainer;

	public FilterConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema) {
		super(
			catalogSchema,
			createHashMap(0) // currently, we don't support any filter constraint with additional children
		);
		this.wrapperContainer = ConstraintDescriptorProvider.getConstraint(And.class);
	}

	@Nullable
	public FilterConstraint resolve(@Nonnull String rootEntityType, @Nonnull String key, @Nullable Object value) {
		return resolve(
			new EntityDataLocator(new ManagedEntityTypePointer(rootEntityType)),
			key,
			value
		);
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
	protected Optional<ConstraintDescriptor> getWrapperContainer() {
		return Optional.of(this.wrapperContainer);
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(FilterBy.class);
	}
}
