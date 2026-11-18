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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.identity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins runtime failures for missing or mismatched declarations and reflective rejection of inherited declarations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula identity declarations fail loudly")
@Tag(ENGINE)
@Tag(CACHE)
class LoudFailureTest {
	/** Proves the reflective contract closes the inherited-pair hole left by the runtime identifier check. */
	@Test
	@DisplayName("Rejects an inherited identity pair even when the runtime identifier check succeeds")
	void shouldRejectInheritedPair() {
		assertNotNull(new InheritedComputationFormula().getKey());
		final IdentityFixture<InheritedComputationFormula> fixture = () -> Map.of("inherited",
			new ConstructionMode<>(InheritedComputationFormula::new, Map.of(), Map.of(), Map.of(),
				Map.of(), Set.of("bitmaps"), Set.of("bitmaps"), Set.of()));
		final AssertionError error = assertThrows(AssertionError.class,
			() -> IdentityContractVerifier.verify(InheritedComputationFormula.class, fixture));
		assertTrue(error.getMessage().contains("check 12: identity not declared"), error.getMessage());
	}

	/** Proves a class identifier without its matching identity cannot produce a key. */
	@Test
	@DisplayName("Rejects a class identifier paired with an inherited identity")
	void shouldRejectMismatchedClassIdentifier() {
		final MismatchedClassFormula formula = new MismatchedClassFormula();

		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, formula::getKey);

		assertEquals("Identity class identifier differs from its holder's identifier.", error.getMessage());
	}

	/** Builds a fresh exact child without shared state. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/**
	 * Deliberately inherits the identity pair while changing the computation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class InheritedComputationFormula extends AndFormula {
		/** Supplies the operands for the inherited conjunction. */
		private InheritedComputationFormula() {
			super(leaf(1), leaf(2));
		}

		/** Deliberately returns one child instead of their conjunction. */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			return getInnerFormulas()[0].compute();
		}
	}

	/**
	 * Deliberately declares a different class identifier while inheriting its parent's identity.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class MismatchedClassFormula extends AndFormula {
		/** Stable identifier deliberately absent from the inherited identity. */
		private static final long CLASS_ID = 5551261709886433149L;

		/** Supplies valid operands without declaring an identity. */
		private MismatchedClassFormula() {
			super(leaf(1), leaf(2));
		}

		/** Returns an identifier that differs from the inherited identity's identifier. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}
	}

}
