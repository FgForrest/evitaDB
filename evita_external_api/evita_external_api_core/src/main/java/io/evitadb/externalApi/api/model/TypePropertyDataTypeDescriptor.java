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

package io.evitadb.externalApi.api.model;

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * API-independent data type descriptor for {@link PropertyDescriptor} for referencing other object used as type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class TypePropertyDataTypeDescriptor implements PropertyDataTypeDescriptor {

	@Nonnull
	private final Object typeReference;
	private final boolean nonNull;
	private final boolean list;

	protected TypePropertyDataTypeDescriptor(@Nonnull Object typeReference, boolean nonNull, boolean list) {
		Assert.isPremiseValid(
			typeReference instanceof TypeDescriptor || typeReference instanceof Supplier<?>,
			() -> new ExternalApiInternalError("Unsupported type of type reference.")
		);
		this.typeReference = typeReference;
		this.nonNull = nonNull;
		this.list = list;
	}

	@Nonnull
	public TypeDescriptor typeReference() {
		if (this.typeReference instanceof TypeDescriptor descriptor) {
			return descriptor;
		} else if (this.typeReference instanceof Supplier<?> supplier) {
			final Object descriptor = supplier.get();
			Assert.isPremiseValid(
				descriptor instanceof TypeDescriptor,
				() -> new ExternalApiInternalError("Supplier returned unsupported object.")
			);
			return (TypeDescriptor) descriptor;
		} else {
			throw new ExternalApiInternalError("Unsupported type of type reference.");
		}
	}

	@Override
	public boolean nonNull() {
		return this.nonNull;
	}

	public boolean list() {
		return this.list;
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nullableRef(@Nonnull TypeDescriptor objectReference) {
		return new TypePropertyDataTypeDescriptor(objectReference, false, false);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nonNullRef(@Nonnull TypeDescriptor objectReference) {
		return new TypePropertyDataTypeDescriptor(objectReference, true, false);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nullableListRef(@Nonnull TypeDescriptor objectReference) {
		return new TypePropertyDataTypeDescriptor(objectReference, false, true);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nonNullListRef(@Nonnull TypeDescriptor objectReference) {
		return new TypePropertyDataTypeDescriptor(objectReference, true, true);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nullableRef(@Nonnull Supplier<TypeDescriptor> objectReferenceSupplier) {
		return new TypePropertyDataTypeDescriptor(objectReferenceSupplier, false, false);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nonNullRef(@Nonnull Supplier<TypeDescriptor> objectReferenceSupplier) {
		return new TypePropertyDataTypeDescriptor(objectReferenceSupplier, true, false);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nullableListRef(@Nonnull Supplier<TypeDescriptor> objectReferenceSupplier) {
		return new TypePropertyDataTypeDescriptor(objectReferenceSupplier, false, true);
	}

	@Nonnull
	public static TypePropertyDataTypeDescriptor nonNullListRef(@Nonnull Supplier<TypeDescriptor> objectReferenceSupplier) {
		return new TypePropertyDataTypeDescriptor(objectReferenceSupplier, true, true);
	}
}
