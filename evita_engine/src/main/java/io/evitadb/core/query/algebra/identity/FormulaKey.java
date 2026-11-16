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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Detached identity of one holder and its ordered formula children.
 * Opaque identities retain their holder; value identities retain only detached values and keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
public final class FormulaKey {
	/** Shared child array for leaves. */
	public static final FormulaKey[] NO_CHILDREN = new FormulaKey[0];
	/** Declared identity components. */
	private final IdentityRecord identity;
	/** Owned ordered child keys. */
	private final FormulaKey[] children;
	/** Structural hash used only as a prefilter. */
	private final long hash64;
	/** Saturating retained weight. */
	private final int weight;
	/** Whether the own identity has an opaque input. */
	private final boolean opaque;
	/** Whether any retained input refuses cross-query use. */
	private final boolean queryLocal;
	/** Whether any retained identity transitively contains a side key. */
	private final boolean sideKeys;
	/** Holder retained exclusively for opaque identities. */
	@Nullable private final Object owner;

	/** Captures the finished single-pass description. */
	private FormulaKey(
		@Nonnull IdentityRecord identity, @Nonnull FormulaKey[] children, @Nullable Object owner,
		long hash64, int weight, boolean opaque, boolean queryLocal, boolean sideKeys
	) {
		this.identity = identity;
		this.children = children;
		this.owner = owner;
		this.hash64 = hash64;
		this.weight = weight;
		this.opaque = opaque;
		this.queryLocal = queryLocal;
		this.sideKeys = sideKeys;
	}

	/**
	 * Checks the class identifier and builds a key with one accumulator. The caller transfers ownership of children.
	 *
	 * @param identity declared identity components of the holder
	 * @param expectedClassId the holder's class identifier, which the identity must carry
	 * @param children ordered child keys, owned by the new key from now on
	 * @param owner the holder; retained only when the identity turns out opaque, which binds the key to it
	 * @return the detached key
	 * @throws GenericEvitaInternalError when the identity's class identifier differs from `expectedClassId`
	 */
	@Nonnull
	public static FormulaKey of(
		@Nonnull IdentityRecord identity, long expectedClassId, @Nonnull FormulaKey[] children, @Nonnull Object owner
	) {
		if (identity.classId() != expectedClassId) {
			throw new GenericEvitaInternalError("Identity class identifier differs from its holder's identifier.");
		}
		final KeyAccumulator accumulator = new KeyAccumulator(expectedClassId);
		identity.describeTo(accumulator);
		final boolean opaque = accumulator.isOpaque();
		for (final FormulaKey child : children) {
			accumulator.addChild(child);
		}
		accumulator.mixValue(children.length);
		final long hash = opaque ? KeyAccumulator.mix(KeyAccumulator.mix(0, expectedClassId),
			System.identityHashCode(owner)) : accumulator.hash64();
		return new FormulaKey(
			identity, children, opaque ? owner : null, hash, accumulator.weight(), opaque,
			opaque || accumulator.isQueryLocal(), accumulator.hasSideKeys()
		);
	}

	/** Returns the declared class identifier. */
	public long getClassId() {
		return this.identity.classId();
	}

	/** Returns the structural hash, never a replacement for equality. */
	public long getHash64() {
		return this.hash64;
	}

	/** Returns whether this key is bound to its holder instance. */
	public boolean isOpaque() {
		return this.opaque;
	}

	/** Reports transitive side keys to a parent accumulator. */
	boolean hasSideKeys() {
		return this.sideKeys;
	}

	/** Returns whether cross-query reuse is refused. */
	public boolean isQueryLocal() {
		return this.queryLocal;
	}

	/** Returns saturating retained weight, including children and side keys. */
	public int getWeight() {
		return this.weight;
	}

	/** Memoizes recursive comparisons while comparing canonical children without a scope. */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof final FormulaKey that)) {
			return false;
		}
		if (this.opaque || that.opaque) {
			return this.opaque && that.opaque && this.owner == that.owner;
		}
		if (this.hash64 != that.hash64 || getClassId() != that.getClassId() ||
			this.children.length != that.children.length) {
			return false;
		}
		boolean requiresScope = this.sideKeys || that.sideKeys;
		if (!requiresScope) {
			for (int i = 0; i < this.children.length; i++) {
				if (this.children[i] != that.children[i]) {
					requiresScope = true;
					break;
				}
			}
		}
		if (!requiresScope) {
			return this.identity.equals(that.identity);
		}
		final ComparisonScope scope = ComparisonScope.current();
		scope.enter();
		try {
			if (scope.contains(this, that)) {
				return true;
			}
			if (!compareValues(that)) {
				return false;
			}
			scope.add(this, that);
			return true;
		} finally {
			scope.leave();
		}
	}

	/** Compares the record and ordered children, checking shared children by reference first. */
	private boolean compareValues(@Nonnull FormulaKey other) {
		if (!this.identity.equals(other.identity)) {
			return false;
		}
		for (int i = 0; i < this.children.length; i++) {
			final FormulaKey left = this.children[i];
			final FormulaKey right = other.children[i];
			if (left != right && !left.equals(right)) {
				return false;
			}
		}
		return true;
	}

	/** Folds the precomputed structural hash. */
	@Override
	public int hashCode() {
		return Long.hashCode(this.hash64);
	}
}
