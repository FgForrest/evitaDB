/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Implementations of this interface can introspect the labels present in the traffic recording.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface LabelIntrospector {

	/**
	 * Returns a stream of all unique labels names ordered by cardinality of their values present in the traffic recording.
	 *
	 * @param nameStartingWith optional prefix to filter the labels by
	 * @param limit            maximum number of labels to return
	 * @return collection of unique label names ordered by cardinality of their values
	 */
	@Nonnull
	Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit);

	/**
	 * Returns a stream of all unique label values ordered by cardinality present in the traffic recording.
	 *
	 * @param labelName         name of the label to get values for
	 * @param valueStartingWith optional prefix to filter the labels by
	 * @param limit             maximum number of values to return
	 * @return collection of unique label values ordered by cardinality
	 */
	@Nonnull
	Collection<String> getLabelValuesOrderedByCardinality(@Nonnull String labelName, @Nullable String valueStartingWith, int limit);

}
