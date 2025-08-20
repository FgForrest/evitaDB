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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.Getter;
import lombok.ToString;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies single created constraint wrapper object (usually JSON object)
 * mainly for reuse by those who need the same object. These metadata
 * must ensure that two objects with these metadata are ultimately same.
 *
 * Note: if there is only flat structure of primitive values (no child parameter), only value parameter descriptor
 * are used for comparison which greatly extends are of usage of the object, because primitive value parameters are not dependent
 * on build context.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Getter
@ToString(callSuper = true)
public class WrapperObjectKey extends CacheableElementKey {

	private static final List<ValueParameterDescriptor> EMPTY_VALUE_PARAMETERS = Collections.emptyList();
	private static final Map<ChildParameterDescriptor, AllowedConstraintPredicate> EMPTY_CHILD_PARAMETERS = Map.of();
	private static final Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> EMPTY_ADDITIONAL_CHILD_PARAMETERS = Map.of();


	/**
	 * Actual value parameters of the object.
	 */
	@Nonnull
	private final List<ValueParameterDescriptor> valueParameters;
	/**
	 * Ahild parameters of the object, defining nested structure with metadata for comparison
	 */
	@Nonnull
	private final Map<ChildParameterDescriptor, AllowedConstraintPredicate> childParameters;
	/**
	 * Additional child parameters of the object, defining nested structure with metadata for comparison
	 */
	@Nonnull
	private final Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> additionalChildParameters;

	public WrapperObjectKey(@Nonnull ConstraintType containerType,
	                        @Nonnull DataLocator dataLocator,
	                        @Nonnull List<ValueParameterDescriptor> valueParameters,
	                        @Nonnull Map<ChildParameterDescriptor, AllowedConstraintPredicate> childParameters,
	                        @Nonnull Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> additionalChildParameters) {
		super(containerType, dataLocator);
		this.valueParameters = valueParameters;
		this.childParameters = childParameters;
		this.additionalChildParameters = additionalChildParameters;
	}

	public WrapperObjectKey(@Nonnull ConstraintType containerType,
	                        @Nonnull DataLocator dataLocator,
	                        @Nonnull List<ValueParameterDescriptor> valueParameters) {
		this(containerType, dataLocator, valueParameters, EMPTY_CHILD_PARAMETERS, EMPTY_ADDITIONAL_CHILD_PARAMETERS);
	}

	public WrapperObjectKey(@Nonnull ConstraintType containerType,
	                        @Nonnull DataLocator dataLocator,
	                        @Nonnull Map<ChildParameterDescriptor, AllowedConstraintPredicate> childParameters,
	                        @Nonnull Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> additionalChildParameters) {
		this(containerType, dataLocator, EMPTY_VALUE_PARAMETERS, childParameters, additionalChildParameters);
	}

	@Override
	@Nonnull
	public String toHash() {
		final LongHashFunction hashFunction = LongHashFunction.xx3();
		final long keyHash;
		if (!this.childParameters.isEmpty() || !this.additionalChildParameters.isEmpty()) {
			// if there is any child parameter, we cannot create hash simply, because for each instance of wrapper object
			// with same parameters, different contexts applies, thus resulting in different inner containers
			keyHash = fullKeyToHash(hashFunction);
		} else {
			// if there is only flat structure of primitive values, we can simplify hash and reuse the object more,
			// because primitive value parameters are not dependent on build context
			keyHash = primitiveKeyToHash(hashFunction);
		}

		return Long.toHexString(keyHash);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		final WrapperObjectKey that = (WrapperObjectKey) o;
		return Objects.equals(toHash(), that.toHash());
	}

	@Override
	public int hashCode() {
		if (getChildParameters().isEmpty() && getAdditionalChildParameters().isEmpty()) {
			// if there is only flat structure of primitive values, we can simplify hash and reuse the object more,
			// because primitive value parameters are not dependent on build context
			return Objects.hash(getValueParameters());
		} else {
			return Objects.hash(getContainerType(), getDataLocator(), getValueParameters(), getChildParameters(), getAdditionalChildParameters());
		}
	}

	/**
	 * Creates hash only from value parameter descriptors.
	 * This is useful when there are no child parameters and thus no nested structure that is dependent on build context.
	 */
	private long primitiveKeyToHash(@Nonnull LongHashFunction hashFunction) {
		return hashValueParameters(hashFunction);
	}

	/**
	 * Creates hash from entire key.
	 * This is needed when there is child parameter and thus potential complex nested structure that is highly dependent
	 * on build context.
	 */
	private long fullKeyToHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(new long[] {
			hashContainerType(hashFunction),
			hashDataLocator(hashFunction),
			hashValueParameters(hashFunction),
			hashChildParameters(hashFunction),
			hashAdditionalChildParameters(hashFunction)
		});
	}

	private long hashValueParameters(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			getValueParameters()
				.stream()
				.sorted(Comparator.comparing(ValueParameterDescriptor::name))
				.mapToLong(parameter -> hashFunction.hashLongs(new long[]{
					hashFunction.hashChars(parameter.name()),
					hashFunction.hashChars(parameter.type().getSimpleName()),
					hashFunction.hashBoolean(parameter.required()),
					hashFunction.hashBoolean(parameter.requiresPlainType())
				}))
				.toArray()
		);
	}

	private long hashChildParameters(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			getChildParameters()
				.entrySet()
				.stream()
				.sorted(Comparator.comparing((entry) ->
					entry.getKey().name()))
				.mapToLong(entry ->
					hashAllowedConstraintPredicate(hashFunction, entry.getValue()))
				.toArray()
		);
	}

	private long hashAdditionalChildParameters(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			getAdditionalChildParameters()
				.entrySet()
				.stream()
				.sorted(Comparator.comparing(entry ->
					entry.getKey().name()))
				.mapToLong(entry ->
					hashAllowedConstraintPredicate(hashFunction, entry.getValue()))
				.toArray()
		);
	}
}
