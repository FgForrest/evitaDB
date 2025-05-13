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

package io.evitadb.core.exception;


import io.evitadb.exception.EvitaInternalError;

import java.io.Serial;

/**
 * This exception is throw when method is called on {@link io.evitadb.core.cdc.ChangeCatalogCapturePublisher} which
 * has been already closed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ChangeCatalogCapturePublisherClosedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 4013251726121370946L;

	public ChangeCatalogCapturePublisherClosedException() {
		super("Publisher is already closed, cannot read WAL.");
	}
}
