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

package io.evitadb.externalApi.utils.path;

/**
 * Utilities for dealing with URLs
 *
 * @author Stuart Douglas
 * @author Andre Schaefer
 */
public class URLUtils {
	private static final char PATH_SEPARATOR = '/';

	private URLUtils() {}

	/**
	 * Adds a '/' prefix to the beginning of a path if one isn't present
	 * and removes trailing slashes if any are present.
	 *
	 * @param path the path to normalize
	 * @return a normalized (with respect to slashes) result
	 */
	public static String normalizeSlashes(final String path) {
		// prepare
		final StringBuilder builder = new StringBuilder(path);
		boolean modified = false;

		// remove all trailing '/'s except the first one
		while (builder.length() > 0 && builder.length() != 1 && PATH_SEPARATOR == builder.charAt(builder.length() - 1)) {
			builder.deleteCharAt(builder.length() - 1);
			modified = true;
		}

		// add a slash at the beginning if one isn't present
		if (builder.length() == 0 || PATH_SEPARATOR != builder.charAt(0)) {
			builder.insert(0, PATH_SEPARATOR);
			modified = true;
		}

		// only create string when it was modified
		if (modified) {
			return builder.toString();
		}

		return path;
	}


}
