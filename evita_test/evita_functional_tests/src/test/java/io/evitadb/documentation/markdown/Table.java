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
import net.steppschuh.markdowngenerator.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.steppschuh.markdowngenerator.util.StringUtil.surroundValueWith;

/**
 * Copied last version without apparent bugs from <a href="https://github.com/Steppschuh/Java-Markdown-Generator">GitHub</a>
 * repository. The repository seems abandoned and the fix will never make it to central repository.
 *
 * @author Stephan Schultz
 */
public class Table extends MarkdownElement {

	public static final String SEPARATOR = "|";
	public static final String WHITESPACE = " ";
	public static final String DEFAULT_TRIMMING_INDICATOR = "~";
	public static final int DEFAULT_MINIMUM_COLUMN_WIDTH = 3;

	public static final int ALIGN_CENTER = 1;
	public static final int ALIGN_LEFT = 2;
	public static final int ALIGN_RIGHT = 3;

	private List<TableRow> rows;
	private List<Integer> alignments;
	private boolean firstRowIsHeader;
	private int minimumColumnWidth = DEFAULT_MINIMUM_COLUMN_WIDTH;
	private String trimmingIndicator = DEFAULT_TRIMMING_INDICATOR;

	public static class Builder {

		private Table table;
		private int rowLimit;

		public Builder() {
			this.table = new Table();
		}

		public Table.Builder withRows(List<TableRow> tableRows) {
			this.table.setRows(tableRows);
			return this;
		}

		public Table.Builder addRow(TableRow tableRow) {
			this.table.getRows().add(tableRow);
			return this;
		}

		public Table.Builder addRow(Object... objects) {
			TableRow tableRow = new TableRow(Arrays.asList(objects));
			this.table.getRows().add(tableRow);
			return this;
		}

		public Table.Builder withAlignments(List<Integer> alignments) {
			this.table.setAlignments(alignments);
			return this;
		}

		public Table.Builder withAlignments(Integer... alignments) {
			return withAlignments(Arrays.asList(alignments));
		}

		public Table.Builder withAlignment(int alignment) {
			return withAlignments(Collections.singletonList(alignment));
		}

		public Table.Builder withRowLimit(int rowLimit) {
			this.rowLimit = rowLimit;
			return this;
		}

		public Table.Builder withTrimmingIndicator(String trimmingIndicator) {
			this.table.setTrimmingIndicator(trimmingIndicator);
			return this;
		}

		public Table build() {
			if (this.rowLimit > 0) {
				this.table.trim(this.rowLimit);
			}
			return this.table;
		}

	}

	public Table() {
		this.rows = new ArrayList<>();
		this.alignments = new ArrayList<>();
		this.firstRowIsHeader = true;
	}

	public Table(List<TableRow> rows) {
		this();
		this.rows = rows;
	}

	public Table(List<TableRow> rows, List<Integer> alignments) {
		this(rows);
		this.alignments = alignments;
	}

	@Override
	public String serialize() {
		Map<Integer, Integer> columnWidths = getColumnWidths(this.rows, this.minimumColumnWidth);

		StringBuilder sb = new StringBuilder();

		String headerSeparator = generateHeaderSeparator(columnWidths, this.alignments);
		boolean headerSeperatorAdded = !this.firstRowIsHeader;
		if (!this.firstRowIsHeader) {
			sb.append(headerSeparator).append("\n");
		}

		for (TableRow row : this.rows) {
			for (int columnIndex = 0; columnIndex < columnWidths.size(); columnIndex++) {
				sb.append(SEPARATOR);

				String value = "";
				if (row.getColumns().size() > columnIndex) {
					Object valueObject = row.getColumns().get(columnIndex);
					if (valueObject != null) {
						value = valueObject.toString();
					}
				}

				if (value.equals(this.trimmingIndicator)) {
					value = StringUtil.fillUpLeftAligned(value, this.trimmingIndicator, columnWidths.get(columnIndex));
					value = surroundValueWith(value, WHITESPACE);
				} else {
					int alignment = getAlignment(this.alignments, columnIndex);
					value = surroundValueWith(value, WHITESPACE);
					value = StringUtil.fillUpAligned(value, WHITESPACE, columnWidths.get(columnIndex) + 2, alignment);
				}

				sb.append(value);

				if (columnIndex == row.getColumns().size() - 1) {
					sb.append(SEPARATOR);
				}
			}

			if (this.rows.indexOf(row) < this.rows.size() - 1 || this.rows.size() == 1) {
				sb.append("\n");
			}

			if (!headerSeperatorAdded) {
				sb.append(headerSeparator).append("\n");
				headerSeperatorAdded = true;
			}
		}
		return sb.toString();
	}

	/**
	 * Removes {@link TableRow}s from the center of this table until only the requested amount of
	 * rows is left.
	 *
	 * @param rowsToKeep Amount of {@link TableRow}s that should not be removed
	 * @return the trimmed table
	 */
	public Table trim(int rowsToKeep) {
		this.rows = trim(this, rowsToKeep, this.trimmingIndicator).getRows();
		return this;
	}

	/**
	 * Removes {@link TableRow}s from the center of the specified table until only the requested
	 * amount of rows is left.
	 *
	 * @param table             Table to remove {@link TableRow}s from
	 * @param rowsToKeep        Amount of {@link TableRow}s that should not be removed
	 * @param trimmingIndicator The content that trimmed cells should be filled with
	 * @return The trimmed table
	 */
	public static Table trim(Table table, int rowsToKeep, String trimmingIndicator) {
		if (table.getRows().size() <= rowsToKeep) {
			return table;
		}

		int trimmedEntriesCount = table.getRows().size() - (table.getRows().size() - rowsToKeep);
		int trimmingStartIndex = Math.round(trimmedEntriesCount / 2) + 1;
		int trimmingStopIndex = table.getRows().size() - trimmingStartIndex;

		List<TableRow> trimmedRows = new ArrayList<>();
		for (int i = trimmingStartIndex; i <= trimmingStopIndex; i++) {
			trimmedRows.add(table.getRows().get(i));
		}

		table.getRows().removeAll(trimmedRows);

		TableRow trimmingIndicatorRow = new TableRow();
		for (int columnIndex = 0; columnIndex < table.getRows().get(0).getColumns().size(); columnIndex++) {
			trimmingIndicatorRow.getColumns().add(trimmingIndicator);
		}
		table.getRows().add(trimmingStartIndex, trimmingIndicatorRow);

		return table;
	}

	public static String generateHeaderSeparator(Map<Integer, Integer> columnWidths, List<Integer> alignments) {
		StringBuilder sb = new StringBuilder();
		for (int columnIndex = 0; columnIndex < columnWidths.entrySet().size(); columnIndex++) {
			sb.append(SEPARATOR);

			String value = StringUtil.fillUpLeftAligned("", "-", columnWidths.get(columnIndex));

			int alignment = getAlignment(alignments, columnIndex);
			switch (alignment) {
				case ALIGN_RIGHT: {
					value = WHITESPACE + value + ":";
					break;
				}
				case ALIGN_CENTER: {
					value = ":" + value + ":";
					break;
				}
				default: {
					value = surroundValueWith(value, WHITESPACE);
					break;
				}
			}

			sb.append(value);
			if (columnIndex == columnWidths.entrySet().size() - 1) {
				sb.append(SEPARATOR);
			}
		}
		return sb.toString();
	}

	public static Map<Integer, Integer> getColumnWidths(List<TableRow> rows, int minimumColumnWidth) {
		Map<Integer, Integer> columnWidths = new HashMap<Integer, Integer>();
		if (rows.isEmpty()) {
			return columnWidths;
		}
		for (int columnIndex = 0; columnIndex < rows.get(0).getColumns().size(); columnIndex++) {
			columnWidths.put(columnIndex, getMaximumItemLength(rows, columnIndex, minimumColumnWidth));
		}
		return columnWidths;
	}

	public static int getMaximumItemLength(List<TableRow> rows, int columnIndex, int minimumColumnWidth) {
		int maximum = minimumColumnWidth;
		for (TableRow row : rows) {
			if (row.getColumns().size() < columnIndex + 1) {
				continue;
			}
			Object value = row.getColumns().get(columnIndex);
			if (value == null) {
				continue;
			}
			maximum = Math.max(value.toString().length(), maximum);
		}
		return maximum;
	}

	public static int getAlignment(List<Integer> alignments, int columnIndex) {
		if (alignments.isEmpty()) {
			return ALIGN_LEFT;
		}
		if (columnIndex >= alignments.size()) {
			columnIndex = alignments.size() - 1;
		}
		return alignments.get(columnIndex);
	}

	public List<TableRow> getRows() {
		return this.rows;
	}

	public void setRows(List<TableRow> rows) {
		this.rows = rows;
		invalidateSerialized();
	}

	public List<Integer> getAlignments() {
		return this.alignments;
	}

	public void setAlignments(List<Integer> alignments) {
		this.alignments = alignments;
		invalidateSerialized();
	}

	public boolean isFirstRowHeader() {
		return this.firstRowIsHeader;
	}

	public void useFirstRowAsHeader(boolean firstRowIsHeader) {
		this.firstRowIsHeader = firstRowIsHeader;
		invalidateSerialized();
	}

	public int getMinimumColumnWidth() {
		return this.minimumColumnWidth;
	}

	public void setMinimumColumnWidth(int minimumColumnWidth) {
		this.minimumColumnWidth = minimumColumnWidth;
		invalidateSerialized();
	}

	public String getTrimmingIndicator() {
		return this.trimmingIndicator;
	}

	public void setTrimmingIndicator(String trimmingIndicator) {
		this.trimmingIndicator = trimmingIndicator;
	}
}
