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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The optional {@link AttributeFilterAccelerator accelerators} an attribute declares in one particular {@link Scope} -
 * the scoped carrier that lets a single mutation state a different set for the live and the archived scope, mirroring
 * {@link ScopedAttributeUniquenessType}.
 *
 * Two parameters:
 *
 * - `scope`: the scope (live or archived) the accelerators apply in. The attribute must also have a filter index in
 *   that very scope - it must be `filterable()` or `unique()` there - because accelerating an index that does not
 *   exist is not a state the engine can be in.
 * - `accelerators`: the accelerators themselves. An **empty array is meaningful** - it says "no acceleration in this
 *   scope", which is what every attribute declared before this axis existed means.
 *
 * The array is **copied on the way in but handed back as-is** by `accelerators()`. The asymmetry is deliberate: the
 * copy is paid once per carrier and is what gives the record its value semantics, while an accessor copy would be
 * paid on every read in the schema-to-wire conversions that walk these carriers. Callers must therefore treat the
 * returned array as read-only and must not retain and mutate it.
 *
 * @param scope        the scope in which the listed accelerators are maintained
 * @param accelerators the accelerators maintained in that scope, possibly empty but never null
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ScopedAttributeFilterAccelerators(
	@Nonnull Scope scope,
	@Nonnull AttributeFilterAccelerator... accelerators
) implements Serializable {

	/**
	 * Shared empty array, so that the very common "no accelerator anywhere" case allocates nothing.
	 */
	public static final ScopedAttributeFilterAccelerators[] EMPTY = new ScopedAttributeFilterAccelerators[0];
	/**
	 * Shared empty accelerator array, used whenever a scope declares no acceleration at all.
	 */
	public static final AttributeFilterAccelerator[] NO_ACCELERATORS = new AttributeFilterAccelerator[0];

	public ScopedAttributeFilterAccelerators {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(accelerators, "Accelerators must not be null");
		for (final AttributeFilterAccelerator accelerator : accelerators) {
			Assert.notNull(accelerator, "Accelerator must not be null");
		}
		// the carrier is a component of `@Immutable` mutations whose equality and hash code reach into this array;
		// without the copy a caller retaining the array it passed in could change a validated carrier's contents -
		// and the hash code of a mutation already filed in a hash-keyed collection along with it. The empty case,
		// which is by far the most common one, folds onto the shared instance instead of allocating a copy
		accelerators = accelerators.length == 0 ? NO_ACCELERATORS : accelerators.clone();
	}

	/**
	 * Records compare array components by reference, which would make two carriers listing the very same accelerators
	 * unequal and silently defeat every mutation-combination and change-detection path that relies on equality.
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof final ScopedAttributeFilterAccelerators that)) {
			return false;
		}
		return this.scope == that.scope && Arrays.equals(this.accelerators, that.accelerators);
	}

	@Override
	public int hashCode() {
		int result = this.scope.hashCode();
		result = 31 * result + Arrays.hashCode(this.accelerators);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "ScopedAttributeFilterAccelerators[scope=" + this.scope +
			", accelerators=" + Arrays.toString(this.accelerators) + ']';
	}

}
