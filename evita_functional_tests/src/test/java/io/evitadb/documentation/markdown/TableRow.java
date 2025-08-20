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

package io.evitadb.documentation.markdown;

import net.steppschuh.markdowngenerator.MarkdownElement;
import net.steppschuh.markdowngenerator.MarkdownSerializationException;
import net.steppschuh.markdowngenerator.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Copied last version without apparent bugs from <a href="https://github.com/Steppschuh/Java-Markdown-Generator">GitHub</a>
 * repository. The repository seems abandoned and the fix will never make it to central repository.
 *
 * @author Stephan Schultz
 */
public class TableRow<T extends Object> extends MarkdownElement {

    private List<T> columns;

    public TableRow() {
        this.columns = new ArrayList<>();
    }

    public TableRow(List<T> columns) {
        this.columns = columns;
    }

    @Override
    public String serialize() throws MarkdownSerializationException {
        StringBuilder sb = new StringBuilder();
        for (Object item : this.columns) {
            if (item == null) {
                throw new MarkdownSerializationException("Column is null");
            }
            if (item.toString().contains(Table.SEPARATOR)) {
                throw new MarkdownSerializationException("Column contains seperator char \"" + Table.SEPARATOR + "\"");
            }
            sb.append(Table.SEPARATOR);
            sb.append(StringUtil.surroundValueWith(item.toString(), " "));
            if (this.columns.indexOf(item) == this.columns.size() - 1) {
                sb.append(Table.SEPARATOR);
            }
        }
        return sb.toString();
    }

    public List<T> getColumns() {
        return this.columns;
    }

    public void setColumns(List<T> columns) {
        this.columns = columns;
        invalidateSerialized();
    }

}
