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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Counterfactual input of {@link ReferencePartitionAccessGuardTest}: one member per shape of direct reduced index
 * access the guard must report. The test scans this class's bytecode with the same scanner it runs over the engine
 * and asserts that each member is reported under the expected rule - so a scanner that silently stopped seeing a shape
 * fails here instead of passing everywhere else.
 *
 * Nothing here is ever executed; the members only have to compile into the bytecode shape they are named after.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings({"unused", "DataFlowIssue"})
final class ReferencePartitionGuardFixture {
	/**
	 * A generic field whose only trace of the type is its generic signature (rule R1).
	 */
	@Nullable private List<ReducedEntityIndex> genericField;

	private ReferencePartitionGuardFixture() {
		// never instantiated
	}

	/**
	 * A cast (rule R1, `CHECKCAST`).
	 *
	 * @param index any index
	 * @return the owners
	 */
	@Nonnull
	static Bitmap castShape(@Nonnull Index<?> index) {
		return ((ReducedEntityIndex) index).getAllPrimaryKeys();
	}

	/**
	 * A type test (rule R1, `INSTANCEOF`).
	 *
	 * @param index any index
	 * @return true for a reduced entity index
	 */
	static boolean instanceofShape(@Nonnull Object index) {
		return index instanceof ReducedEntityIndex;
	}

	/**
	 * A class token (rule R1, `LDC` of a type).
	 *
	 * @return the class token
	 */
	@Nonnull
	static Object classTokenShape() {
		return ReducedEntityIndex.class;
	}

	/**
	 * A method whose only trace of the type is its generic return signature (rule R1).
	 *
	 * @return nothing, never called
	 */
	@Nullable
	static Supplier<ReducedEntityIndex[]> genericSignatureShape() {
		return null;
	}

	/**
	 * A method reference whose only trace of the type is the `invokedynamic` call site (rule R1).
	 *
	 * @return the function
	 */
	@Nonnull
	static Object methodReferenceShape() {
		return (Function<ReducedEntityIndex, Bitmap>) ReducedEntityIndex::getAllPrimaryKeys;
	}

	/**
	 * A partition key built by hand (rule R2).
	 *
	 * @return the key
	 */
	@Nonnull
	static EntityIndexKey keyShape() {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey("any", 1))
		);
	}

	/**
	 * A raw by-primary-key lookup through the method Lombok delegates from `DataStoreReader` onto
	 * {@link EntityCollection} - it does not appear in that class's source at all (rule R3).
	 *
	 * @param collection any collection
	 * @return the index or `null`
	 */
	@Nullable
	static Index<?> delegatedRawPrimaryKeyShape(@Nonnull EntityCollection collection) {
		return collection.getIndexIfExists(1, pk -> null);
	}

	/**
	 * A raw by-primary-key lookup whose result is read without ever naming the type (rule R3).
	 *
	 * @param collection any collection
	 * @return the owners
	 */
	@Nonnull
	static Bitmap untypedRawPrimaryKeyShape(@Nonnull EntityCollection collection) {
		return collection.getIndexByPrimaryKeyIfExists(1).getAllPrimaryKeys();
	}

	/**
	 * An enumeration of a target's partition primary keys (rule R4).
	 *
	 * @param typeIndex any type index
	 * @return the primary keys
	 */
	@Nonnull
	static int[] enumerationShape(@Nonnull ReferencedTypeEntityIndex typeIndex) {
		return typeIndex.getAllReferenceIndexes(1);
	}

	/**
	 * A violation inside a lambda, which the guard must attribute to this enclosing method (rule R1).
	 *
	 * @return the predicate
	 */
	@Nonnull
	static Predicate<Object> lambdaShape() {
		return candidate -> candidate instanceof ReducedEntityIndex;
	}

}
