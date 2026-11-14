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

package io.evitadb.core.query.algebra.base;

import io.evitadb.test.identity.*;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link EmptyFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EmptyFormulaIdentityFixture implements IdentityFixture<EmptyFormula> {
	/** Covers the stateless value and rejected child cloning. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<EmptyFormula>> modes() {
		return Map.of("stateless", new ConstructionMode<>(EmptyFormulaIdentityFixture::fresh,
			Map.of(), Map.of(), Map.of(), Map.of("inner formulas",
				new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), REJECTED)),
			Set.of(), Set.of(), Set.of()));
	}

	/** Invokes the real private constructor to keep reflection probes off the shared singleton. */
	@Nonnull
	private static EmptyFormula fresh() {
		try {
			final Constructor<EmptyFormula> constructor = EmptyFormula.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Cannot construct the stateless formula", ex);
		}
	}
}
