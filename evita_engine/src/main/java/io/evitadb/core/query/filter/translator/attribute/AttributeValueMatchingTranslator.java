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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * Implemented by the {@link FilteringConstraintTranslator translators} of the attribute constraints that test a
 * single attribute value - the comparisons, the set, the interval, the range envelope and the string searches. The
 * translator owns the per-value test of its constraint: it creates the {@link AttributeValueMatcher} that answers the
 * constraint from a value, without the index of the attribute.
 *
 * A translator of an attribute constraint that is no test of a single value (`attributeIs` asks whether a value
 * exists at all) does not implement it - the exceptions are named by the invariant
 * {@link io.evitadb.core.query.filter.FilterByVisitor} checks when it is loaded.
 *
 * @param <T> the translated constraint
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface AttributeValueMatchingTranslator<T extends FilterConstraint & AttributeConstraint<FilterConstraint>> {

	/**
	 * Creates the matcher of the constraint over the attribute. The conversion and the normalization of the constraint
	 * value are paid here, once, so the matcher can be applied to any number of values.
	 *
	 * @param constraint        the constraint
	 * @param attributeSchema   the schema of the attribute the constraint is applied to
	 * @param attributeIndexKey key of the filter index the attribute is read from, its locale selects the comparator
	 * @param now               supplier of the moment of the query, consulted by a constraint that names no moment of
	 *                          its own
	 * @return the matcher
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the constraint cannot be applied to the attribute
	 */
	@Nonnull
	AttributeValueMatcher createMatcher(
		@Nonnull T constraint,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Supplier<OffsetDateTime> now
	);

	/**
	 * Tells whether the main query answers the constraint from the unique index of the attribute where it has one.
	 * That is the lookup policy of the operator - `attributeEquals` and `attributeInSet` resolve a value in the unique
	 * index, every other operator reads the filter index - not a fact of the schema. It decides which locales of a
	 * localized attribute the constraint reads.
	 *
	 * @return true when the unique index of the attribute answers the constraint
	 */
	default boolean isAnsweredByUniqueIndex() {
		return false;
	}

}
