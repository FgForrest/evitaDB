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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;

import javax.annotation.Nonnull;

/**
 * Makes the translator's production presence factories reachable from alternative identity tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class AttributeIsTranslatorTestFixture {
	/** Utility fixture with no instance state. */
	private AttributeIsTranslatorTestFixture() {
	}

	/** Constructs the actual presence alternative without inspecting a formula's private state. */
	@Nonnull
	public static AttributeBitmapFilter createAlternative(
		@Nonnull AttributeIs constraint, @Nonnull FilterByVisitor visitor
	) {
		return switch (constraint.getAttributeSpecialValue()) {
			case NULL -> AttributeIsTranslator.createAlternativeNullBitmapFilter(constraint, visitor);
			case NOT_NULL -> AttributeIsTranslator.createAlternativeNotNullBitmapFilter(constraint, visitor);
		};
	}
}
