/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.openApi;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Constants used internally by {@link io.swagger.v3.oas.models.OpenAPI} schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OpenApiConstants {

	public static final String TYPE_ARRAY = "array";
	public static final String TYPE_OBJECT = "object";
	public static final String TYPE_STRING = "string";
	public static final String TYPE_INTEGER = "integer";
	public static final String TYPE_BOOLEAN = "boolean";

	public static final String FORMAT_DATE_TIME = "date-time";
	public static final String FORMAT_LOCAL_DATE_TIME = "local-date-time";
	public static final String FORMAT_DATE = "date";
	public static final String FORMAT_LOCAL_TIME = "local-time";
	public static final String FORMAT_INT_16 = "int16";
	public static final String FORMAT_INT_32 = "int32";
	public static final String FORMAT_INT_64 = "int64";
	public static final String FORMAT_CURRENCY = "iso-4217";
	public static final String FORMAT_UUID = "uuid";
	public static final String FORMAT_BYTE = "int8";
	public static final String FORMAT_CHAR = "char";
	public static final String FORMAT_DECIMAL = "decimal";
	public static final String FORMAT_LOCALE = "locale";
	public static final String FORMAT_RANGE = "range";
	public static final String FORMAT_EXPRESSION = "string";

}
