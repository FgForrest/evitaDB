/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.algebra.locale;

import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.filter.translator.entity.EntityLocaleEqualsTranslator;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;

/**
 * Formula that is identical to {@link ConstantFormula} but allows excluding in {@link EntityLocaleEqualsTranslator}
 * when localized {@link AttributeFormula} is found in conjunctive scope of the entire formula and thus optimizing
 * the calculation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class LocaleFormula extends ConstantFormula {
	private static final long CLASS_ID = 6877689619565475680L;

	public LocaleFormula(@Nonnull Bitmap delegate) {
		super(delegate);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	public String toString() {
		return "LOCALE: " + super.toString();
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "LOCALE: " + super.toStringVerbose();
	}
}
