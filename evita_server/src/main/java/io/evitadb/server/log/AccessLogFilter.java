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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.server.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.AbstractMatcherFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.evitadb.externalApi.log.AccessLogMarker;
import org.slf4j.Marker;

import java.util.List;

/**
 * Filters out log messages that aren't access log messages.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class AccessLogFilter extends AbstractMatcherFilter<ILoggingEvent> {

	@Override
	public FilterReply decide(ILoggingEvent event) {
		final List<Marker> markerListInEvent = event.getMarkerList();
		if (markerListInEvent == null || markerListInEvent.isEmpty()) {
			// no marker in event, not access log message
			return FilterReply.DENY;
		}

		for (Marker markerInEvent : markerListInEvent) {
			if (markerInEvent.contains(AccessLogMarker.getInstance().getName())) {
				return FilterReply.NEUTRAL;
			}
		}
		// no access log marker in event, not access log message
		return FilterReply.DENY;
	}
}
