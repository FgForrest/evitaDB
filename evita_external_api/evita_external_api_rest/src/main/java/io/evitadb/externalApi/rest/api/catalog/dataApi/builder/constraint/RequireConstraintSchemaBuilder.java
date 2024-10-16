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
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Implementation of {@link ConstraintSchemaBuilder} for building require query tree starting from {@link Require}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class RequireConstraintSchemaBuilder extends OpenApiConstraintSchemaBuilder {

	public static final Set<Class<? extends Constraint<?>>> ALLOWED_CONSTRAINTS_FOR_LIST = Set.of(
		EntityFetch.class,
		AssociatedDataContent.class,
		AttributeContent.class,
		DataInLocales.class,
		PriceContent.class,
		ReferenceContent.class,
		HierarchyContent.class,
		HierarchyDistance.class,
		HierarchyLevel.class,
		HierarchyNode.class,
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		Page.class,
		Spacing.class,
		SpacingGap.class,
		PriceType.class,
		Strip.class
	);

	public static final Set<Class<? extends Constraint<?>>> ALLOWED_CONSTRAINTS_FOR_UPSERT = Set.of(
		EntityFetch.class,
		AssociatedDataContent.class,
		AttributeContent.class,
		HierarchyContent.class,
		HierarchyDistance.class,
		HierarchyLevel.class,
		HierarchyNode.class,
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
		HierarchyContent.class,
		HierarchyDistance.class,
		HierarchyLevel.class,
		HierarchyNode.class,
		Strip.class,
		Page.class
	);

	private static final Set<Class<? extends Constraint<?>>> REQUIRED_FORBIDDEN = Set.of(Require.class);

	public RequireConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
	                                      @Nonnull AtomicReference<FilterConstraintSchemaBuilder> filterConstraintSchemaBuilder,
	                                      @Nonnull AtomicReference<OrderConstraintSchemaBuilder> orderConstraintSchemaBuilder) {
		this(
			constraintSchemaBuildingCtx,
			Set.of(),
			filterConstraintSchemaBuilder,
			orderConstraintSchemaBuilder
		);
	}

	@Nonnull
	public OpenApiSimpleType build(@Nonnull String rootEntityType) {
		return build(new GenericDataLocator(new ManagedEntityTypePointer(rootEntityType)));
	}

	public RequireConstraintSchemaBuilder(@Nonnull OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx,
	                                      @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                      @Nonnull AtomicReference<FilterConstraintSchemaBuilder> filterConstraintSchemaBuilder,
	                                      @Nonnull AtomicReference<OrderConstraintSchemaBuilder> orderConstraintSchemaBuilder) {
		super(
			constraintSchemaBuildingCtx,
			Map.of(
				ConstraintType.FILTER, filterConstraintSchemaBuilder,
				ConstraintType.ORDER, orderConstraintSchemaBuilder
			),
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
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(Require.class);
	}

	@Nonnull
	@Override
	protected String getContainerObjectTypeName() {
		return "RequireContainer";
	}

	@Nonnull
	@Override
	protected Predicate<AttributeSchemaContract> getAttributeSchemaFilter() {
		return attributeSchema -> true;
	}
}
