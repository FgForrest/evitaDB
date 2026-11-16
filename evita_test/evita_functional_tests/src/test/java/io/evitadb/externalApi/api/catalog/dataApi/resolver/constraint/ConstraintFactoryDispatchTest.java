/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.*;
import io.evitadb.api.query.descriptor.*;
import io.evitadb.api.query.descriptor.ConstraintCreator.*;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.require.*;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins external API creator dispatch and parameter positions against the Java factories.
 */
@DisplayName("External API factory dispatch")
@Tag(EXTERNAL_API)
@Tag(QUERY)
class ConstraintFactoryDispatchTest {
	/**
	 * Contracts for every registered creator and for descriptors outside the registry.
	 */
	@Nested
	@DisplayName("Creator dispatch")
	class Creators {
		/**
		 * Makes newly unmatched creators and newly introduced adapters an explicit review decision.
		 */
		@Test
		@DisplayName("Only the audited creators use constructor fallback or an adapter")
		void shouldPinFallbacksAndAdaptersWhenCreatorsAreRegistered() {
			final Set<String> fallbacks = new TreeSet<>();
			final Set<String> adapters = new TreeSet<>();
			for (final ConstraintDescriptor descriptor : ConstraintDescriptorProvider.getAllConstraints()) {
				if (ConstraintResolver.usesFactoryAdapter(descriptor)) {
					adapters.add(creatorName(descriptor));
				} else if (ConstraintResolver.getConstraintCreator(descriptor) == descriptor.creator()) {
					fallbacks.add(creatorName(descriptor));
				}
			}
			assertEquals(Set.of("AttributeInRange", "SegmentLimit", "HierarchyDistance", "HierarchyLevel",
				"ReferenceSummary", "ReferenceSummaryOfReference", "ReferenceSummaryOfReference:withHistograms"),
				fallbacks);
			assertEquals(Set.of("HistogramHaving", "Segment"), adapters);
		}

		/**
		 * Distinguishes repeated parameter types so swapped positions cannot pass unnoticed.
		 *
		 * @return one contract assertion for each factory-dispatched creator
		 */
		@TestFactory
		@DisplayName("Factory dispatch preserves every distinct non-null creator argument")
		Stream<DynamicTest> shouldMatchConstructorWhenFactoryArgumentsAreDistinct() {
			return ConstraintDescriptorProvider.getAllConstraints().stream().sorted()
				.filter(descriptor -> !ConstraintResolver.usesFactoryAdapter(descriptor))
				.filter(descriptor -> ConstraintResolver.getConstraintCreator(descriptor) != descriptor.creator())
				.map(descriptor -> DynamicTest.dynamicTest(creatorName(descriptor), () -> {
					final Object[] arguments = factoryDispatchArguments(descriptor);
					final Constraint<?> constructed = descriptor.creator().instantiateConstraint(arguments,
						descriptor.fullName());
					assertNotNull(constructed);
					assertTrue(constructed.isApplicable());
					assertEquals(constructed, ConstraintResolver.instantiateConstraint(descriptor, arguments,
						descriptor.fullName()));
				}));
		}

		/**
		 * Uses the supplied descriptor creator when the static registry has no such key.
		 */
		@Test
		@DisplayName("Unregistered descriptors retain their own creator")
		void shouldUseOwnCreatorWhenDescriptorIsNotRegistered() {
			final ConstraintDescriptor template = ConstraintDescriptorProvider.getAllConstraints().stream()
				.filter(descriptor -> descriptor.constraintClass() == Random.class).findFirst().orElseThrow();
			final ConstraintDescriptor descriptor = new ConstraintDescriptor(
				template.constraintClass(), template.type(),
				template.propertyType(), "unregisteredRandom", template.shortDescription(), "/query/ordering/random",
				null, template.supportedIn(), template.supportedValues(), template.creator());
			assertEquals(random(), ConstraintResolver.instantiateConstraint(descriptor, new Object[0], "random"));
		}
	}

	/**
	 * Identifies a creator including variants of the same constraint class.
	 *
	 * @param descriptor registered creator descriptor
	 * @return stable class and suffix identifier
	 */
	@Nonnull
	private static String creatorName(@Nonnull ConstraintDescriptor descriptor) {
		return descriptor.constraintClass().getSimpleName() + descriptor.creator().suffix()
			.map(suffix -> ":" + suffix).orElse("");
	}

	/**
	 * Builds non-null inputs, assigning different enum constants to repeated enum positions.
	 *
	 * @param descriptor creator whose signature supplies the argument types
	 * @return arguments in descriptor order
	 */
	@Nonnull
	private static Object[] factoryDispatchArguments(@Nonnull ConstraintDescriptor descriptor) {
		final List<ParameterDescriptor> parameters = descriptor.creator().parameters();
		final Map<Class<?>, Integer> occurrences = createHashMap(parameters.size());
		final Object[] arguments = new Object[parameters.size()];
		for (int index = 0; index < arguments.length; index++) {
			final ParameterDescriptor parameter = parameters.get(index);
			final Class<?> type = parameter instanceof ClassifierParameterDescriptor ? String.class :
				parameter instanceof ValueParameterDescriptor value ? value.type() :
					((ConstraintParameterDescriptor) parameter).type();
			arguments[index] = factoryDispatchValue(type, index, occurrences.merge(type, 1, Integer::sum) - 1);
		}
		return arguments;
	}

	/**
	 * Supplies valid, position-specific values without hiding unsupported future parameter types.
	 *
	 * @param type declared parameter or array component type
	 * @param position argument position, used to distinguish scalar values
	 * @param occurrence occurrence of this type, used to distinguish enum constants
	 * @return a non-null value accepted by the creator and its matching factory
	 */
	@Nonnull
	private static Object factoryDispatchValue(@Nonnull Class<?> type, int position, int occurrence) {
		if (type.isArray()) {
			final Object values = Array.newInstance(type.getComponentType(), 1);
			Array.set(values, 0, factoryDispatchValue(type.getComponentType(), position, occurrence));
			return values;
		}
		if (type == String.class) return "argument" + position;
		if (type == int.class || type == Integer.class) return position + 1;
		if (type == long.class || type == Long.class) return (long) position + 1;
		if (type == BigDecimal.class || type == Number.class) return BigDecimal.valueOf(position + 1);
		if (type == Serializable.class || type == Comparable.class) return position + 1;
		if (type == Locale.class) return Locale.ENGLISH;
		if (type == Currency.class) return Currency.getInstance("EUR");
		if (type == OffsetDateTime.class) return OffsetDateTime.of(2026, 1, position + 1, 0, 0, 0, 0, ZoneOffset.UTC);
		if (type == Expression.class) return ExpressionFactory.parse("true");
		if (type.isEnum()) return type.getEnumConstants()[occurrence % type.getEnumConstants().length];
		if (type == HeadConstraint.class) return collection("collection" + position);
		if (type == FilterConstraint.class) return attributeEquals("filter" + position, position + 1);
		if (type == OrderConstraint.class) return entityPrimaryKeyNatural(ASC);
		if (type == RequireConstraint.class || type == EntityFetchRequire.class || type == EntityFetch.class) {
			return entityFetch(attributeContent("entity" + position));
		}
		if (type == EntityContentRequire.class || type == AttributeContent.class) {
			return attributeContent("content" + position);
		}
		if (type == EntityGroupFetch.class) return entityGroupFetch(attributeContent("group" + position));
		if (type == FilterBy.class) return filterBy(entityPrimaryKeyInSet(position + 1));
		if (type == FilterGroupBy.class) return filterGroupBy(entityPrimaryKeyInSet(position + 1));
		if (type == EntityHaving.class) return entityHaving(entityPrimaryKeyInSet(position + 1));
		if (type == GroupHaving.class) return groupHaving(entityPrimaryKeyInSet(position + 1));
		if (type == HierarchySpecificationFilterConstraint.class) return excluding(entityPrimaryKeyInSet(position + 1));
		if (type == OrderBy.class) return orderBy(entityPrimaryKeyNatural(ASC));
		if (type == OrderGroupBy.class) return orderGroupBy(entityPrimaryKeyNatural(ASC));
		if (type == SegmentLimit.class) return limit(position + 1);
		if (type == Segment.class) return segment(orderBy(entityPrimaryKeyNatural(ASC)));
		if (type == ChunkingRequireConstraint.class) return page(position + 1, position + 2);
		if (type == HierarchyNode.class) return node(filterBy(entityPrimaryKeyInSet(position + 1)));
		if (type == HierarchyStopAtRequireConstraint.class) return distance(position + 1);
		if (type == HierarchyRequireConstraint.class) return fromRoot("root" + position);
		if (type == HierarchyStopAt.class) return stopAt(distance(position + 1));
		if (type == HierarchyOutputRequireConstraint.class) return stopAt(distance(position + 1));
		if (type == HierarchySiblings.class) return siblings("siblings" + position);
		if (type == SpacingGap.class) return gap(position + 1, "true");
		if (type == Spacing.class) return spacing(gap(position + 1, "true"));
		throw new IllegalArgumentException("Missing dispatch fixture for " + type.getName());
	}
}
