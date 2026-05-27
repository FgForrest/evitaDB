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

package io.evitadb.index;

import io.evitadb.index.attribute.FilterIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;

/**
 * Contract for entity indexes that support histogram value storage. Both {@link ReferencedTypeEntityIndex}
 * and {@link ReducedGroupEntityIndex} maintain histogram indexes and share identical method signatures
 * for histogram operations — this interface allows callers to work with either type polymorphically.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface HistogramCapableEntityIndex {

	/**
	 * Returns the {@link HistogramIndex} for the given histogram name, or `null` if none exists.
	 *
	 * @param histogramName the name of the histogram definition
	 * @return the histogram index, or `null`
	 */
	@Nullable
	HistogramIndex getHistogramIndex(@Nonnull String histogramName);

	/**
	 * Returns the histogram filter index for the given histogram name and locale, or `null` if no such
	 * histogram has been indexed yet.
	 *
	 * @param histogramName the name of the histogram definition
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @return the filter index containing histogram value-to-ownerPK mappings, or `null`
	 */
	@Nullable
	FilterIndex getHistogramFilterIndex(@Nonnull String histogramName, @Nullable Locale locale);

	/**
	 * Inserts a histogram value for the given owner entity. Delegates to the appropriate
	 * {@link HistogramIndex}, creating it lazily if needed.
	 *
	 * @param histogramName the name of the histogram definition
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @param value         the histogram value in its original type (a `Number` for plain numeric
	 *                      attributes or a `Range` instance for Range-typed attributes)
	 * @param ownerPK       the primary key of the owner entity
	 * @param valueType     the plain type of the value (used for lazy index creation)
	 */
	void insertHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		@Nonnull Class<? extends Serializable> valueType
	);

	/**
	 * Removes a histogram value for the given owner entity. Delegates to the appropriate
	 * {@link HistogramIndex} and removes it from the map if it becomes empty.
	 *
	 * @param histogramName the name of the histogram definition
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @param value         the histogram value in its original numeric type
	 * @param ownerPK       the primary key of the owner entity
	 */
	void removeHistogramValue(
		@Nonnull String histogramName, @Nullable Locale locale, @Nonnull Serializable value, int ownerPK
	);

}
