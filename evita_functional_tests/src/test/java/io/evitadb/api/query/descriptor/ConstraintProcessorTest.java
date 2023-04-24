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

package io.evitadb.api.query.descriptor;


import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.HierarchySpecificationFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.exception.EvitaInternalError;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ConstraintProcessor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ConstraintProcessorTest {

	@Test
	void shouldCorrectlyProcessConstraints() {
		final Set<ConstraintDescriptor> descriptors = new ConstraintProcessor().process(Set.of(And.class, AttributeStartsWith.class, HierarchyWithin.class));
		assertEquals(
			List.of(
				createAndDescriptor(),
				createAttributeStartsWithDescriptor(),
				createHierarchyWithinDescriptor(),
				createHierarchyWithinSelfDescriptor()
			),
			descriptors.stream().sorted(Comparator.comparing(ConstraintDescriptor::fullName)).toList()
		);
	}

	@Test
	void shouldCorrectlyProcessSimilarConstraints() {
		final Set<ConstraintDescriptor> descriptors1 = new ConstraintProcessor().process(Set.of(
			ConstraintAWithoutSuffix.class,
			ConstraintBWithSuffix.class
		));
		assertEquals(2, descriptors1.size());

		final Set<ConstraintDescriptor> descriptors2 = new ConstraintProcessor().process(Set.of(
			ConstraintWithSameConditionWithoutClassifier.class,
			ConstraintWithSameConditionWithClassifier.class
		));
		assertEquals(2, descriptors2.size());

		final Set<ConstraintDescriptor> descriptors3 = new ConstraintProcessor().process(Set.of(
			ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersA.class,
			ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB.class
		));
		assertEquals(3, descriptors3.size());

		final Set<ConstraintDescriptor> descriptors4 = new ConstraintProcessor().process(Set.of(
			ConstraintWithMultipleAdditionalChildren.class
		));
		assertEquals(1, descriptors4.size());
	}

	@Test
	void shouldNotProcessIncorrectlyDefinedConstraint() {
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(UnannotatedConstraint.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithoutCreator.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithUnannotatedParameters.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithoutType.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithoutPropertyType.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithDuplicateCreators.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithMultipleImplicitClassifiers.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(SimilarConstraintA.class, SimilarConstraintB.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(SimilarConstraintWithSuffixedCreatorsA.class, SimilarConstraintWithSuffixedCreatorsB.class)));
		assertThrows(EvitaInternalError.class, () -> new ConstraintProcessor().process(Set.of(ConstraintWithMultipleSameAdditionalChildren.class)));
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createAndDescriptor() {
		return new ConstraintDescriptor(
			And.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.GENERIC,
			"and",
			"This is a constraint.",
			Set.of(ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE),
			null,
			new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(
					new ChildParameterDescriptor(
						"children",
						FilterConstraint[].class,
						true,
						ConstraintDomain.DEFAULT,
						false,
						Set.of(),
						Set.of()
					)
				),
				null
			)
		);
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createAttributeStartsWithDescriptor() {
		return new ConstraintDescriptor(
			AttributeStartsWith.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.ATTRIBUTE,
			"startsWith",
			"This is a constraint.",
			Set.of(ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE),
			new SupportedValues(
				Set.of(String.class),
				true
			),
			new ConstraintCreator(
				AttributeStartsWith.class.getConstructor(String.class, String.class),
				List.of(
					new ClassifierParameterDescriptor("attributeName"),
					new ValueParameterDescriptor(
						"textToSearch",
						String.class,
						true,
						false
					)
				),
				null
			)
		);
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createHierarchyWithinDescriptor() {
		return new ConstraintDescriptor(
			HierarchyWithin.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.HIERARCHY,
			"within",
			"This is a constraint.",
			Set.of(ConstraintDomain.ENTITY),
			null,
			new ConstraintCreator(
				HierarchyWithin.class.getConstructor(String.class, Integer.class, HierarchySpecificationFilterConstraint[].class),
				List.of(
					new ClassifierParameterDescriptor("referenceName"),
					new ValueParameterDescriptor(
						"ofParent",
						Integer.class,
						true,
						false
					),
					new ChildParameterDescriptor(
						"with",
						HierarchySpecificationFilterConstraint[].class,
						true,
						ConstraintDomain.DEFAULT,
						true,
						Set.of(),
						Set.of()
					)
				),
				null
			)
		);
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createHierarchyWithinSelfDescriptor() {
		return new ConstraintDescriptor(
			HierarchyWithin.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.HIERARCHY,
			"withinSelf",
			"This is a constraint.",
			Set.of(ConstraintDomain.ENTITY),
			null,
			new ConstraintCreator(
				HierarchyWithin.class.getConstructor(Integer.class, HierarchySpecificationFilterConstraint[].class),
				List.of(
					new ValueParameterDescriptor(
						"ofParent",
						Integer.class,
						true,
						false
					),
					new ChildParameterDescriptor(
						"with",
						HierarchySpecificationFilterConstraint[].class,
						true,
						ConstraintDomain.DEFAULT,
						true,
						Set.of(),
						Set.of()
					)
				),
				new SilentImplicitClassifier()
			)
		);
	}

	private static class UnannotatedConstraint extends AbstractAttributeFilterConstraintLeaf {

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithoutCreator extends AbstractAttributeFilterConstraintLeaf {

		public ConstraintWithoutCreator() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithoutType implements Constraint<ConstraintWithoutType> {

		@Nonnull
		@Override
		public String getName() {
			return null;
		}

		@Nonnull
		@Override
		public Class<ConstraintWithoutType> getType() {
			return ConstraintWithoutType.class;
		}

		@Nonnull
		@Override
		public Serializable[] getArguments() {
			return new Serializable[0];
		}

		@Override
		public boolean isApplicable() {
			return false;
		}

		@Override
		public void accept(@Nonnull ConstraintVisitor visitor) {

		}

		@Nonnull
		@Override
		public String toString() {
			return null;
		}

		@Nonnull
		@Override
		public ConstraintWithoutType cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithoutPropertyType implements FilterConstraint {

		@Nonnull
		@Override
		public String getName() {
			return null;
		}

		@Nonnull
		@Override
		public Class<FilterConstraint> getType() {
			return FilterConstraint.class;
		}

		@Nonnull
		@Override
		public Serializable[] getArguments() {
			return new Serializable[0];
		}

		@Override
		public boolean isApplicable() {
			return false;
		}

		@Override
		public void accept(@Nonnull ConstraintVisitor visitor) {

		}

		@Nonnull
		@Override
		public String toString() {
			return null;
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithUnannotatedParameters extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintWithUnannotatedParameters(@Nonnull Long value) {
			super(value);
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintA extends AbstractAttributeFilterConstraintLeaf {

		@Creator(implicitClassifier = "primaryKey")
		public SimilarConstraintA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintB extends AbstractAttributeFilterConstraintLeaf {

		@Creator(implicitClassifier = "primaryKey")
		public SimilarConstraintB() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintWithSuffixedCreatorsA extends AbstractAttributeFilterConstraintLeaf {

		@Creator(implicitClassifier = "primaryKey", suffix = "other")
		public SimilarConstraintWithSuffixedCreatorsA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintWithSuffixedCreatorsB extends AbstractAttributeFilterConstraintLeaf {

		@Creator(implicitClassifier = "primaryKey", suffix = "other")
		public SimilarConstraintWithSuffixedCreatorsB() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionWithoutClassifier extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintWithSameConditionWithoutClassifier() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionWithClassifier extends AbstractAttributeFilterConstraintLeaf {

		@Creator(implicitClassifier = "primaryKey")
		public ConstraintWithSameConditionWithClassifier() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersA extends AbstractAttributeFilterConstraintLeaf {

		@Creator(suffix = "other")
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB() {
		}

		@Creator(suffix = "other")
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB(@Nonnull @Classifier String classifier) {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithDuplicateCreators extends AbstractAttributeFilterConstraintLeaf {

		@Creator(suffix = "other")
		public ConstraintWithDuplicateCreators() {
		}

		@Creator(suffix = "other")
		public ConstraintWithDuplicateCreators(@Nonnull @Value String value) {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintAWithoutSuffix extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintAWithoutSuffix() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "somethingElse",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintBWithSuffix extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintBWithSuffix() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithMultipleImplicitClassifiers extends AbstractAttributeFilterConstraintLeaf {

		@Creator(silentImplicitClassifier = true, implicitClassifier = "primaryKey")
		public ConstraintWithMultipleImplicitClassifiers() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithMultipleAdditionalChildren extends AbstractAttributeFilterConstraintContainer {

		@Creator
		public ConstraintWithMultipleAdditionalChildren(@Nonnull @AdditionalChild OrderBy orderBy,
		                                                @Nonnull @AdditionalChild Require require) {
		}
	}

	@ConstraintDefinition(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithMultipleSameAdditionalChildren extends AbstractAttributeFilterConstraintLeaf {

		@Creator
		public ConstraintWithMultipleSameAdditionalChildren(@Nonnull @AdditionalChild Require require,
		                                                    @Nonnull @AdditionalChild Require require2) {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	private abstract static class AbstractAttributeFilterConstraintLeaf extends ConstraintLeaf<FilterConstraint> implements FilterConstraint, AttributeConstraint<FilterConstraint> {
		protected AbstractAttributeFilterConstraintLeaf(Serializable... arguments) {
			super(arguments);
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

	private abstract static class AbstractAttributeFilterConstraintContainer extends ConstraintContainer<FilterConstraint> implements FilterConstraint, AttributeConstraint<FilterConstraint> {

		@Nonnull
		@Override
		public Class<FilterConstraint> getType() {
			return FilterConstraint.class;
		}

		@Override
		public void accept(@Nonnull ConstraintVisitor visitor) {
			visitor.visit(this);
		}

		@Nonnull
		@Override
		public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
			return null;
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}
}