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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for building require query tree starting from {@link Require}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class RequireSchemaBuilder extends OpenApiConstraintSchemaBuilder {
	public static final Set<Class<? extends Constraint<?>>> ALLOWED_CONSTRAINTS_FOR_LIST = Set.of(
		EntityFetch.class,
		AssociatedDataContent.class,
		AttributeContent.class,
		DataInLocales.class,
		PriceContent.class,
		ReferenceContent.class,
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		Page.class,
		PriceType.class,
		Strip.class
	);

	public static final Set<Class<? extends Constraint<?>>> ALLOWED_CONSTRAINTS_FOR_UPSERT = Set.of(
		EntityFetch.class,
		AssociatedDataContent.class,
		AttributeContent.class,
		DataInLocales.class,
		PriceContent.class,
		ReferenceContent.class
	);

	public static final Set<Class<? extends Constraint<?>>> ALLOWED_CONSTRAINTS_FOR_DELETE = Set.of(
		EntityFetch.class,
		AssociatedDataContent.class,
		AttributeContent.class,
		DataInLocales.class,
		PriceContent.class,
		ReferenceContent.class,
		Strip.class,
		Page.class
	);

	private static final Set<Class<? extends Constraint<?>>> REQUIRED_FORBIDDEN = Set.of(Require.class);

	public RequireSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
								@Nonnull String rootEntityType) {
		this(
			constraintSchemaBuildingCtx,
			rootEntityType,
			Set.of()
		);
	}

	public RequireSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
	                            @Nonnull String rootEntityType,
	                            @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints) {
		super(
			constraintSchemaBuildingCtx,
			rootEntityType,
			allowedConstraints,
			REQUIRED_FORBIDDEN
		);
	}

	@Nonnull
	@Override
	protected ConstraintType getConstraintType() {
		return ConstraintType.REQUIRE;
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getRootConstraintContainerDescriptor() {
		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(Require.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new OpenApiBuildingError("Could not find `require` require query.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new OpenApiBuildingError(
				"There multiple variants of `require` require query, cannot decide which to choose."
			)
		);
		return descriptors.iterator().next();
	}

	@Nonnull
	@Override
	protected DataLocator getRootDataLocator() {
		return new GenericDataLocator(rootEntityType);
	}

	@Nonnull
	@Override
	protected String getContainerObjectTypeName() {
		return "RequireContainer_";
	}

	@Nonnull
	@Override
	protected Predicate<AttributeSchemaContract> getAttributeSchemaFilter() {
		return attributeSchema -> true;
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		// We don't want list of wrapper container because in "require" constraints there are no generic conjunction
		// containers (and also there is currently no need to support that). Essentially, we want require constraints
		// with children to act as if they were `ChildParameterDescriptor#uniqueChildren`.
		return true;
	}
}
