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

package io.evitadb.externalApi.utils;

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.ArrayUtils;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents URI path. It provides convenient builder and concat functionality (similarly to {@link java.nio.file.Path} but for web URLs).
 * It also supports path parameters (e.g. /users/{userId}/orders/{orderId}). Path parameters are represented by curly braces.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
public class UriPath implements Comparable<UriPath> {

	@Nonnull private final String path;

	public static UriPath of(@Nonnull Object... path) {
		return new Builder(Arrays.stream(path).map(Object::toString).toArray(String[]::new)).build();
	}

	public static UriPath of(@Nonnull String... path) {
		return new Builder(path).build();
	}

	public static UriPath of(@Nonnull UriPath basePath, @Nonnull String... path) {
		return new Builder(ArrayUtils.mergeArrays(new String[] {basePath.getPath()}, path)).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static Builder builder(@Nonnull UriPath basePath) {
		return new Builder(basePath.getPath());
	}

	@Override
	public String toString() {
		return this.path;
	}

	@Override
	public int compareTo(@Nonnull UriPath o) {
		return this.path.compareTo(o.path);
	}

	public static class Builder {
		private static final String PATH_SEPARATOR = "/";
		private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*)}");
		private static final Pattern WILDCARD_PATTERN = Pattern.compile("\\*");
		// this is potential ReDOS, but it used only for URL paths that are not user-controlled, so it's safe
		private static final Pattern SUB_PATH_PATTERN = Pattern.compile("^(/)?(.+(?:/.+)+)$");

		@Nonnull private final List<String> parts;

		private Builder(@Nonnull String... path) {
			this.parts = new LinkedList<>();
			Arrays.stream(path).forEach(this::part);
		}

		@Nonnull
		public Builder part(@Nonnull String part) {
			if (part.isEmpty()) {
				return this;
			}

			if (part.equals(PATH_SEPARATOR)) {
				this.parts.add(part);
				return this;
			}

			final Matcher subPathMatcher = SUB_PATH_PATTERN.matcher(part);
			if (subPathMatcher.matches()) {
				if (subPathMatcher.group(1) != null) {
					part(subPathMatcher.group(1));
				}
				Arrays.stream(subPathMatcher.group(2).split(PATH_SEPARATOR)).forEach(this::part);
				return this;
			}

			final Matcher wildcardMatcher = WILDCARD_PATTERN.matcher(part);
			if (wildcardMatcher.matches()) {
				this.parts.add("*");
				return this;
			}

			final Matcher pathParamMatcher = PATH_PARAM_PATTERN.matcher(part);
			if (pathParamMatcher.matches()) {
				validatePart(pathParamMatcher.group(1));
			} else {
				validatePart(part);
			}
			this.parts.add(part);

			return this;
		}

		@Nonnull Builder part(@Nonnull UriPath part) {
			return part(part.getPath());
		}

		@Nonnull
		public UriPath build() {
			final String builtPath = String.join("/", this.parts).replaceAll("/+", PATH_SEPARATOR);
			return new UriPath(builtPath);
		}

		private void validatePart(@Nonnull String part) {
			try {
				new URI(part);
			} catch (URISyntaxException e) {
				throw new ExternalApiInternalError("Invalid URI part `" + part + "`.", e);
			}
		}
	}
}
