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

package io.evitadb.api.requestResponse.trafficRecording;


import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Label associated with the query / source query.
 * TODO JNO - add to source query as well, source query labels automatically propagate to queries
 *
 * @param name  the name of the label
 * @param value the value of the label
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record Label(
	@Nonnull String name,
	@Nonnull Serializable value
) implements Comparable<Label> {

	public static final Label[] EMPTY_LABELS = new Label[0];

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	public int compareTo(@Nonnull Label that) {
		final int first = this.name.compareTo(that.name);
		if (first != 0) {
			return first;
		} else if (this.value instanceof Comparable comparable && this.value.getClass().isInstance(that.value)) {
			return comparable.compareTo(that.value);
		} else {
			return this.value.toString().compareTo(that.value.toString());

		}
	}
}
