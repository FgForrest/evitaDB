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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;

/**
 * Adapter for messages used in the PathMatcher and other classes copied from Undertow.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class UndertowMessages {

	public static final MessageAdapter MESSAGES = new MessageAdapter();

	public static class MessageAdapter {

		public EvitaInternalError pathMustBeSpecified() {
			return new GenericEvitaInternalError("Path must be specified");
		}

		public EvitaInternalError couldNotParseUriTemplate(String path, int i) {
			return new GenericEvitaInternalError("Could not parse URI template: " + path + " at position " + i);
		}

		public EvitaInternalError matcherAlreadyContainsTemplate(String templateString, String templateString1) {
			return new GenericEvitaInternalError("Matcher already contains template: " + templateString + " " + templateString1);
		}
	}
}
