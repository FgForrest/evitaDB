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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.AttributeSetExact;
import io.evitadb.api.query.order.AttributeSetInFilter;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstraintDescriptorProvider}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ConstraintDescriptorProviderTest {

	@Test
	void shouldHaveProcessedConstraints() {
		assertEquals(115, ConstraintDescriptorProvider.getAllConstraints().size());
	}

	@Test
	void shouldCorrectlyDetermineExistenceOfConstraint() {
		assertTrue(ConstraintDescriptorProvider.isKnownConstraint(And.class));
		assertFalse(ConstraintDescriptorProvider.isKnownConstraint(UnknownConstraint.class));
	}

	@Test
	void shouldCorrectlyFindCorrectDescriptorForSpecificConstraint() {
		final ConstraintDescriptor containerDescriptor = ConstraintDescriptorProvider.getConstraint(And.class);
		assertEquals(And.class, containerDescriptor.constraintClass());

		final ConstraintDescriptor leafDescriptor = ConstraintDescriptorProvider.getConstraint(AttributeStartsWith.class);
		assertEquals(AttributeStartsWith.class, leafDescriptor.constraintClass());

		assertThrows(EvitaInternalError.class, () -> ConstraintDescriptorProvider.getConstraint(HierarchyWithin.class));
	}

	@Test
	void shouldCorrectlyFindCorrectDescriptorsForSpecificConstraint() {
		final Set<ConstraintDescriptor> containerDescriptors = ConstraintDescriptorProvider.getConstraints(And.class);
		assertEquals(1, containerDescriptors.size());
		assertEquals(And.class, containerDescriptors.iterator().next().constraintClass());

		final Set<ConstraintDescriptor> leafDescriptor = ConstraintDescriptorProvider.getConstraints(AttributeStartsWith.class);
		assertEquals(1, leafDescriptor.size());
		assertEquals(AttributeStartsWith.class, leafDescriptor.iterator().next().constraintClass());
	}

	@Test
	void shouldFindCorrectDescriptorForSpecificConstraintByImportantMetadata() {
		final ConstraintDescriptor descriptorByBaseName = ConstraintDescriptorProvider.getConstraint(
			ConstraintType.FILTER,
			ConstraintPropertyType.GENERIC,
			"and",
			null
		).get();
		assertEquals(And.class, descriptorByBaseName.constraintClass());

		final ConstraintDescriptor descriptorByFullName = ConstraintDescriptorProvider.getConstraint(
			ConstraintType.FILTER,
			ConstraintPropertyType.HIERARCHY,
			"withinSelf",
			null
		).get();
		assertEquals(HierarchyWithin.class, descriptorByFullName.constraintClass());

		final ConstraintDescriptor descriptorByNameAndClassifier = ConstraintDescriptorProvider.getConstraint(
			ConstraintType.FILTER,
			ConstraintPropertyType.ATTRIBUTE,
			"startsWith",
			"code"
		).get();
		assertEquals(AttributeStartsWith.class, descriptorByNameAndClassifier.constraintClass());

		final ConstraintDescriptor descriptorByNameAndClassifier2 = ConstraintDescriptorProvider.getConstraint(
			ConstraintType.REQUIRE,
			ConstraintPropertyType.FACET,
			"summary",
			null
		).get();
		assertEquals(FacetSummary.class, descriptorByNameAndClassifier2.constraintClass());

		final ConstraintDescriptor descriptorByNameAndClassifier3 = ConstraintDescriptorProvider.getConstraint(
			ConstraintType.REQUIRE,
			ConstraintPropertyType.FACET,
			"summary",
			"parameter"
		).get();
		assertEquals(FacetSummaryOfReference.class, descriptorByNameAndClassifier3.constraintClass());
	}

	@Test
	void shouldFindAllConstraintsForSpecificType() {
		assertEquals(43, ConstraintDescriptorProvider.getConstraints(ConstraintType.FILTER).size());
		assertEquals(21, ConstraintDescriptorProvider.getConstraints(ConstraintType.ORDER).size());
	}

	@Test
	void shouldFindAllConstraintForSpecificTypeAndPropertyType() {
		assertEquals(
			4,
			ConstraintDescriptorProvider.getConstraints(
				ConstraintType.FILTER,
				ConstraintPropertyType.HIERARCHY,
				ConstraintDomain.ENTITY
			).size()
		);
	}

	@Test
	void shouldFindAllConstraintForSpecificTypeAndPropertyTypeAndSupportedValue() {
		final Set<ConstraintDescriptor> stringConstraints = ConstraintDescriptorProvider.getConstraints(
			ConstraintType.FILTER,
			ConstraintPropertyType.ATTRIBUTE,
			ConstraintDomain.ENTITY,
			String.class,
			false,
			true
		);
		assertEquals(
			List.of(
				AttributeBetween.class,
				AttributeContains.class,
				AttributeEndsWith.class,
				AttributeEquals.class,
				AttributeGreaterThan.class,
				AttributeGreaterThanEquals.class,
				AttributeInSet.class,
				AttributeIs.class,
				AttributeLessThan.class,
				AttributeLessThanEquals.class,
				AttributeStartsWith.class
			),
			stringConstraints.stream()
				.map(ConstraintDescriptor::constraintClass)
				.sorted(Comparator.comparing(Class::getSimpleName))
				.toList()
		);

		assertTrue(
			ConstraintDescriptorProvider.getConstraints(
				ConstraintType.FILTER,
				ConstraintPropertyType.ATTRIBUTE,
				ConstraintDomain.HIERARCHY,
				String.class,
				false,
				false
			).isEmpty()
		);

		final Set<ConstraintDescriptor> constraintsWithoutArraySupportRequired = ConstraintDescriptorProvider.getConstraints(
			ConstraintType.ORDER,
			ConstraintPropertyType.ATTRIBUTE,
			ConstraintDomain.ENTITY,
			String.class,
			false,
			false
		);
		assertEquals(
			List.of(
				AttributeNatural.class,
				AttributeSetExact.class,
				AttributeSetInFilter.class
			),
			constraintsWithoutArraySupportRequired.stream()
				.map(ConstraintDescriptor::constraintClass)
				.sorted(Comparator.comparing(Class::getSimpleName))
				.toList()
		);

		assertTrue(
			ConstraintDescriptorProvider.getConstraints(
				ConstraintType.ORDER,
				ConstraintPropertyType.ATTRIBUTE,
				ConstraintDomain.ENTITY,
				String.class,
				true,
				false
			).isEmpty()
		);
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint.",
		userDocsLink = "/link"
	)
	private static class UnknownConstraint extends ConstraintLeaf<FilterConstraint> implements FilterConstraint, AttributeConstraint<FilterConstraint> {

		@Creator
		public UnknownConstraint() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}

		@Nonnull
		@Override
		public Class<FilterConstraint> getType() {
			return FilterConstraint.class;
		}

		@Override
		public boolean isApplicable() {
			return isArgumentsNonNull() && getArguments().length > 0;
		}

		@Override
		public void accept(@Nonnull ConstraintVisitor visitor) {
			visitor.visit(this);
		}
	}
}
