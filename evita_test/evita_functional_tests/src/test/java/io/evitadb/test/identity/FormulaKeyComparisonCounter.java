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

package io.evitadb.test.identity;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts detached leaf record comparisons while keeping the executable formula a production constant.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FormulaKeyComparisonCounter {
	/** Prevents instantiation of the test instrumentation. */
	private FormulaKeyComparisonCounter() {
	}

	/** Installs a counted equivalent leaf identity before any parent captures the constant's key. */
	public static void observe(@Nonnull ConstantFormula formula, @Nonnull AtomicInteger comparisons) {
		final IdentityRecord identity = new CountedIdentity(
			new ConstantFormula.Identity(IdentityToken.of(formula.compute())), comparisons
		);
		try {
			final Field memo = AbstractFormula.class.getDeclaredField("memoizedKey");
			memo.setAccessible(true);
			memo.set(formula, FormulaKey.of(identity, identity.classId(), FormulaKey.NO_CHILDREN, formula));
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	/**
	 * Delegates the complete constant identity and observes each record equality call.
	 *
	 * @param delegate the unchanged detached constant operand
	 * @param comparisons equality call counter
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record CountedIdentity(
		@Nonnull ConstantFormula.Identity delegate, @Nonnull AtomicInteger comparisons
	) implements IdentityRecord {
		/** Returns the production constant discriminator. */
		@Override
		public long classId() {
			return this.delegate.classId();
		}

		/** Describes exactly the production constant operand. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			this.delegate.describeTo(accumulator);
		}

		/** Counts one comparison before comparing the complete production operand. */
		@Override
		public boolean equals(@Nullable Object other) {
			this.comparisons.incrementAndGet();
			return other instanceof CountedIdentity that && this.delegate.equals(that.delegate);
		}

		/** Returns the production operand hash, excluding instrumentation state. */
		@Override
		public int hashCode() {
			return this.delegate.hashCode();
		}
	}
}
