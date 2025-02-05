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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link HeadConstraint} usually with {@link Head}
 * as root container.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class HeadConstraintResolver extends GraphQLConstraintResolver<HeadConstraint> {

	public HeadConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema) {
		super(
			catalogSchema,
			createHashMap(0) // currently, we don't support any head constraint with additional children
		);
	}

	@Nullable
	public HeadConstraint resolve(@Nonnull String rootEntityType, @Nonnull String key, @Nullable Object value) {
		return resolve(
			new GenericDataLocator(new ManagedEntityTypePointer(rootEntityType)),
			key,
			value
		);
	}

	@Override
	protected Class<HeadConstraint> getConstraintClass() {
		return HeadConstraint.class;
	}

	@Override
	@Nonnull
	protected ConstraintType getConstraintType() {
		return ConstraintType.HEAD;
	}

	@Nonnull
	@Override
	protected Optional<ConstraintDescriptor> getWrapperContainer() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(Head.class);
	}
}
