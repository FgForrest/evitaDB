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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.dataType.EvitaDataTypes;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AllowedConstraintPredicate}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class AllowedConstraintPredicateTest {

	static final ConstraintDescriptor ATTRIBUTE_EQUALS = createAttributeEqualsDescriptor();
	static final ConstraintDescriptor ENTITY_LOCALE_EQUALS = createEntityLocaleEqualsDescriptor();
	static final ConstraintDescriptor ATTRIBUTE_NATURAL = createAttributeNaturalDescriptor();

	@Test
	void shouldAllowConstraint() {
		final AllowedConstraintPredicate emptyPredicate = new AllowedConstraintPredicate(Set.of(), Set.of());
		assertTrue(emptyPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate onlyAllowedPredicate = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeEquals.class),
			Set.of()
		);
		assertTrue(onlyAllowedPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate onlyForbiddenPredicate = new AllowedConstraintPredicate(
			Set.of(),
			Set.of(And.class)
		);
		assertTrue(onlyForbiddenPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicate = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeEquals.class, Or.class),
			Set.of(Or.class, AttributeStartsWith.class)
		);
		assertTrue(fullPredicate.test(ATTRIBUTE_EQUALS));
	}

	@Test
	void shouldAllowConstraintFromConstructor() {
		final AllowedConstraintPredicate emptyGlobalConstraints = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(),
			Set.of()
		);
		assertTrue(emptyGlobalConstraints.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate onlyAllowedPredicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(And.class, AttributeEquals.class),
			Set.of()
		);
		assertTrue(onlyAllowedPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate onlyForbiddenPredicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(),
			Set.of(And.class)
		);
		assertTrue(onlyForbiddenPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(And.class, AttributeEquals.class, Or.class),
			Set.of(Or.class, AttributeStartsWith.class)
		);
		assertTrue(fullPredicate.test(ATTRIBUTE_EQUALS));
	}

	@Test
	void shouldNotAllowConstraint() {
		final AllowedConstraintPredicate onlyAllowedPredicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(And.class, AttributeEquals.class),
			Set.of()
		);
		assertFalse(onlyAllowedPredicate.test(ENTITY_LOCALE_EQUALS));

		final AllowedConstraintPredicate onlyForbiddenPredicate = new AllowedConstraintPredicate(
			Set.of(),
			Set.of(AttributeEquals.class)
		);
		assertFalse(onlyForbiddenPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicateWithForbiddenConstraint = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeStartsWith.class),
			Set.of(Or.class, AttributeEquals.class)
		);
		assertFalse(fullPredicateWithForbiddenConstraint.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicateWithForbiddenAndAllowedConstraint = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeStartsWith.class, AttributeEquals.class),
			Set.of(Or.class, AttributeEquals.class)
		);
		assertFalse(fullPredicateWithForbiddenAndAllowedConstraint.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate onlyBaseType = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(),
			Set.of()
		);
		assertFalse(onlyBaseType.test(ATTRIBUTE_NATURAL));
	}

	@Test
	void shouldNotAllowConstraintFromConstructor() {
		final AllowedConstraintPredicate emptyGlobalConstraints = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(UserFilter.class).iterator().next().creator().childParameter().get(),
			Set.of(),
			Set.of()
		);
		assertFalse(emptyGlobalConstraints.test(ENTITY_LOCALE_EQUALS));

		final AllowedConstraintPredicate onlyForbiddenPredicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraints(And.class).iterator().next().creator().childParameter().get(),
			Set.of(),
			Set.of(AttributeEquals.class)
		);
		assertFalse(onlyForbiddenPredicate.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicateWithForbiddenConstraint = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeStartsWith.class),
			Set.of(Or.class, AttributeEquals.class)
		);
		assertFalse(fullPredicateWithForbiddenConstraint.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicateWithForbiddenAndAllowedConstraint = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeStartsWith.class, AttributeEquals.class),
			Set.of(Or.class, AttributeEquals.class)
		);
		assertFalse(fullPredicateWithForbiddenAndAllowedConstraint.test(ATTRIBUTE_EQUALS));

		final AllowedConstraintPredicate fullPredicateWithForbiddenAndAllowedConstraint2 = new AllowedConstraintPredicate(
			Set.of(And.class, AttributeStartsWith.class, AttributeEquals.class),
			Set.of(Or.class, AttributeEquals.class)
		);
		assertFalse(fullPredicateWithForbiddenAndAllowedConstraint2.test(ENTITY_LOCALE_EQUALS));
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createAttributeEqualsDescriptor() {
		return new ConstraintDescriptor(
			AttributeEquals.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.ATTRIBUTE,
			"equals",
			"This is a description.",
			Set.of(ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE),
			new SupportedValues(
				EvitaDataTypes.getSupportedDataTypes(),
				true
			),
			new ConstraintCreator(
				AttributeEquals.class.getConstructor(String.class, Serializable.class),
				List.of(
					new ClassifierParameterDescriptor("attributeName"),
					new ValueParameterDescriptor(
						"attributeValue",
						Serializable.class,
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
	private static ConstraintDescriptor createEntityLocaleEqualsDescriptor() {
		return new ConstraintDescriptor(
			EntityLocaleEquals.class,
			ConstraintType.FILTER,
			ConstraintPropertyType.ENTITY,
			"equals",
			"This is a description.",
			Set.of(ConstraintDomain.ENTITY),
			null,
			new ConstraintCreator(
				EntityLocaleEquals.class.getConstructor(Locale.class),
				List.of(
					new ValueParameterDescriptor(
						"locale",
						Locale.class,
						true,
						false
					)
				),
				new FixedImplicitClassifier("locale")
			)
		);
	}

	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createAttributeNaturalDescriptor() {
		return new ConstraintDescriptor(
			AttributeNatural.class,
			ConstraintType.ORDER,
			ConstraintPropertyType.ATTRIBUTE,
			"equals",
			"This is a description.",
			Set.of(ConstraintDomain.ENTITY),
			null,
			new ConstraintCreator(
				AttributeNatural.class.getConstructor(String.class),
				List.of(
					new ValueParameterDescriptor(
						"attributeName",
						String.class,
						true,
						false
					)
				),
				null
			)
		);
	}
}