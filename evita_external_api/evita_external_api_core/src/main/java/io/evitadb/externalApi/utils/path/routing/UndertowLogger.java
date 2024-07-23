/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.evitadb.externalApi.utils.path.routing;

import lombok.extern.slf4j.Slf4j;

/**
 * Adapter for logger used in classes copied from Undertow.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class UndertowLogger {
	public static final LoggerAdapter REQUEST_LOGGER = new LoggerAdapter();

	@Slf4j
	public static class LoggerAdapter {

		public void debugf(String message, Object... arguments) {
			log.debug(String.format(message, arguments));
		}
	}
}
