/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.log;

import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Logs access log messages to Slf4J logger marked with `ACCESS_LOG` and `UNDERTOW_ACCESS_LOG`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class Slf4JAccessLogReceiver implements AccessLogReceiver {

	/**
	 * Marks Undertow's access log messages to differentiate them from access log messages from other web servers.
	 */
	private static final Marker UNDERTOW_ACCESS_LOG_MARKER = MarkerFactory.getMarker("UNDERTOW_ACCESS_LOG");

	@Override
	public void logMessage(String message) {
		log.atInfo()
			.addMarker(AccessLogMarker.getInstance())
			.addMarker(UNDERTOW_ACCESS_LOG_MARKER)
			.log(message);
	}
}
