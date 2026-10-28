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

package io.evitadb.core.query.filter;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FacetIncludingChildren;
import io.evitadb.api.query.filter.FacetIncludingChildrenExcept;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.filter.HierarchyAnyHaving;
import io.evitadb.api.query.filter.HierarchyDirectRelation;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyExcludingRoot;
import io.evitadb.api.query.filter.HierarchyHaving;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeEqualsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeIsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeValueMatchingTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that every attribute constraint the filter can execute has a translator that creates the matcher of its
 * value, and that the registry of the translators refuses to load otherwise.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute constraint translators of the filter")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class FilterByVisitorAttributeTranslatorsTest {

	/**
	 * The described filter constraints that have no translator of their own, because they are options or children
	 * the translator of the constraint they stand in reads, or that only a require constraint reads.
	 */
	private static final Set<Class<? extends FilterConstraint>> CONSTRAINTS_READ_BY_THEIR_PARENT = Set.of(
		HierarchyDirectRelation.class,
		HierarchyExcluding.class,
		HierarchyExcludingRoot.class,
		HierarchyAnyHaving.class,
		HierarchyHaving.class,
		FilterGroupBy.class,
		FacetIncludingChildren.class,
		FacetIncludingChildrenExcept.class
	);

	/**
	 * Returns the classes of all filter constraints the query model describes.
	 *
	 * @return the classes
	 */
	@Nonnull
	private static Set<Class<? extends FilterConstraint>> getDescribedFilterConstraints() {
		final Set<Class<? extends FilterConstraint>> result = new HashSet<>();
		for (ConstraintDescriptor descriptor : ConstraintDescriptorProvider.getConstraints(ConstraintType.FILTER)) {
			result.add(descriptor.constraintClass().asSubclass(FilterConstraint.class));
		}
		return result;
	}

	@Test
	@DisplayName("the registry of the real translators passes the attribute translator invariant")
	void shouldAcceptRealRegistry() {
		final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>>
			registry = new HashMap<>();
		for (Class<? extends FilterConstraint> constraintClass : getDescribedFilterConstraints()) {
			final FilteringConstraintTranslator<? extends FilterConstraint> translator =
				FilterByVisitor.getTranslator(constraintClass);
			if (translator != null) {
				registry.put(constraintClass, translator);
			}
		}
		assertFalse(registry.isEmpty());
		assertDoesNotThrow(() -> FilterByVisitor.verifyAttributeTranslators(registry));
	}

	@Test
	@DisplayName("an attribute constraint translated without the value matcher contract is refused by name")
	void shouldRefuseAttributeConstraintWithoutMatcher() {
		final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>>
			registry = Map.of(AttributeEquals.class, FilteringConstraintTranslator.<AttributeEquals>noOpTranslator());
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class, () -> FilterByVisitor.verifyAttributeTranslators(registry)
		);
		assertTrue(error.getPublicMessage().contains(AttributeEquals.class.getName()), error.getPublicMessage());
		assertTrue(
			error.getPublicMessage().contains(AttributeValueMatchingTranslator.class.getSimpleName()),
			error.getPublicMessage()
		);
	}

	@Test
	@DisplayName("`attributeIs` is exempt from the value matcher contract")
	void shouldAcceptAttributeIsWithoutMatcher() {
		final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>>
			registry = Map.of(AttributeIs.class, new AttributeIsTranslator());
		assertDoesNotThrow(() -> FilterByVisitor.verifyAttributeTranslators(registry));
	}

	@Test
	@DisplayName("a constraint that is no attribute constraint needs no value matcher")
	void shouldAcceptNonAttributeConstraintWithoutMatcher() {
		final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>>
			registry = new HashMap<>();
		registry.put(AttributeEquals.class, new AttributeEqualsTranslator());
		registry.put(EntityLocaleEquals.class, FilteringConstraintTranslator.<EntityLocaleEquals>noOpTranslator());
		assertDoesNotThrow(() -> FilterByVisitor.verifyAttributeTranslators(registry));
	}

	@Test
	@DisplayName("a class no translator is registered for has none")
	void shouldReturnNullForUnknownConstraintClass() {
		assertNull(FilterByVisitor.getTranslator(FilterConstraint.class));
	}

	@Test
	@DisplayName("every described filter constraint has a translator, and every attribute one can create a matcher")
	void shouldHaveTranslatorForEveryDescribedFilterConstraint() {
		final Set<Class<? extends FilterConstraint>> attributeConstraints = new HashSet<>();
		final Set<Class<? extends FilterConstraint>> uniqueIndexConstraints = new HashSet<>();
		final Set<Class<? extends FilterConstraint>> withoutTranslator = new HashSet<>();
		for (Class<? extends FilterConstraint> constraintClass : getDescribedFilterConstraints()) {
			final FilteringConstraintTranslator<? extends FilterConstraint> translator =
				FilterByVisitor.getTranslator(constraintClass);
			if (translator == null) {
				withoutTranslator.add(constraintClass);
				continue;
			}
			if (AttributeConstraint.class.isAssignableFrom(constraintClass)) {
				attributeConstraints.add(constraintClass);
				if (constraintClass == AttributeIs.class) {
					assertFalse(translator instanceof AttributeValueMatchingTranslator<?>);
				} else {
					final AttributeValueMatchingTranslator<?> matchingTranslator = assertInstanceOf(
						AttributeValueMatchingTranslator.class, translator,
						"The translator of `" + constraintClass.getName() + "` creates no matcher."
					);
					if (matchingTranslator.isAnsweredByUniqueIndex()) {
						uniqueIndexConstraints.add(constraintClass);
					}
				}
			}
		}
		assertEquals(
			CONSTRAINTS_READ_BY_THEIR_PARENT, withoutTranslator,
			"The filter constraints without a translator differ from those their parent translator reads."
		);
		assertFalse(attributeConstraints.isEmpty());
		assertTrue(attributeConstraints.contains(AttributeIs.class));
		assertEquals(Set.of(AttributeEquals.class, AttributeInSet.class), uniqueIndexConstraints);
	}

}
