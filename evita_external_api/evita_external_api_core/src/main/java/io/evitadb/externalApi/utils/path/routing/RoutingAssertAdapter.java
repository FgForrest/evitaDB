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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adapters for classes taken from Undertow server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class RoutingAssertAdapter {

	/**
	 * Internal implementation to avoid entire library dependency.
	 * @param argumentName name of the argument
	 * @param argument argument to check
	 */
	@Nonnull
	public static <T> T checkNotNullParamWithNullPointerException(@Nonnull String argumentName, @Nullable T argument) {
		Assert.notNull(argument, "Parameter `" + argumentName + "` may not be null");
		return argument;
	}

}
