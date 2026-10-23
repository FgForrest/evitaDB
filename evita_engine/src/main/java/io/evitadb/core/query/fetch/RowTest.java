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

package io.evitadb.core.query.fetch;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A node of the compiled filter of the fetched references ({@link ReferenceRowFilter}): a test of one reference row of
 * one owner entity. The nodes mirror the constraints the filter admits one to one - the logical containers, the
 * `inScope` container, the owner locale, the referenced key, the nested `entityHaving` / `groupHaving` sets and the
 * attribute leaves - and there is no node that stands for "nothing": a constraint without a meaning on a row is
 * refused by {@link ReferenceRowFilterCompiler}, never compiled into a node answering a constant.
 *
 * A node reads nothing but the owner's scope and locales, the row itself and the nested sets, so the verdict of a row
 * never depends on any other owner or row.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
sealed interface RowTest {

	/**
	 * Tests one reference row of an owner entity.
	 *
	 * @param ownerScope the scope of the owner entity
	 * @param owner      the owner entity - the raw entity, never a decorator hiding part of it
	 * @param row        the reference row of the owner entity
	 * @return true when the row satisfies the node
	 */
	boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row);

	/**
	 * Returns the keys an attribute node reads in the scope of the owner. A node outside every `inScope` is compiled
	 * for the scopes the references are looked up in and for the scopes of all owners whose rows the fetch tests - an
	 * owner may live outside the scopes of the request - and a node inside `inScope` is reached by the owners of its
	 * scope alone, so a missing entry is a programming error.
	 *
	 * @param keysByScope the keys indexed by {@link Scope#ordinal()}
	 * @param ownerScope  the scope of the owner entity
	 * @return the keys to read
	 */
	@Nonnull
	private static AttributeKey[] getKeys(@Nonnull AttributeKey[][] keysByScope, @Nonnull Scope ownerScope) {
		final AttributeKey[] keys = keysByScope[ownerScope.ordinal()];
		if (keys == null) {
			throw new GenericEvitaInternalError(
				"The row filter was not compiled for the scope `" + ownerScope + "` of the owner entity!"
			);
		}
		return keys;
	}

	/**
	 * Returns the value of the attribute stored under the key, NULL when the row carries none or has dropped it.
	 *
	 * @param row the reference row
	 * @param key the key of the attribute
	 * @return the value, or NULL
	 */
	@Nullable
	private static Serializable getValue(@Nonnull ReferenceContract row, @Nonnull AttributeKey key) {
		final Optional<AttributeValue> attributeValue = row.getAttributeValue(key);
		return attributeValue.isPresent() && attributeValue.get().exists() ? attributeValue.get().value() : null;
	}

	/**
	 * Satisfied when every child is.
	 *
	 * @param children the conjoined nodes
	 */
	record And(@Nonnull RowTest[] children) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			for (RowTest child : this.children) {
				if (!child.test(ownerScope, owner, row)) {
					return false;
				}
			}
			return true;
		}

	}

	/**
	 * Satisfied when any child is.
	 *
	 * @param children the disjoined nodes
	 */
	record Or(@Nonnull RowTest[] children) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			for (RowTest child : this.children) {
				if (child.test(ownerScope, owner, row)) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * Satisfied when the child is not - the complement within the rows of the owner, the same rows the child judges.
	 *
	 * @param child the negated node
	 */
	record Not(@Nonnull RowTest child) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			return !this.child.test(ownerScope, owner, row);
		}

	}

	/**
	 * Applies the child to the rows of the owners living in the scope only - the rows of an owner living elsewhere
	 * satisfy it, as the `inScope` container restricts nothing outside its scope.
	 *
	 * @param scope the scope of the container
	 * @param child the node applied in the scope
	 */
	record InScope(@Nonnull Scope scope, @Nonnull RowTest child) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			return ownerScope != this.scope || this.child.test(ownerScope, owner, row);
		}

	}

	/**
	 * Satisfied by every row of an owner holding the locale - `entityLocaleEquals` speaks about the owner entity, so
	 * the answer is the same for all rows of one owner.
	 *
	 * @param locale the locale the owner entity must hold
	 */
	record OwnerLocale(@Nonnull Locale locale) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			return owner.getAllLocales().contains(this.locale);
		}

	}

	/**
	 * Satisfied by a row referencing one of the primary keys.
	 *
	 * @param sortedPrimaryKeys the referenced primary keys, ascending
	 */
	record TargetIn(@Nonnull int[] sortedPrimaryKeys) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			return Arrays.binarySearch(this.sortedPrimaryKeys, row.getReferencedPrimaryKey()) >= 0;
		}

	}

	/**
	 * Satisfied by a row whose referenced entity the nested `entityHaving` filter selects.
	 *
	 * @param nestedSet the entities the nested filter selects
	 */
	record TargetInNested(@Nonnull NestedSet nestedSet) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			return this.nestedSet.contains(row.getReferencedPrimaryKey());
		}

	}

	/**
	 * Satisfied by a row whose group the nested `groupHaving` filter selects - a row without a group, or with a
	 * dropped one, satisfies nothing.
	 *
	 * @param nestedSet the groups the nested filter selects
	 */
	record GroupInNested(@Nonnull NestedSet nestedSet) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			final Optional<GroupEntityReference> group = row.getGroup();
			return group.isPresent() && group.get().exists() && this.nestedSet.contains(group.get().primaryKey());
		}

	}

	/**
	 * Satisfied by a row carrying, under any of the keys read in the scope of its owner, a value the matcher accepts.
	 * The keys of a scope are chosen by the compiler from that scope's own declaration of the attribute: a single key
	 * (the locale of the leaf, or none for a non-localized attribute), one key per locale of the owner's schema where
	 * the value counts in any locale, or no key at all where no value can count.
	 *
	 * @param keysByScope the keys read in each scope, indexed by {@link Scope#ordinal()}; NULL for a scope the node
	 *                    was not compiled for
	 * @param matcher     the test of a value
	 */
	record AttributeMatch(
		@Nonnull AttributeKey[][] keysByScope,
		@Nonnull AttributeValueMatcher matcher
	) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			for (AttributeKey key : getKeys(this.keysByScope, ownerScope)) {
				if (this.matcher.matches(getValue(row, key))) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * The `attributeIs` test: satisfied by a row that carries a value under any of the keys read in the scope of its
	 * owner when `present` is true, and by a row that carries none when it is false. A dropped value and an empty array
	 * are no value - the filter index keeps no entry for either.
	 *
	 * @param keysByScope the keys read in each scope, indexed by {@link Scope#ordinal()}; NULL for a scope the node
	 *                    was not compiled for
	 * @param present     true for `NOT_NULL`, false for `NULL`
	 */
	record AttributePresence(
		@Nonnull AttributeKey[][] keysByScope,
		boolean present
	) implements RowTest {

		@Override
		public boolean test(@Nonnull Scope ownerScope, @Nonnull Entity owner, @Nonnull ReferenceContract row) {
			for (AttributeKey key : getKeys(this.keysByScope, ownerScope)) {
				final Serializable value = getValue(row, key);
				if (value != null && !(value instanceof Object[] elements && elements.length == 0)) {
					return this.present;
				}
			}
			return !this.present;
		}

	}

}
