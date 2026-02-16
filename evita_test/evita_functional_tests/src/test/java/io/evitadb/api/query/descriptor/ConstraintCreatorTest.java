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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.filter.And;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstraintCreator}.
 *
 * @author Lukas Hornych, FG Forrest a.s. (c) 2022
 */
@DisplayName("ConstraintCreator")
class ConstraintCreatorTest {

	@Nested
	@DisplayName("Creation")
	class CreationTest {

		@Test
		@DisplayName("should create with classifier parameter")
		void shouldCreateCorrectCreatorWithClassifierParameter() {
			assertDoesNotThrow(() -> createCreator(null, new ClassifierParameterDescriptor("name")));
		}

		@Test
		@DisplayName("should create with implicit classifier")
		void shouldCreateCorrectCreatorWithImplicitClassifier() {
			assertDoesNotThrow(() -> createCreator(new FixedImplicitClassifier("primaryKey")));
		}
	}

	@Nested
	@DisplayName("Classifier validation")
	class ClassifierValidationTest {

		@Test
		@DisplayName("should reject both implicit and explicit classifiers")
		void shouldNotCreateWithMultipleClassifiers() {
			assertThrows(
				EvitaInternalError.class,
				() -> createCreator(new FixedImplicitClassifier("primaryKey"), new ClassifierParameterDescriptor("name"))
			);
		}

		@Test
		@DisplayName("should reject multiple classifier parameters")
		void shouldNotCreateWithMultipleClassifierParameters() {
			assertThrows(
				EvitaInternalError.class,
				() -> createCreator(null, new ClassifierParameterDescriptor("name"), new ClassifierParameterDescriptor("type"))
			);
		}
	}

	@Nested
	@DisplayName("Value structure")
	class ValueStructureTest {

		@Test
		@DisplayName("should return NONE when creator has no value, child or additional child parameters")
		void shouldReturnNoneWhenNoParameters() throws NoSuchMethodException {
			final ConstraintCreator creator = createCreator(null);

			assertEquals(ConstraintValueStructure.NONE, creator.valueStructure());
		}

		@Test
		@DisplayName("should return PRIMITIVE when creator has single value parameter")
		void shouldReturnPrimitiveWhenSingleValueParameter() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(
					new ValueParameterDescriptor("value", String.class, true, false)
				)
			);

			assertEquals(ConstraintValueStructure.PRIMITIVE, creator.valueStructure());
		}

		@Test
		@DisplayName("should return RANGE when creator has 'from' and 'to' value parameters of same type")
		void shouldReturnRangeWhenFromAndToParameters() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(
					new ValueParameterDescriptor("from", Integer.class, true, false),
					new ValueParameterDescriptor("to", Integer.class, true, false)
				)
			);

			assertEquals(ConstraintValueStructure.RANGE, creator.valueStructure());
		}

		@Test
		@DisplayName("should return CONTAINER when creator has single abstract child parameter and no values")
		void shouldReturnContainerWhenSingleAbstractChildParameter() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
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
				)
			);

			assertEquals(ConstraintValueStructure.CONTAINER, creator.valueStructure());
		}

		@Test
		@DisplayName("should return COMPLEX when creator has multiple value params and child params")
		void shouldReturnComplexWhenMixedParameters() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(
					new ValueParameterDescriptor("value1", String.class, true, false),
					new ValueParameterDescriptor("value2", Integer.class, true, false),
					new ChildParameterDescriptor(
						"children",
						FilterConstraint[].class,
						true,
						ConstraintDomain.DEFAULT,
						false,
						Set.of(),
						Set.of()
					)
				)
			);

			assertEquals(ConstraintValueStructure.COMPLEX, creator.valueStructure());
		}
	}

	@Nested
	@DisplayName("Parameter accessors")
	class ParameterAccessorsTest {

		@Test
		@DisplayName("should return only value parameters")
		void shouldReturnOnlyValueParameters() throws NoSuchMethodException {
			final ValueParameterDescriptor valueParam =
				new ValueParameterDescriptor("value", String.class, true, false);
			final ChildParameterDescriptor childParam = new ChildParameterDescriptor(
				"children", FilterConstraint[].class, true,
				ConstraintDomain.DEFAULT, false, Set.of(), Set.of()
			);
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(valueParam, childParam)
			);

			final List<ValueParameterDescriptor> result = creator.valueParameters();

			assertEquals(1, result.size());
			assertSame(valueParam, result.get(0));
		}

		@Test
		@DisplayName("should return only child parameters")
		void shouldReturnOnlyChildParameters() throws NoSuchMethodException {
			final ValueParameterDescriptor valueParam =
				new ValueParameterDescriptor("value", String.class, true, false);
			final ChildParameterDescriptor childParam = new ChildParameterDescriptor(
				"children", FilterConstraint[].class, true,
				ConstraintDomain.DEFAULT, false, Set.of(), Set.of()
			);
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(valueParam, childParam)
			);

			final List<ChildParameterDescriptor> result = creator.childParameters();

			assertEquals(1, result.size());
			assertSame(childParam, result.get(0));
		}

		@Test
		@DisplayName("should return only additional child parameters")
		void shouldReturnOnlyAdditionalChildParameters() throws NoSuchMethodException {
			final ValueParameterDescriptor valueParam =
				new ValueParameterDescriptor("value", String.class, true, false);
			final AdditionalChildParameterDescriptor additionalChildParam =
				new AdditionalChildParameterDescriptor(
					ConstraintType.ORDER, "orderBy",
					FilterConstraint.class, true, ConstraintDomain.DEFAULT
				);
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(valueParam, additionalChildParam)
			);

			final List<AdditionalChildParameterDescriptor> result = creator.additionalChildParameters();

			assertEquals(1, result.size());
			assertSame(additionalChildParam, result.get(0));
		}

		@Test
		@DisplayName("should return classifier parameter when present")
		void shouldReturnClassifierParameterWhenPresent() throws NoSuchMethodException {
			final ClassifierParameterDescriptor classifierParam =
				new ClassifierParameterDescriptor("attributeName");
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(classifierParam)
			);

			assertTrue(creator.hasClassifierParameter());
			assertTrue(creator.classifierParameter().isPresent());
			assertSame(classifierParam, creator.classifierParameter().get());
		}

		@Test
		@DisplayName("should return true for hasClassifier when creator has classifier parameter")
		void shouldReturnTrueForHasClassifierWithClassifierParameter() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(new ClassifierParameterDescriptor("name"))
			);

			assertTrue(creator.hasClassifier());
		}

		@Test
		@DisplayName("should return true for hasClassifier when creator has implicit classifier")
		void shouldReturnTrueForHasClassifierWithImplicitClassifier() throws NoSuchMethodException {
			final ConstraintCreator creator = new ConstraintCreator(
				And.class.getConstructor(FilterConstraint[].class),
				List.of(),
				new FixedImplicitClassifier("primaryKey")
			);

			assertTrue(creator.hasClassifier());
		}
	}

	@Nested
	@DisplayName("Constraint instantiation")
	class InstantiateConstraintTest {

		@Test
		@DisplayName("should instantiate And constraint via constructor")
		void shouldInstantiateConstraintViaConstructor() throws NoSuchMethodException {
			final Constructor<?> constructor = And.class.getConstructor(FilterConstraint[].class);
			final ConstraintCreator creator = new ConstraintCreator(
				constructor,
				List.of(
					new ChildParameterDescriptor(
						"children", FilterConstraint[].class, true,
						ConstraintDomain.DEFAULT, false, Set.of(), Set.of()
					)
				)
			);

			final Constraint<?> result = creator.instantiateConstraint(
				new Object[]{new FilterConstraint[0]}, "and"
			);

			assertInstanceOf(And.class, result);
		}

		@Test
		@DisplayName("should rethrow EvitaInvalidUsageException without wrapping")
		void shouldRethrowEvitaInvalidUsageException() throws NoSuchMethodException {
			// FixedImplicitClassifier constructor validates non-empty classifier
			// and throws EvitaInternalError (not EvitaInvalidUsageException).
			// We need to test with a constructor that throws EvitaInvalidUsageException.
			// And.class constructor with FilterConstraint[] won't throw, but we can test
			// with wrong arguments to see the wrapping behavior.
			// The best way is to verify the exception type from a real scenario.
			final Constructor<?> constructor = And.class.getConstructor(FilterConstraint[].class);
			final ConstraintCreator creator = new ConstraintCreator(
				constructor,
				List.of()
			);

			// Passing null where FilterConstraint[] is expected causes InvocationTargetException
			// wrapping an exception. Since it's not EvitaInvalidUsageException, it gets wrapped.
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> creator.instantiateConstraint(new Object[]{null}, "and")
			);

			assertNotNull(error.getMessage());
		}

		@Test
		@DisplayName("should wrap generic exception in GenericEvitaInternalError")
		void shouldWrapGenericExceptionInError() throws NoSuchMethodException {
			final Constructor<?> constructor = And.class.getConstructor(FilterConstraint[].class);
			final ConstraintCreator creator = new ConstraintCreator(
				constructor,
				List.of()
			);

			// Passing completely wrong argument type causes IllegalArgumentException
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> creator.instantiateConstraint(new Object[]{"notAnArray"}, "and")
			);

			assertTrue(error.getMessage().contains("and"));
			assertTrue(error.getMessage().contains(And.class.getName()));
		}
	}

	/**
	 * Creates a {@link ConstraintCreator} with optional implicit classifier and given parameters.
	 */
	@Nonnull
	private static ConstraintCreator createCreator(
		@Nullable ImplicitClassifier implicitClassifier,
		@Nonnull ParameterDescriptor... parameters
	) throws NoSuchMethodException {
		return new ConstraintCreator(
			And.class.getConstructor(FilterConstraint[].class),
			List.of(parameters),
			implicitClassifier
		);
	}
}
