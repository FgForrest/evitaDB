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

import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.Getter;
import lombok.ToString;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
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
public class WrapperObjectKey extends CachableElementKey {

	/**
	 * Actual value parameters of the object.
	 */
	@Nonnull
	private final List<ValueParameterDescriptor> valueParameters;
	/**
	 * Actual child parameter of the object, defining nested structure
	 */
	@Nullable
	private final ChildParameterDescriptor childParameter;

	public WrapperObjectKey(@Nonnull ConstraintType containerType,
	                        @Nonnull DataLocator dataLocator,
	                        @Nonnull List<ValueParameterDescriptor> valueParameters,
	                        @Nullable ChildParameterDescriptor childParameter) {
		super(containerType, dataLocator);
		this.valueParameters = valueParameters;
		this.childParameter = childParameter;
	}

	@Override
	@Nonnull
	public String toHash() {
		final LongHashFunction hashFunction = LongHashFunction.xx3();
		final long keyHash;
		if (childParameter == null) {
			// if there is only flat structure of primitive values, we can simplify hash and reuse the object more,
			// because primitive value parameters are not dependent on build context
			keyHash = primitiveKeyToHash(hashFunction);
		} else {
			keyHash = fullKeyToHash(hashFunction);
		}
		return Long.toHexString(keyHash);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		final WrapperObjectKey that = (WrapperObjectKey) o;
		if (getChildParameter() == null) {
			return that.getChildParameter() == null &&
				Objects.equals(getValueParameters(), that.getValueParameters());
		} else {
			return super.equals(o) &&
				Objects.equals(getValueParameters(), that.getValueParameters()) &&
				Objects.equals(getChildParameter(), that.getChildParameter());
		}
	}

	@Override
	public int hashCode() {
		if (childParameter == null) {
			// if there is only flat structure of primitive values, we can simplify hash and reuse the object more,
			// because primitive value parameters are not dependent on build context
			return Objects.hash(getValueParameters());
		} else {
			return Objects.hash(getContainerType(), getDataLocator(), getValueParameters(), getChildParameter());
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
			hashChildParameter(hashFunction)
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

	private long hashChildParameter(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(new long[]{
			hashFunction.hashChars(childParameter.name()),
			hashFunction.hashChars(childParameter.type().getSimpleName()),
			hashFunction.hashBoolean(childParameter.required()),
			hashFunction.hashBoolean(childParameter.uniqueChildren()),
			hashFunction.hashLongs(
				childParameter.allowedChildTypes()
					.stream()
					.map(Class::getSimpleName)
					.sorted()
					.mapToLong(hashFunction::hashChars)
					.toArray()
			),
			hashFunction.hashLongs(
				childParameter.forbiddenChildTypes()
					.stream()
					.map(Class::getSimpleName)
					.sorted()
					.mapToLong(hashFunction::hashChars)
					.toArray()
			)
		});
	}
}
