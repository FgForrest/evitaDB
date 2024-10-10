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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeEndsWith;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.attribute.FilterIndex;

import javax.annotation.Nonnull;
import java.util.function.BiPredicate;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeEndsWith} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeEndsWithTranslator
	extends AbstractAttributeStringSearchTranslator
	implements FilteringConstraintTranslator<AttributeEndsWith> {

	public AttributeEndsWithTranslator() {
		super(
			"ends with",
			FilterIndex::getRecordsWhoseValuesEndsWith,
			createPredicate()
		);
	}

	/**
	 * Creates a BiPredicate that tests whether a given string ends with another specified string.
	 * The predicate will return false if the string to test is null.
	 *
	 * @return a BiPredicate that evaluates to true if the first string ends with the second string, otherwise false
	 */
	@Nonnull
	static BiPredicate<String, String> createPredicate() {
		return (theValue, textToSearch) -> theValue != null && theValue.endsWith(textToSearch);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeEndsWith attributeEndsWith, @Nonnull FilterByVisitor filterByVisitor) {
		return translateInternal(attributeEndsWith, filterByVisitor);
	}

}
