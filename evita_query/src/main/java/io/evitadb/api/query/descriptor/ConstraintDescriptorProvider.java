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
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Provides access to all registered {@link Constraint}s via {@link ConstraintDescriptor}s which serve as generic
 * descriptors of those constraints with all data needed about them for automated reconstruction or other processing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConstraintDescriptorProvider {

	private static final Set<ConstraintDescriptor> CONSTRAINT_DESCRIPTORS;
	private static final Map<Class<?>, Set<ConstraintDescriptor>> CONSTRAINT_DESCRIPTORS_TO_CLASS;
	private static final Map<ConstraintReconstructionLookupKey, Set<ConstraintDescriptor>> CONSTRAINT_DESCRIPTOR_RECONSTRUCTION_LOOKUP_INDEX;

	static {
		CONSTRAINT_DESCRIPTORS = new ConstraintProcessor().process(ConstraintRegistry.REGISTERED_CONSTRAINTS);

		final Map<Class<?>, Set<ConstraintDescriptor>> constraintDescriptorsToClass = createHashMap(CONSTRAINT_DESCRIPTORS.size());
		CONSTRAINT_DESCRIPTORS.forEach(descriptor -> {
			final Set<ConstraintDescriptor> descriptors = constraintDescriptorsToClass.computeIfAbsent(
				descriptor.constraintClass(),
				k -> new TreeSet<>(Comparator.comparing(ConstraintDescriptor::type).thenComparing(ConstraintDescriptor::fullName))
			);
			descriptors.add(descriptor);
		});
		CONSTRAINT_DESCRIPTORS_TO_CLASS = Collections.unmodifiableMap(constraintDescriptorsToClass);

		final Map<ConstraintReconstructionLookupKey, Set<ConstraintDescriptor>> constraintDescriptorReconstructionLookupIndex = createHashMap(CONSTRAINT_DESCRIPTORS.size());
		CONSTRAINT_DESCRIPTORS.forEach(descriptor -> {
			final Set<ConstraintDescriptor> descriptors = constraintDescriptorReconstructionLookupIndex.computeIfAbsent(
				new ConstraintReconstructionLookupKey(descriptor.type(), descriptor.propertyType(), descriptor.fullName()),
				k -> new TreeSet<>(new ConstraintDescriptorClassifierComparator())
			);
			descriptors.add(descriptor);
		});
		CONSTRAINT_DESCRIPTOR_RECONSTRUCTION_LOOKUP_INDEX = Collections.unmodifiableMap(constraintDescriptorReconstructionLookupIndex);
	}

	/**
	 * @return descriptors of all correctly registered constraints
	 */
	@Nonnull
	public static Set<ConstraintDescriptor> getAllConstraints() {
		return CONSTRAINT_DESCRIPTORS;
	}

	@Nonnull
	public static Optional<ConstraintDescriptor> getConstraint(@Nonnull ConstraintType type,
	                                                           @Nonnull ConstraintPropertyType propertyType,
	                                                           @Nonnull String fullName,
	                                                           @Nullable String classifier) {
		final Set<ConstraintDescriptor> descriptorCandidates = CONSTRAINT_DESCRIPTOR_RECONSTRUCTION_LOOKUP_INDEX.get(new ConstraintReconstructionLookupKey(
			type,
			propertyType,
			fullName
		));
		if (descriptorCandidates == null) {
			return Optional.empty();
		}
		return descriptorCandidates.stream()
			.filter(candidate -> {
				final ConstraintCreator creator = candidate.creator();

				final Optional<ImplicitClassifier> implicitClassifier = creator.implicitClassifier();
				if (implicitClassifier.isPresent()) {
					if (implicitClassifier.get() instanceof SilentImplicitClassifier) {
						return classifier == null;
					} else if (implicitClassifier.get() instanceof final FixedImplicitClassifier fixedImplicitClassifier) {
						return fixedImplicitClassifier.classifier().equals(classifier);
					} else {
						throw new GenericEvitaInternalError("Unsupported implicit classifier class.");
					}
				}
				if (creator.hasClassifierParameter()) {
					return classifier != null;
				}

				// creator doesn't support classifier, thus client cannot send one
				return classifier == null;
			})
			.findFirst();
	}

	@Nonnull
	public static ConstraintDescriptor getConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final Set<ConstraintDescriptor> constraints = getConstraints(constraintClass);
		Assert.isPremiseValid(
			constraints.size() == 1,
			"There are multiple variants of constraint `" + constraintClass.getName() + "`."
		);
		return constraints.iterator().next();
	}

	@Nonnull
	public static ConstraintDescriptor getConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass,
	                                                 @Nullable String suffix) {
		return getConstraints(constraintClass)
			.stream()
			.filter(
				it -> it.creator()
					.suffix()
					.map(it2 -> it2.equals(suffix))
					.orElse(suffix == null)
			)
			.findFirst()
			.orElseThrow(() ->
				new GenericEvitaInternalError("Unknown constraint `" + constraintClass.getName() + "` with suffix `" + suffix + "`. Is it properly registered?"));
	}

	@Nonnull
	public static Set<ConstraintDescriptor> getConstraints(@Nonnull Class<? extends Constraint<?>> constraintClass) {
		final Set<ConstraintDescriptor> foundConstraints = CONSTRAINT_DESCRIPTORS_TO_CLASS.getOrDefault(constraintClass, Set.of());
		Assert.isPremiseValid(
			!foundConstraints.isEmpty(),
			"Unknown constraint `" + constraintClass.getName() + "`. Is it properly registered?"
		);
		return foundConstraints;
	}

	/**
	 * @return descriptors of all correctly registered constraints with passed type
	 */
	@Nonnull
	public static Set<ConstraintDescriptor> getConstraints(@Nonnull ConstraintType type) {
		return CONSTRAINT_DESCRIPTORS.stream()
			.filter(cd -> cd.type().equals(type))
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * @return descriptors of all correctly registered constraints with passed type and property type and supported domain
	 */
	@Nonnull
	public static Set<ConstraintDescriptor> getConstraints(@Nonnull ConstraintType requiredType,
	                                                       @Nonnull ConstraintPropertyType requiredPropertyType,
	                                                       @Nonnull ConstraintDomain requiredSupportedDomain) {
		return CONSTRAINT_DESCRIPTORS.stream()
			.filter(cd -> cd.type().equals(requiredType) &&
				cd.propertyType().equals(requiredPropertyType) &&
				cd.supportedIn().contains(requiredSupportedDomain))
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * @return descriptors of all correctly registered constraints conforming to all the passed arguments
	 */
	@Nonnull
	public static Set<ConstraintDescriptor> getConstraints(
		@Nonnull ConstraintType requiredType,
		@Nonnull ConstraintPropertyType requiredPropertyType,
		@Nonnull ConstraintDomain requiredSupportedDomain,
		@Nonnull Class<?> requiredSupportedValueType,
		boolean arraySupportRequired,
		boolean nullableData
	) {
		return CONSTRAINT_DESCRIPTORS.stream()
			.filter(cd -> {
				final SupportedValues supportedValues = cd.supportedValues();
				return cd.type().equals(requiredType) &&
					cd.propertyType().equals(requiredPropertyType) &&
					cd.supportedIn().contains(requiredSupportedDomain) &&
					supportedValues != null &&
					supportedValues.dataTypes().contains(
						requiredSupportedValueType.isPrimitive() ?
							EvitaDataTypes.getWrappingPrimitiveClass(requiredSupportedValueType) :
							requiredSupportedValueType
					) &&
					verifyNullabilitySupport(supportedValues.nullability(), nullableData) &&
					(!arraySupportRequired || supportedValues.supportsArrays());
			})
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * @return descriptors of all correctly registered constraints supporting compound data types
	 */
	@Nonnull
	public static Set<ConstraintDescriptor> getConstraintsSupportingCompounds(@Nonnull ConstraintType requiredType,
	                                                                          @Nonnull ConstraintPropertyType requiredPropertyType,
	                                                                          @Nonnull ConstraintDomain requiredSupportedDomain) {
		return CONSTRAINT_DESCRIPTORS.stream()
			.filter(cd -> cd.type().equals(requiredType) &&
				cd.propertyType().equals(requiredPropertyType) &&
				cd.supportedIn().contains(requiredSupportedDomain) &&
				cd.supportedValues() != null &&
				cd.supportedValues().compoundsSupported())
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Checks if specified constraint is known to Evita.
	 */
	public static boolean isKnownConstraint(@Nonnull Class<?> constraintClass) {
		return CONSTRAINT_DESCRIPTORS_TO_CLASS.containsKey(constraintClass);
	}

	/**
	 * Verifies if nullability support of constraint matches nullability setting of data.
	 */
	private static boolean verifyNullabilitySupport(@Nonnull ConstraintNullabilitySupport nullabilitySupport, boolean nullableData) {
		if (nullabilitySupport.equals(ConstraintNullabilitySupport.ONLY_NULLABLE)) {
			return nullableData;
		} else if (nullabilitySupport.equals(ConstraintNullabilitySupport.ONLY_NONNULL)) {
			return !nullableData;
		} else {
			return true;
		}
	}

	/**
	 * Key for quick lookup of specific query descriptor during query reconstruction.
	 * These properties match uniqueness properties of descriptors plus suffixes of single creator.
	 */
	private record ConstraintReconstructionLookupKey(@Nonnull ConstraintType type,
	                                                 @Nonnull ConstraintPropertyType propertyType,
	                                                 @Nonnull String fullName) {
	}

	/**
	 * Primarily for sorting constraint descriptors by classifier definitions.
	 */
	private static class ConstraintDescriptorClassifierComparator implements Comparator<ConstraintDescriptor>, Serializable {

		@Serial private static final long serialVersionUID = 6454523828560777917L;

		@Override
		public int compare(ConstraintDescriptor o1, ConstraintDescriptor o2) {
			if (o1.creator().hasImplicitClassifier() && !o2.creator().hasImplicitClassifier()) {
				return -1;
			} else if (o2.creator().hasImplicitClassifier() && !o1.creator().hasImplicitClassifier()) {
				return 1;
			}

			if (o1.creator().hasClassifierParameter() && !o2.creator().hasClassifierParameter()) {
				return -1;
			} else if (o2.creator().hasClassifierParameter() && !o1.creator().hasClassifierParameter()) {
				return 1;
			}

			return 0;
		}
	}
}
