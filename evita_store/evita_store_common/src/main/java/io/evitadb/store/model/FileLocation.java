/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.model;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This descriptor points to specific location of the file. It contains location of the first byte of the stored record
 * contents and also total length of the record. From these numbers end location can be easily computed, but there is
 * no place which would require it. When reading starting point and length suffices to perform reading.
 *
 * @param startingPosition Position of the first byte of the record from the file beginning. Indexed from zero.
 * @param recordLength     Number of bytes that belong to the record. Records can have variable size up-to {@link Integer#MAX_VALUE}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record FileLocation(long startingPosition, int recordLength) implements Serializable {
	@Serial private static final long serialVersionUID = 6408882908172452621L;
	public static final FileLocation EMPTY = new FileLocation(0, 0);

	@Nonnull
	@Override
	public String toString() {
		return "location " + this.startingPosition + " [length: " + this.recordLength + "B]";
	}

	/**
	 * Method returns the last position occupied by this record.
	 */
	public long endPosition() {
		return this.startingPosition + this.recordLength;
	}

}
