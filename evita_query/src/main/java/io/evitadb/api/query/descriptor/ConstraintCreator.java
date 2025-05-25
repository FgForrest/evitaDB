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

import io.evitadb.api.query.Constraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Contains metadata for reconstructing original constraint described by {@link ConstraintDescriptor}. Because
 * lots of data need to be computed, they are cached once they are computed.

 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ConstraintCreator {

	/**
	 * Name of creator parameter representing left side of a range.
	 */
	public static final String RANGE_FROM_VALUE_PARAMETER = "from";
	/**
	 * Name of creator parameter representing right side of a range.
	 */
	public static final String RANGE_TO_VALUE_PARAMETER = "to";
	/**
	 * Number of creator parameter to qualify it as a range.
	 */
	public static final int RANGE_PARAMETERS_COUNT = 2;

	// base data
	/**
	 * Executable element (constructor or factory method) to instantiate original constraint from {@link #parameters()}.
	 */
	@Nonnull private final Executable instantiator;
	/**
	 * Suffix of creator, defining {@link ConstraintDescriptor#fullName()}.
	 */
	@Nullable private final String suffix;
	/**
	 * Descriptors of original parameters of instantiator in same order to be able to reconstruct
	 * the original constraint.
	 */
	@Nonnull private final List<ParameterDescriptor> parameters;
	/**
	 * fixed implicit classifier, alternative to dynamic classifier parameter
	 */
	@Nullable private final ImplicitClassifier implicitClassifier;

	// computed data
	@Nullable private Boolean hasClassifierParameter;
	@Nullable private ClassifierParameterDescriptor classifierParameter;
	@Nullable private Boolean hasValueParameters;
	@Nullable private List<ValueParameterDescriptor> valueParameters;
	@Nullable private Boolean hasChildParameters;
	@Nullable private List<ChildParameterDescriptor> childParameters;
	@Nullable private Boolean hasAdditionalChildParameters;
	@Nullable private List<AdditionalChildParameterDescriptor> additionalChildParameters;
	@Nullable private ConstraintValueStructure valueStructure;

	public ConstraintCreator(@Nonnull Executable instantiator,
	                         @Nonnull List<ParameterDescriptor> parameters) {
		this(instantiator, null, parameters, null);
	}

	public ConstraintCreator(@Nonnull Executable instantiator,
	                         @Nullable String suffix,
	                         @Nonnull List<ParameterDescriptor> parameters) {
		this(instantiator, suffix, parameters, null);
	}

	public ConstraintCreator(@Nonnull Executable instantiator,
	                         @Nonnull List<ParameterDescriptor> parameters,
	                         @Nullable ImplicitClassifier implicitClassifier) {
		this(instantiator, null, parameters, implicitClassifier);
	}

	public ConstraintCreator(@Nonnull Executable instantiator,
							 @Nullable String suffix,
	                         @Nonnull List<ParameterDescriptor> parameters,
	                         @Nullable ImplicitClassifier implicitClassifier) {
		this.instantiator = instantiator;
		this.suffix = suffix;
		this.parameters = parameters;
		this.implicitClassifier = implicitClassifier;

		final boolean hasClassifierDescriptor = this.parameters.stream().anyMatch(ClassifierParameterDescriptor.class::isInstance);
		final boolean hasImplicitClassifier = this.implicitClassifier != null;
		Assert.isPremiseValid(
			!(hasClassifierDescriptor && hasImplicitClassifier),
			"Constraint cannot have both classifier parameter and implicit classifier specified."
		);
		Assert.isPremiseValid(
			this.parameters.stream().filter(ClassifierParameterDescriptor.class::isInstance).count() <= 1,
			"Constraint must have maximum of 1 classifier."
		);
	}

	/**
	 * Instantiates new constraint with passed arguments.
	 *
	 * @param args arguments for constraint constructor
	 * @param parsedName name of parsed constraint in target API, used to locale errors
	 * @return new constraint
	 */
	public Constraint<?> instantiateConstraint(@Nonnull Object[] args, @Nonnull String parsedName) {
		try {
			this.instantiator.trySetAccessible();
			if (this.instantiator instanceof Constructor<?> constructor) {
				return (Constraint<?>) constructor.newInstance(args);
			} else if (this.instantiator instanceof Method method) {
				return (Constraint<?>) method.invoke(null, args);
			} else {
				throw new GenericEvitaInternalError("Unsupported creator.");
			}
		} catch (Exception e) {
			if (e instanceof final InvocationTargetException invocationTargetException &&
				invocationTargetException.getTargetException() instanceof final EvitaInvalidUsageException invalidUsageException) {
				throw invalidUsageException;
			}
			throw new GenericEvitaInternalError(
				"Could not instantiate constraint `" + parsedName + "` to original constraint `" + this.instantiator.getDeclaringClass().getName() + "`: " + e.getMessage(),
				"Could not recreate constraint `" + parsedName + "`.",
				e
			);
		}
	}


	/**
	 * Returns optional suffix of this creator.
	 */
	@Nonnull
	public Optional<String> suffix() {
		return Optional.ofNullable(this.suffix);
	}

	/**
	 * Whether constraint has implicit classifier instead of classifier parameter.
	 */
	public boolean hasImplicitClassifier() {
		return this.implicitClassifier != null;
	}

	@Nonnull
	public Optional<ImplicitClassifier> implicitClassifier() {
		return Optional.ofNullable(this.implicitClassifier);
	}

	/**
	 * Whether there is classifier parameter in {@link #parameters()}.
	 */
	public boolean hasClassifierParameter() {
		if (this.hasClassifierParameter == null) {
			this.classifierParameter = parameters().stream()
				.filter(ClassifierParameterDescriptor.class::isInstance)
				.map(ClassifierParameterDescriptor.class::cast)
				.findFirst()
				.orElse(null);
			this.hasClassifierParameter = this.classifierParameter != null;
		}
		return this.hasClassifierParameter;
	}

	/**
	 * Whether this constraint requires classifier, either fixed {@link #implicitClassifier()} or dynamic {@link #classifierParameter()}.
	 */
	public boolean hasClassifier() {
		return hasClassifierParameter() || hasImplicitClassifier();
	}

	@Nonnull
	public List<ParameterDescriptor> parameters() {
		return this.parameters;
	}

	/**
	 * Finds classifier parameter in {@link #parameters()}.
	 */
	@Nonnull
	public Optional<ClassifierParameterDescriptor> classifierParameter() {
		if (this.hasClassifierParameter == null) {
			hasClassifierParameter();
		}
		return Optional.ofNullable(this.classifierParameter);
	}

	/**
	 * Finds value parameters in {@link #parameters()}.
	 */
	@Nonnull
	public List<ValueParameterDescriptor> valueParameters() {
		if (this.hasValueParameters == null) {
			this.valueParameters = parameters().stream()
				.filter(ValueParameterDescriptor.class::isInstance)
				.map(ValueParameterDescriptor.class::cast)
				.toList();
			this.hasValueParameters = !this.valueParameters.isEmpty();
		}
		return Objects.requireNonNull(this.valueParameters);
	}

	/**
	 * Finds child parameters in {@link #parameters()}.
	 */
	@Nonnull
	public List<ChildParameterDescriptor> childParameters() {
		if (this.hasChildParameters == null) {
			this.childParameters = parameters().stream()
				.filter(ChildParameterDescriptor.class::isInstance)
				.map(ChildParameterDescriptor.class::cast)
				.toList();
			this.hasChildParameters = !this.childParameters.isEmpty();
		}
		return Objects.requireNonNull(this.childParameters);
	}

	/**
	 * Finds all additional child parameters categorized by constraint type.
	 */
	@Nonnull
	public List<AdditionalChildParameterDescriptor> additionalChildParameters() {
		if (this.hasAdditionalChildParameters == null) {
			this.additionalChildParameters = parameters().stream()
				.filter(AdditionalChildParameterDescriptor.class::isInstance)
				.map(AdditionalChildParameterDescriptor.class::cast)
				.toList();
			this.hasAdditionalChildParameters = !this.additionalChildParameters.isEmpty();
		}
		return Objects.requireNonNull(this.additionalChildParameters);
	}

	/**
	 * Summarizes creator parameters into a single structure type.
	 */
	@Nonnull
	public ConstraintValueStructure valueStructure() {
		if (this.valueStructure == null) {
			final List<ValueParameterDescriptor> valueParameters = valueParameters();
			final List<ChildParameterDescriptor> childParameters = childParameters();
			final List<AdditionalChildParameterDescriptor> additionalChildParameters = additionalChildParameters();

			if (valueParameters.isEmpty() && childParameters.isEmpty() && additionalChildParameters.isEmpty()) {
				this.valueStructure = ConstraintValueStructure.NONE;
			} else if (valueParameters.size() == 1 && childParameters.isEmpty() && additionalChildParameters.isEmpty()) {
				this.valueStructure = ConstraintValueStructure.PRIMITIVE;
			} else if (
				valueParameters.size() == RANGE_PARAMETERS_COUNT &&
				childParameters.isEmpty() &&
				additionalChildParameters.isEmpty() &&
				valueParameters.stream().filter(p -> p.name().equals(RANGE_FROM_VALUE_PARAMETER) || p.name().equals(RANGE_TO_VALUE_PARAMETER)).count() == RANGE_PARAMETERS_COUNT &&
				valueParameters.get(0).type().equals(valueParameters.get(1).type())
			) {
				this.valueStructure = ConstraintValueStructure.RANGE;
			} else if (
				valueParameters.isEmpty() &&
				childParameters.size() == 1 &&
				ClassUtils.isAbstract(childParameters.get(0).type()) &&
				additionalChildParameters.isEmpty()
			) {
				this.valueStructure = ConstraintValueStructure.CONTAINER;
			} else {
				this.valueStructure = ConstraintValueStructure.COMPLEX;
			}
		}
		return this.valueStructure;
	}

	@Override
	public String toString() {
		return "ConstraintCreator{" +
			"instantiator=" + this.instantiator +
			", suffix='" + this.suffix + '\'' +
			", parameters=" + this.parameters +
			", implicitClassifier=" + this.implicitClassifier +
			", hasClassifierParameter=" + this.hasClassifierParameter +
			", classifierParameter=" + this.classifierParameter +
			", hasValueParameters=" + this.hasValueParameters +
			", valueParameters=" + this.valueParameters +
			", hasChildParameters=" + this.hasChildParameters +
			", childParameters=" + this.childParameters +
			", hasAdditionalChildParameters=" + this.hasAdditionalChildParameters +
			", additionalChildParameters=" + this.additionalChildParameters +
			", valueStructure=" + this.valueStructure +
			'}';
	}

	/**
	 * Represents classifier that is not specified by client but by system.
	 */
	public sealed interface ImplicitClassifier permits SilentImplicitClassifier, FixedImplicitClassifier {}

	/**
	 * Implicit classifier that is silently resolved by system without client knowing.
	 */
	public record SilentImplicitClassifier() implements ImplicitClassifier {}

	/**
	 * Implicit classifier that has fixed value specified during constraint definition.
	 */
	public record FixedImplicitClassifier(@Nonnull String classifier) implements ImplicitClassifier {

		public FixedImplicitClassifier {
			Assert.isPremiseValid(!classifier.isEmpty(), "Classifier cannot be empty");
		}
	}

	/**
	 * Common ancestor for all constraint parameters.
	 */
	public interface ParameterDescriptor {
		/**
		 * Name of original parameter.
		 */
		@Nonnull String name();
	}

	/**
	 * Describes single constraint constructor parameter whose purpose is to classify target data (e.g. entity type, attribute name...).
	 * Currently, if classifier parameter is present it is always required.
	 */
	public record ClassifierParameterDescriptor(@Nonnull String name) implements ParameterDescriptor {}

	/**
	 * Describes single constraint constructor parameter which holds some specific value to usually compare against target
	 * data.
	 *
	 * @param name name of original parameter
	 * @param type data type of original parameter
	 * @param required value cannot be null
	 * @param requiresPlainType if true, value is required to be plain type of original one (e.g. if original type is integer range, this constraint
	 *                          requires integer to be passed)
	 */
	public record ValueParameterDescriptor(@Nonnull String name,
	                                       @Nonnull Class<? extends Serializable> type,
	                                       boolean required,
	                                       boolean requiresPlainType) implements ParameterDescriptor {
	}

	/**
	 * Ancestor descriptor for parameter wrapping constraints or constraint containers.
	 */
	public interface ConstraintParameterDescriptor extends ParameterDescriptor {

		/**
		 * Constraint type of the parameter.
		 */
		@Nonnull Class<?> type();

		/**
		 * Specifies domain for the constraint.
		 */
		@Nonnull ConstraintDomain domain();
	}

	/**
	 * Describes single constraint constructor parameter which holds single or multiple child constraints (of
	 * same type as parent container).
	 *
	 * @param name name of original parameter
	 * @param type data type of original parameter
	 * @param required children cannot be null
	 * @param domain specifies domain for child constraints
	 * @param uniqueChildren if each child constraint can be passed only once in this list parameter.
	 * @param allowedChildTypes set of allowed child constraints. Constraint not specified in this set will be forbidden.
	 * @param forbiddenChildTypes set of forbidden child constraints. All constraints are allowed except of these.
	 */
	public record ChildParameterDescriptor(@Nonnull String name,
	                                       @Nonnull Class<?> type,
	                                       boolean required,
	                                       @Nonnull ConstraintDomain domain,
	                                       boolean uniqueChildren,
	                                       @Nonnull Set<Class<? extends Constraint<?>>> allowedChildTypes,
	                                       @Nonnull Set<Class<? extends Constraint<?>>> forbiddenChildTypes) implements ConstraintParameterDescriptor {}

	/**
	 * Describes single constraint constructor parameter which holds single or multiple additional child constraints (of different type
	 * than the parent container).
	 *
	 * @param constraintType type of constraint, different from the parent container
	 * @param name name of original parameter
	 * @param type data type of original parameter
	 * @param required children cannot be null
	 * @param domain specifies domain for additional child constraints
	 */
	public record AdditionalChildParameterDescriptor(@Nonnull ConstraintType constraintType,
	                                                 @Nonnull String name,
	                                                 @Nonnull Class<?> type,
	                                                 boolean required,
	                                                 @Nonnull ConstraintDomain domain) implements ConstraintParameterDescriptor {}
}
