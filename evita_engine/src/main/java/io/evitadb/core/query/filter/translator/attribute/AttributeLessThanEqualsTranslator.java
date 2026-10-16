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

import io.evitadb.api.query.filter.AttributeLessThanEquals;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeLessThanEquals} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeLessThanEqualsTranslator extends AbstractAttributeComparisonTranslator
	implements FilteringConstraintTranslator<AttributeLessThanEquals>,
	AttributeValueMatchingTranslator<AttributeLessThanEquals> {

	public AttributeLessThanEqualsTranslator() {
		super(
			result -> result <= 0,
			FilterIndex::getRecordsLesserThanEqFormula,
			"lesser than or equals"
		);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeLessThanEquals attributeLessThanEquals, @Nonnull FilterByVisitor filterByVisitor) {
		return translateInternal(attributeLessThanEquals, filterByVisitor, this);
	}

	@Nonnull
	@Override
	public AttributeValueMatcher createMatcher(
		@Nonnull AttributeLessThanEquals attributeLessThanEquals,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Supplier<OffsetDateTime> now
	) {
		return createComparisonMatcher(attributeLessThanEquals, attributeSchema, attributeIndexKey);
	}

	@Nonnull
	@Override
	public Serializable[] getIndexProbeValues(@Nonnull AttributeLessThanEquals attributeLessThanEquals) {
		return new Serializable[]{attributeLessThanEquals.getAttributeValue()};
	}

}
