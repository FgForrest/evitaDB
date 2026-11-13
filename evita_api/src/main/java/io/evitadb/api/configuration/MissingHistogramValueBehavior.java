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

package io.evitadb.api.configuration;

/**
 * What a write does when it has to withdraw a reference-histogram value that the index does not hold for the entity.
 *
 * Before a write changes a reference whose attribute feeds a reference histogram, the engine withdraws the value the
 * reference contributed so far, and it asserts that the value is there. A value that is missing means the derived
 * histogram index has drifted from the stored entities - the entity bodies themselves are intact. This setting
 * decides whether such drift stops the write or is reported and stepped over.
 *
 * Other histogram consistency checks - the final add of the values, the bookkeeping of the references awaiting it,
 * the shape of the values - are not governed by this setting and always fail the write.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum MissingHistogramValueBehavior {

	/**
	 * The write fails with an error naming the entity, the reference, the histogram and the missing value.
	 */
	REFUSE,
	/**
	 * The write logs the same identification at error level, skips the withdrawal of that one value and continues.
	 * The line starts with `Cannot withdraw histogram value` and ends by stating that the write continues because the
	 * setting is `REPORT`. Where the index the histogram lives in is missing altogether, its values are reported too,
	 * and the histogram checks fail the write only if it goes on to add a value into that index - the reference's new
	 * value, typically - on a check this setting does not govern; a write that adds nothing there, such as the removal
	 * of the reference, passes them. The exception is a group index that the catalog still lists for the group but no
	 * longer holds: a write reaching it fails in either mode and nothing is reported for it. The price is a histogram
	 * that may stay wrong where the write itself does not repair it, until the catalog is rebuilt.
	 */
	REPORT

}
