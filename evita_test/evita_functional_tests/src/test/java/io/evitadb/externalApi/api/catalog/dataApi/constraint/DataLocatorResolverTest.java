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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DataLocatorResolver}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DataLocatorResolverTest {

	private static final String EXTERNAL_ENTITY_TAG = "TAG";

	private Map<String, EntitySchemaContract> entitySchemaIndex;
	private CatalogSchemaContract catalogSchema;
	private DataLocatorResolver dataLocatorResolver;

	protected static Stream<Arguments> possibleParentDataLocatorsWithDesiredChildDomains() {
		return Stream.of(
			// 1. parent data locator; 2. desired child domain; 3. expected child data locator

			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.DEFAULT, new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.SEGMENT, new SegmentDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY, new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY)),

			// from basic reference to entity
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), EXTERNAL_ENTITY_TAG), ConstraintDomain.ENTITY, new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG))),

			// from basic inline reference to entity
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), EXTERNAL_ENTITY_TAG), ConstraintDomain.ENTITY, new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG))),

			// from hierarchy to entity
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY)), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),

			// from hierarchy to hierarchy target
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY)), ConstraintDomain.HIERARCHY_TARGET, new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY_TARGET, new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY)),

			// from facet to entity
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER)), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER))),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER), ConstraintDomain.ENTITY, new EntityDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER))),

			// switch between data locators with references
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.REFERENCE, new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY)),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER), ConstraintDomain.REFERENCE, new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER)),

			// generic can be created from any parent
			Arguments.of(new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new EntityDataLocator(new ExternalEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.GENERIC, new GenericDataLocator(new ExternalEntityTypePointer(Entities.PRODUCT))),
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY)), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER)), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER))),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER), ConstraintDomain.GENERIC, new GenericDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER)))
		);
	}

	protected static Stream<Arguments> impossibleChildDomainsForParentDataLocators() {
		return Stream.of(
			// 1. parent data locator; 2. desired child domain

			// there is no hierarchy in parent
			Arguments.of(new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG)), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.HIERARCHY_TARGET),
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY_TARGET),

			// there is no reference specified in parent
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.REFERENCE),
			// there is no hierarchy specified in parent
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY)), ConstraintDomain.HIERARCHY),
			// there is no facet specified in parent
			Arguments.of(new EntityDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER)), ConstraintDomain.FACET),
			// there is no reference specified in parent
			Arguments.of(new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)), ConstraintDomain.REFERENCE),
			// there is no hierarchy specified in parent
			Arguments.of(new GenericDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY)), ConstraintDomain.HIERARCHY),
			// there is no facet specified in parent
			Arguments.of(new GenericDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER)), ConstraintDomain.FACET),
			// there is no reference specified in parent
			Arguments.of(new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG)), ConstraintDomain.REFERENCE),
			// there is no hierarchy specified in parent
			Arguments.of(new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG)), ConstraintDomain.HIERARCHY),
			// there is no facet specified in parent
			Arguments.of(new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG)), ConstraintDomain.FACET),
			// we don't know if hierarchy is faceted
			Arguments.of(new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.FACET),
			// we don't know if facet is hierarchical
			Arguments.of(new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY),
			// we don't know if reference is facet
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.FACET),
			// we don't know if reference is facet
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.FACET),
			// we don't know if reference is hierarchy
			Arguments.of(new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY),
			// we don't know if reference is hierarchy
			Arguments.of(new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY), ConstraintDomain.HIERARCHY)
		);
	}

	protected static Stream<Arguments> possibleParentDataLocatorsWithChildConstraints() {
		return Stream.of(
			// 1. parent data locator; 2. child constraint descriptor; 3. classifier for child constraint; 4. expected child data locator

			// should inherit parent's data locator
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(And.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(AttributeEquals.class),
				ATTRIBUTE_CODE,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraints(AssociatedDataContent.class)
					.stream()
					.filter(it -> it.fullName().equals("content"))
					.findFirst()
					.orElseThrow(),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(PriceBetween.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),
			Arguments.of(
				new SegmentDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(And.class),
				null,
				new SegmentDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),

			// should change data locator to reference
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND,
				new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.BRAND)
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(FacetHaving.class),
				Entities.PARAMETER,
				new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER)
			),
			Arguments.of(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(FacetGroupsConjunction.class),
				Entities.PARAMETER,
				new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER)
			),
			Arguments.of(
				new SegmentDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND,
				new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.BRAND)
			),

			// should change data locator to hierarchy
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraints(HierarchyWithin.class)
					.stream()
					.filter(it -> it.fullName().equals("within"))
					.findFirst()
					.orElseThrow(),
				Entities.CATEGORY,
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY)
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraints(HierarchyWithin.class)
					.stream()
					.filter(it -> it.fullName().equals("withinSelf"))
					.findFirst()
					.orElseThrow(),
				null,
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT))
			),

			// should inherit hierarchy data locator
			Arguments.of(
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(HierarchyExcluding.class),
				null,
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY)
			),

			// should switch from reference to entity
			Arguments.of(
				new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))
			),
			Arguments.of(
				new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))
			),
			Arguments.of(
				new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), EXTERNAL_ENTITY_TAG),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG))
			),
			Arguments.of(
				new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), EXTERNAL_ENTITY_TAG),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG))
			),
			Arguments.of(
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.CATEGORY))
			),
			Arguments.of(
				new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.PARAMETER),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PARAMETER))
			),
			Arguments.of(
				new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), EXTERNAL_ENTITY_TAG),
				ConstraintDescriptorProvider.getConstraint(EntityHaving.class),
				null,
				new EntityDataLocator(new ExternalEntityTypePointer(EXTERNAL_ENTITY_TAG))
			)
		);
	}

	protected static Stream<Arguments> impossibleChildConstraintsForParentDataLocators() {
		return Stream.of(
			// 1. parent data locator; 2. child constraint descriptor; 3. classifier for child constraint

			// cannot have another reference in other reference
			Arguments.of(
				new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND
			),
			Arguments.of(
				new InlineReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND
			),
			Arguments.of(
				new HierarchyDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND
			),
			Arguments.of(
				new FacetDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY),
				ConstraintDescriptorProvider.getConstraint(ReferenceHaving.class),
				Entities.BRAND
			),

			// missing required classifier
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraints(HierarchyWithin.class)
					.stream()
					.filter(it -> it.fullName().equals("within"))
					.findFirst()
					.orElseThrow(),
				null
			),
			Arguments.of(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				ConstraintDescriptorProvider.getConstraint(FacetHaving.class),
				null
			)
		);
	}

	@BeforeEach
	void setUp() {
		this.entitySchemaIndex = new HashMap<>();
		this.catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			Map.of(),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return DataLocatorResolverTest.this.entitySchemaIndex.values();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return ofNullable(DataLocatorResolverTest.this.entitySchemaIndex.get(entityType));
				}
			}
		);

		final EntitySchemaContract productSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withPrice()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE, thatIs -> thatIs.withAttribute(ATTRIBUTE_CODE, String.class))
			.withReferenceToEntity(Entities.PARAMETER, Entities.PARAMETER, Cardinality.ONE_OR_MORE)
			.withReferenceTo(EXTERNAL_ENTITY_TAG, EXTERNAL_ENTITY_TAG, Cardinality.EXACTLY_ONE)
			.toInstance();

		this.entitySchemaIndex.put(Entities.PRODUCT, productSchema);

		final EntitySchemaContract categorySchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.CATEGORY)
		)
			.withPrice()
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.toInstance();
		this.entitySchemaIndex.put(Entities.CATEGORY, categorySchema);

		final EntitySchemaContract parameterSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.PARAMETER)
		)
			.toInstance();
		this.entitySchemaIndex.put(Entities.PARAMETER, parameterSchema);

		this.dataLocatorResolver = new DataLocatorResolver(this.catalogSchema);
	}

	@ParameterizedTest
	@MethodSource("possibleParentDataLocatorsWithDesiredChildDomains")
	void shouldResolveChildParameterDataLocator(
		@Nonnull DataLocator parentDataLocator,
		@Nonnull ConstraintDomain desiredChildDomain,
		@Nonnull DataLocator expectedChildDataLocator
	) {
		assertEquals(
			expectedChildDataLocator,
			this.dataLocatorResolver.resolveChildParameterDataLocator(parentDataLocator, desiredChildDomain).get()
		);
	}

	@ParameterizedTest
	@MethodSource("impossibleChildDomainsForParentDataLocators")
	void shouldNotResolveChildParameterDataLocator(
		@Nonnull DataLocator parentDataLocator,
		@Nonnull ConstraintDomain desiredChildDomain
	) {
		assertThrows(
			ExternalApiInternalError.class,
			() -> this.dataLocatorResolver.resolveChildParameterDataLocator(parentDataLocator, desiredChildDomain)
		);
	}

	@ParameterizedTest
	@MethodSource("possibleParentDataLocatorsWithChildConstraints")
	void shouldResolveConstraintChildDataLocator(
		@Nonnull DataLocator parentDataLocator,
		@Nonnull ConstraintDescriptor constraintDescriptor,
		@Nullable String classifier,
		@Nonnull DataLocator expectedChildDataLocator
	) {
		assertEquals(
			expectedChildDataLocator,
			this.dataLocatorResolver.resolveConstraintDataLocator(parentDataLocator, constraintDescriptor, classifier)
		);
	}

	@ParameterizedTest
	@MethodSource("impossibleChildConstraintsForParentDataLocators")
	void shouldNotResolveConstraintDataLocator(
		@Nonnull DataLocator parentDataLocator,
		@Nonnull ConstraintDescriptor constraintDescriptor,
		@Nullable String classifier
	) {
		assertThrows(
			ExternalApiInternalError.class,
			() -> this.dataLocatorResolver.resolveConstraintDataLocator(parentDataLocator, constraintDescriptor, classifier)
		);
	}
}
