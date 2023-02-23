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


import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;
import io.evitadb.api.query.filter.AbstractAttributeFilterConstraintLeaf;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.HierarchySpecificationFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
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

	@ConstraintDef(
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

	@ConstraintDef(
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

	@ConstraintDef(
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

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithUnannotatedParameters extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef
		public ConstraintWithUnannotatedParameters(@Nonnull Long value) {
			super(value);
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintA extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(implicitClassifier = "primaryKey")
		public SimilarConstraintA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintB extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(implicitClassifier = "primaryKey")
		public SimilarConstraintB() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintWithSuffixedCreatorsA extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(implicitClassifier = "primaryKey", suffix = "other")
		public SimilarConstraintWithSuffixedCreatorsA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class SimilarConstraintWithSuffixedCreatorsB extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(implicitClassifier = "primaryKey", suffix = "other")
		public SimilarConstraintWithSuffixedCreatorsB() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionWithoutClassifier extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef
		public ConstraintWithSameConditionWithoutClassifier() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionWithClassifier extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(implicitClassifier = "primaryKey")
		public ConstraintWithSameConditionWithClassifier() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersA extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(suffix = "other")
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersA() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB() {
		}

		@ConstraintCreatorDef(suffix = "other")
		public ConstraintWithSameConditionAndDifferentFullNamesOrClassifiersB(@Nonnull @ConstraintClassifierParamDef String classifier) {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithDuplicateCreators extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(suffix = "other")
		public ConstraintWithDuplicateCreators() {
		}

		@ConstraintCreatorDef(suffix = "other")
		public ConstraintWithDuplicateCreators(@Nonnull @ConstraintValueParamDef String value) {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintAWithoutSuffix extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef
		public ConstraintAWithoutSuffix() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "somethingElse",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintBWithSuffix extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef
		public ConstraintBWithSuffix() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}

	@ConstraintDef(
		name = "something",
		shortDescription = "This is a constraint."
	)
	private static class ConstraintWithMultipleImplicitClassifiers extends AbstractAttributeFilterConstraintLeaf {

		@ConstraintCreatorDef(silentImplicitClassifier = true, implicitClassifier = "primaryKey")
		public ConstraintWithMultipleImplicitClassifiers() {
		}

		@Nonnull
		@Override
		public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
			return null;
		}
	}
}