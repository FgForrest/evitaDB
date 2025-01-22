/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.spi;


import io.evitadb.core.traffic.TrafficRecorder;
import io.evitadb.store.model.FileLocation;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * This record contains information about the single session to evitaDB. Sessions are assigned a sequence order as
 * they are registered in {@link TrafficRecorder} and are stored to a particular file location.
 *
 * @param sequenceOrder the sequence order of the session
 * @param fileLocation  the file location of the session in the file
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record SessionLocation(
	long sequenceOrder,
	@Nonnull FileLocation fileLocation
) {

	public SessionLocation {
		Assert.notNull(fileLocation, "File location must not be null");
	}

}
