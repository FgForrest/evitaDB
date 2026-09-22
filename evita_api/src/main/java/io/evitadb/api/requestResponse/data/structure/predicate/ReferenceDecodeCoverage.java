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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Describes exactly how much of an entity's reference set a read was allowed to materialize - which reference names,
 * and within a name, which referenced entity primary keys.
 *
 * A reference storage record holds **every** reference of an entity, including every back-reference pointing at it,
 * while a projection typically asks for one name and a handful of keys within it. This type is the vocabulary in which
 * that narrowing is expressed, carried and - crucially - *remembered*, so that a value decoded under a narrowing can
 * never be mistaken for the entity's complete reference set.
 *
 * Two axes, deliberately separate rather than folded into one map with a sentinel:
 *
 * - {@link #getNamesDecodedWhole()} - names the read materialized in full. A question about the *absence* of a
 *   reference of such a name can be answered from the decoded data.
 * - {@link #getNamesDecodedByKey()} - names the read materialized only for an explicit, sorted set of referenced
 *   entity primary keys. Nothing about the absence of a reference of such a name may be concluded, because a key
 *   outside the set was skipped rather than found missing.
 *
 * A NULL coverage reference - never an instance of this class - means "no narrowing at all": every name, every key.
 * That is the value the write path requires and the value an unrestricted read binds, and it is why callers ask
 * {@link #isComplete(ReferenceDecodeCoverage)} rather than testing a field.
 *
 * **Instances are immutable and are compared by content.** The key arrays are defensively copied on the way in,
 * never handed out, and the hash code is precomputed - this type participates in cache record identity, where it is
 * hashed on every storage read, and in a thread-bound decode filter, where the deserializer compares it by identity
 * to memoize a per-name decision across a run of references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see ReferenceContractSerializablePredicate
 */
public final class ReferenceDecodeCoverage implements Serializable {
	@Serial private static final long serialVersionUID = 4471928369213845861L;

	/**
	 * Names whose references were decoded in full - see the class javadoc for why this axis is kept apart from
	 * {@link #namesDecodedByKey}.
	 */
	@Nonnull private final Set<String> namesDecodedWhole;
	/**
	 * Names whose references were decoded only for the listed referenced entity primary keys. The arrays are sorted
	 * ascending so that a decoder walking a name's run - which is itself ordered by referenced primary key - can
	 * merge-walk them and stop probing once it passes the last wanted key.
	 */
	@Nonnull private final Map<String, int[]> namesDecodedByKey;
	/**
	 * Precomputed because this type is hashed on every storage record lookup.
	 */
	private final int hashCode;

	/**
	 * Tells whether the passed coverage imposes no narrowing whatsoever, i.e. whether a value carrying it is the
	 * entity's complete reference set.
	 *
	 * Expressed as a static method taking a NULLable argument on purpose: "no narrowing" is the absence of a
	 * coverage object, so every caller would otherwise have to write the null check itself and some would forget.
	 *
	 * @param coverage the coverage to examine, NULL when the value was produced by an unrestricted read
	 * @return true when nothing was narrowed away
	 */
	public static boolean isComplete(@Nullable ReferenceDecodeCoverage coverage) {
		return coverage == null;
	}

	/**
	 * Creates a coverage that lets through the passed reference names in full and nothing else.
	 *
	 * This is the shape the reference *name* narrowing produces, and passing an empty set is legal - it describes
	 * a read that materialized no reference at all.
	 *
	 * @param referenceNames names the read may materialize in full
	 * @return coverage over those names
	 */
	@Nonnull
	public static ReferenceDecodeCoverage ofNames(@Nonnull Set<String> referenceNames) {
		return new ReferenceDecodeCoverage(Set.copyOf(referenceNames), Collections.emptyMap());
	}

	/**
	 * Creates a coverage from both axes at once.
	 *
	 * @param namesDecodedWhole names the read may materialize in full
	 * @param namesDecodedByKey names the read may materialize only for the listed referenced primary keys; the arrays
	 *                          are copied and sorted, and the caller keeps ownership of the ones it passed
	 * @return coverage over both axes
	 */
	@Nonnull
	public static ReferenceDecodeCoverage of(
		@Nonnull Set<String> namesDecodedWhole,
		@Nonnull Map<String, int[]> namesDecodedByKey
	) {
		final Map<String, int[]> copiedKeys = new LinkedHashMap<>(namesDecodedByKey.size());
		for (final Entry<String, int[]> entry : namesDecodedByKey.entrySet()) {
			Assert.isPremiseValid(
				!namesDecodedWhole.contains(entry.getKey()),
				() -> "Reference name `" + entry.getKey() + "` cannot be decoded both whole and by key!"
			);
			final int[] sortedCopy = entry.getValue().clone();
			Arrays.sort(sortedCopy);
			copiedKeys.put(entry.getKey(), sortedCopy);
		}
		return new ReferenceDecodeCoverage(Set.copyOf(namesDecodedWhole), copiedKeys);
	}

	/**
	 * Unions two ascending, duplicate-free key arrays into a third one of the same shape.
	 *
	 * Lives here because both sides of the narrowing need it: the request unions the key sets of several
	 * requirements naming one reference, and an enriching predicate unions its own set with the new request's.
	 *
	 * @param left  first sorted key set
	 * @param right second sorted key set
	 * @return their union, sorted ascending and free of duplicates
	 */
	@Nonnull
	public static int[] unionSortedKeys(@Nonnull int[] left, @Nonnull int[] right) {
		final int[] merged = new int[left.length + right.length];
		int l = 0;
		int r = 0;
		int w = 0;
		while (l < left.length && r < right.length) {
			if (left[l] < right[r]) {
				merged[w++] = left[l++];
			} else if (left[l] > right[r]) {
				merged[w++] = right[r++];
			} else {
				merged[w++] = left[l++];
				r++;
			}
		}
		while (l < left.length) {
			merged[w++] = left[l++];
		}
		while (r < right.length) {
			merged[w++] = right[r++];
		}
		return w == merged.length ? merged : Arrays.copyOf(merged, w);
	}

	/**
	 * Tells whether two per-name key narrowings describe the same thing, comparing the arrays by content.
	 *
	 * @param left  first narrowing
	 * @param right second narrowing
	 * @return true when they bind the same names to the same keys
	 */
	public static boolean sameNarrowing(@Nonnull Map<String, int[]> left, @Nonnull Map<String, int[]> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (final Entry<String, int[]> entry : left.entrySet()) {
			if (!Arrays.equals(entry.getValue(), right.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private ReferenceDecodeCoverage(
		@Nonnull Set<String> namesDecodedWhole,
		@Nonnull Map<String, int[]> namesDecodedByKey
	) {
		this.namesDecodedWhole = namesDecodedWhole;
		this.namesDecodedByKey = namesDecodedByKey;
		int computedHash = namesDecodedWhole.hashCode();
		for (final Entry<String, int[]> entry : namesDecodedByKey.entrySet()) {
			// entry-wise so the result does not depend on iteration order, matching the content equality below
			computedHash += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
		}
		this.hashCode = computedHash;
	}

	/**
	 * Returns the names this coverage lets through in full.
	 *
	 * @return the names decoded whole, never NULL
	 */
	@Nonnull
	public Set<String> getNamesDecodedWhole() {
		return this.namesDecodedWhole;
	}

	/**
	 * Returns the names this coverage lets through for an explicit key set only, mapped to those sorted keys.
	 *
	 * @return the names decoded by key, never NULL
	 */
	@Nonnull
	public Map<String, int[]> getNamesDecodedByKey() {
		return this.namesDecodedByKey;
	}

	/**
	 * Tells whether a value carrying this coverage can answer questions about the *absence* of a reference of
	 * the passed name - which requires that the name was decoded whole.
	 *
	 * A name narrowed to a key set answers FALSE: a reference outside that set was skipped, not found missing, and
	 * reading the difference as absence is the failure mode this whole type exists to prevent.
	 *
	 * @param referenceName name of the reference the caller asks about
	 * @return true when every reference of that name was materialized
	 */
	public boolean isNameDecodedWhole(@Nonnull String referenceName) {
		return this.namesDecodedWhole.contains(referenceName);
	}

	/**
	 * Tells whether this coverage lets the passed referenced entity primary key of the passed reference name through.
	 *
	 * @param referenceName        name of the reference
	 * @param referencedPrimaryKey primary key of the referenced entity
	 * @return true when a reference with that name and key may be materialized
	 */
	public boolean isReferenceDecoded(@Nonnull String referenceName, int referencedPrimaryKey) {
		if (this.namesDecodedWhole.contains(referenceName)) {
			return true;
		}
		final int[] keys = this.namesDecodedByKey.get(referenceName);
		return keys != null && Arrays.binarySearch(keys, referencedPrimaryKey) >= 0;
	}

	/**
	 * Tells whether everything the passed coverage lets through is also let through by this one, i.e. whether a read
	 * already performed under this coverage satisfies a request asking for `other`.
	 *
	 * This is what decides whether an enrichment has to go back to the storage. A NULL `other` means the caller wants
	 * everything and is therefore covered by nothing narrower than a complete read.
	 *
	 * @param other the coverage the caller now wants, NULL when it wants everything
	 * @return true when this coverage already includes all of it
	 */
	public boolean covers(@Nullable ReferenceDecodeCoverage other) {
		if (other == null) {
			// the caller wants everything, and this coverage is by construction narrower than that
			return false;
		}
		for (final String referenceName : other.namesDecodedWhole) {
			if (!this.namesDecodedWhole.contains(referenceName)) {
				return false;
			}
		}
		for (final Entry<String, int[]> entry : other.namesDecodedByKey.entrySet()) {
			if (this.namesDecodedWhole.contains(entry.getKey())) {
				continue;
			}
			final int[] ourKeys = this.namesDecodedByKey.get(entry.getKey());
			if (ourKeys == null) {
				return false;
			}
			for (final int wantedKey : entry.getValue()) {
				if (Arrays.binarySearch(ourKeys, wantedKey) < 0) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		return this.hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof final ReferenceDecodeCoverage that)) {
			return false;
		}
		if (this.hashCode != that.hashCode || !this.namesDecodedWhole.equals(that.namesDecodedWhole)) {
			return false;
		}
		if (this.namesDecodedByKey.size() != that.namesDecodedByKey.size()) {
			return false;
		}
		for (final Entry<String, int[]> entry : this.namesDecodedByKey.entrySet()) {
			if (!Arrays.equals(entry.getValue(), that.namesDecodedByKey.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("ReferenceDecodeCoverage{whole=").append(this.namesDecodedWhole);
		if (!this.namesDecodedByKey.isEmpty()) {
			sb.append(", byKey={");
			boolean first = true;
			for (final Entry<String, int[]> entry : this.namesDecodedByKey.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				sb.append(entry.getKey()).append('=').append(entry.getValue().length).append(" key(s)");
				first = false;
			}
			sb.append('}');
		}
		return sb.append('}').toString();
	}

}
