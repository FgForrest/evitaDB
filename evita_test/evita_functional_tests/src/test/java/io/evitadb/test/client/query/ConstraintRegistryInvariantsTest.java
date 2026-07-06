/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.test.client.query;

import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.GroupConstraint;
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.utils.ClassUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style tests over the global constraint registry. Both tests scan every loaded
 * `ConstraintDescriptor` and assert structural invariants that would otherwise fail silently:
 *
 * - Every constraint class implements exactly one of the nine property-type marker interfaces.
 *   `ConstraintProcessor.resolveConstraintPropertyType` uses `findFirst()`, so a class that
 *   accidentally retains an old marker alongside a new one is silently classified by enum
 *   declaration order — this test catches the two-marker case the processor masks.
 *
 * - Every non-abstract concrete `@Child` parameter has a non-empty parameter name and a
 *   non-empty descriptor lookup. The first guards against a missing `-parameters` javac flag
 *   (which would yield empty wrapper keys on the wire); the second guards against orphan
 *   `@Child` types whose target class has no `@Creator`.
 *
 * @author JNO, FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@DisplayName("Constraint registry structural invariants")
class ConstraintRegistryInvariantsTest {

	@Test
	@DisplayName("Every constraint implements exactly one property-type-defining marker interface")
	void shouldImplementExactlyOnePropertyTypeMarkerInterface() {
		// guards the contract documented in GroupConstraint javadoc and enforced by ConstraintProcessor:
		// each constraint class implements exactly one marker, otherwise the descriptor registry's
		// property-type classification is ambiguous. The assertion below would also catch a class
		// that declared *two* markers during a careless refactor.
		final List<Class<?>> markers = List.of(
			GenericConstraint.class,
			EntityConstraint.class,
			AttributeConstraint.class,
			AssociatedDataConstraint.class,
			PriceConstraint.class,
			ReferenceConstraint.class,
			HierarchyConstraint.class,
			FacetConstraint.class,
			GroupConstraint.class
		);

		final List<String> violations = new ArrayList<>();
		for (final ConstraintDescriptor descriptor : ConstraintDescriptorProvider.getAllConstraints()) {
			final Class<?> constraintClass = descriptor.constraintClass();
			int matched = 0;
			final List<String> matchedNames = new ArrayList<>();
			for (final Class<?> marker : markers) {
				if (marker.isAssignableFrom(constraintClass)) {
					matched++;
					matchedNames.add(marker.getSimpleName());
				}
			}
			if (matched != 1) {
				violations.add(constraintClass.getName() + " implements " + matched + " markers: " + matchedNames);
			}
		}
		assertTrue(
			violations.isEmpty(),
			"Property-type marker invariants violated:\n  " + String.join("\n  ", violations)
		);
	}

	@Test
	@DisplayName("Every non-abstract concrete @Child parameter has at least one descriptor variant and a non-empty parameter name")
	void shouldHaveAtLeastOneVariantForEveryNonAbstractConcreteChildParameter() {
		final List<String> failures = new ArrayList<>();
		for (final ConstraintDescriptor descriptor : ConstraintDescriptorProvider.getAllConstraints()) {
			for (final ChildParameterDescriptor childParameter : descriptor.creator().childParameters()) {
				final Class<?> childType = childParameter.type();
				if (childType.isArray() || ClassUtils.isAbstract(childType)) {
					continue;
				}
				if (!Constraint.class.isAssignableFrom(childType)) {
					continue;
				}
				@SuppressWarnings("unchecked") final Set<ConstraintDescriptor> childDescriptors =
					ConstraintDescriptorProvider.getConstraints((Class<Constraint<?>>) childType);
				if (childDescriptors.isEmpty()) {
					failures.add(String.format(
						"Parent `%s` (%s) declares non-abstract @Child `%s` of type `%s` whose descriptor lookup is empty — pipeline cannot route the value.",
						descriptor.fullName(), descriptor.constraintClass().getSimpleName(),
						childParameter.name(), childType.getName()
					));
				}
				if (childParameter.name() == null || childParameter.name().isEmpty()) {
					failures.add(String.format(
						"Parent `%s` (%s) declares non-abstract @Child of type `%s` with empty parameter name — wire format would have no wrapper key in the single-variant case.",
						descriptor.fullName(), descriptor.constraintClass().getSimpleName(),
						childType.getName()
					));
				}
			}
		}
		assertTrue(
			failures.isEmpty(),
			"Wire-format invariants violated:\n  " + String.join("\n  ", failures)
		);
	}
}
