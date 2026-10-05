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
import io.evitadb.core.buffer.DataStoreChanges;
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
 * access the guard must report. The test scans the bytecode of this class and of its nested and synthetic classes
 * with the same scanner it runs over the engine and asserts that each member is reported under the expected rule - so
 * a scanner that silently stopped seeing a shape fails here instead of passing everywhere else.
 *
 * The fixture also carries shapes the guard must not report - the group family, a key of another index type, a
 * by-key lookup and a read through the partition handle. The test expects exactly the listed hits, so a scanner that
 * started reporting one of these fails too.
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

	/**
	 * An instantiation (rule R1, `NEW`).
	 *
	 * @param key any key
	 * @return the index
	 */
	@Nonnull
	static Object newShape(@Nonnull EntityIndexKey key) {
		return new ReducedEntityIndex(1, "any", key);
	}

	/**
	 * An array creation (rule R1, `ANEWARRAY`).
	 *
	 * @return the array
	 */
	@Nonnull
	static Object arrayShape() {
		return new ReducedEntityIndex[0];
	}

	/**
	 * A method whose parameter names the type in its descriptor (rule R1).
	 *
	 * @param index any reduced entity index
	 * @return its primary key
	 */
	static int descriptorShape(@Nonnull ReducedEntityIndex index) {
		return index.getPrimaryKey();
	}

	/**
	 * A method whose only trace of the type is the declared type of a local variable (rule R1).
	 *
	 * @return true, always
	 */
	static boolean localVariableShape() {
		final AbstractReducedEntityIndex local = null;
		return local == null;
	}

	/**
	 * A class header naming the type as its superclass (rule R1). Its constructor calls the super constructor, which
	 * the guard reports as a member access of the type.
	 */
	abstract static class ExtendsShape extends AbstractReducedEntityIndex {
		/**
		 * Never called.
		 *
		 * @param key any key
		 */
		ExtendsShape(@Nonnull EntityIndexKey key) {
			super(1, "any", key);
		}
	}

	/**
	 * A classic `switch` over the index type (rule R2). javac compiles it into a synthetic switch map class, whose
	 * static initializer is where the guard sees the constant.
	 *
	 * @param type any index type
	 * @return 1 for a partition key type
	 */
	static int enumSwitchShape(@Nonnull EntityIndexType type) {
		return switch (type) {
			case REFERENCED_ENTITY -> 1;
			default -> 0;
		};
	}

	/**
	 * A raw by-primary-key acquisition for writing (rule R3).
	 *
	 * @param changes any buffer of changes
	 * @return the index or `null`
	 */
	@Nullable
	static EntityIndex modificationByPrimaryKeyShape(@Nonnull DataStoreChanges changes) {
		return changes.getIndexForModification(1, pk -> null);
	}

	/**
	 * An enumeration of every partition primary key through a callback (rule R4).
	 *
	 * @param typeIndex any type index
	 */
	static void enumerationByCallbackShape(@Nonnull ReferencedTypeEntityIndex typeIndex) {
		typeIndex.forEachReferenceIndexPrimaryKey(pk -> {});
	}

	/**
	 * A cast to the group family, which is not a partition - never reported.
	 *
	 * @param index any index
	 * @return the owners
	 */
	@Nonnull
	static Bitmap groupFamilyShape(@Nonnull Index<?> index) {
		return ((ReducedGroupEntityIndex) index).getAllPrimaryKeys();
	}

	/**
	 * A key of the group family, which is not a partition key - never reported.
	 *
	 * @return the key
	 */
	@Nonnull
	static EntityIndexKey groupKeyShape() {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey("any", 1))
		);
	}

	/**
	 * A by-key lookup, which cannot confuse the two reduced index families - never reported.
	 *
	 * @param collection any collection
	 * @param key        any key
	 * @return the index or `null`
	 */
	@Nullable
	static EntityIndex byKeyShape(@Nonnull EntityCollection collection, @Nonnull EntityIndexKey key) {
		return collection.getIndexIfExists(key, __ -> null);
	}

	/**
	 * One overload of a name - the cast is written in the method itself.
	 *
	 * @param index any index
	 * @return the owners
	 */
	@Nonnull
	static Bitmap overloadShape(@Nonnull Index<?> index) {
		return ((ReducedEntityIndex) index).getAllPrimaryKeys();
	}

	/**
	 * Another overload of the same name - the type test is written in a lambda body, which counts as this overload.
	 *
	 * @param index any index
	 * @param times how many times to test it
	 * @return the predicate testing it
	 */
	@Nonnull
	static Predicate<Object> overloadShape(@Nonnull Object index, int times) {
		return candidate -> times > 0 && candidate instanceof ReducedEntityIndex;
	}

	/**
	 * A read through the partition handle, which is the sanctioned route - never reported.
	 *
	 * @param partition any partition
	 * @return the owners
	 */
	@Nonnull
	static Bitmap handleShape(@Nonnull ReferencePartition partition) {
		return partition.getAllPrimaryKeys();
	}

}
