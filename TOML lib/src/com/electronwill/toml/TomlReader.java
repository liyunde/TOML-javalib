package com.electronwill.toml;

import java.io.EOFException;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TomlReader {
	
	private static final DateTimeFormatter TOML_DATE_FORMATTER = new DateTimeFormatterBuilder()
			.append(DateTimeFormatter.ISO_LOCAL_DATE)
			.optionalStart()
			.appendLiteral('T')
			.append(DateTimeFormatter.ISO_LOCAL_TIME)
			.optionalStart()
			.appendOffsetId()
			.optionalEnd()
			.optionalEnd()
			.toFormatter();
			
	private final String data;
	private final List<Integer> newlines;
	private int pos = 0;
	private Map<String, Object> map;
	
	/**
	 * Creates a new TomlReader.
	 * 
	 * @param data the data to read
	 * @param newlines a list containing the position of every newlines (\n characters).
	 */
	public TomlReader(String data, List<Integer> newlines) {
		this.data = data;
		this.newlines = newlines;
	}
	
	/**
	 * Parses TOML data.
	 */
	public Map<String, Object> read() throws IOException {
		map = new HashMap<>();
		map = readTableContent();// reads everything until the first table (remember that non-inline tables are declared
									// at the end of the file, because a table ends with another table, or with the end
									// of the file)
		while (pos < data.length()) {
			int ch = data.charAt(pos++);// The [ character has already been read by #readTableContent()
			String key;
			if (ch == '[') {// there are two [
				pos++;
			}
			key = until(']');
			if (ch == '[') {// there were two [
				if (data.charAt(pos++) != ']')// [[name] is invalid
					throw new IOException("Missing character ] at " + getCurrentPosition());
			}
			Map<String, Object> value = readTableContent();
			if (ch == '[') {// element of a table array
				// TODO supporter a.b.c
				// TODO à faire
			} else {// just a table
				map.put(key, value);
				// TODO supporter a.b.c
			}
		}
		return map;
	}
	
	// TODO Supporter les commentaires: commencent par # et vont jusqu'à la fin de la ligne, cad jusqu'à '\n'.
	
	// === Methods for reading data structures ===
	
	/**
	 * Reads the content of a table. Stops at the next table (reads the [ character), or at the end of the file.
	 */
	Map<String, Object> readTableContent() throws IOException {
		HashMap<String, Object> table = new HashMap<>();
		while (true) {
			final int ch = nextChar();
			String key = null;
			Object value = null;
			
			if (ch == '[' || ch == -1) {
				return table;
			} else if (ch == '\"') {
				key = readBasicString();
				goAfter('=', false);
			} else if (ch == '\'') {
				key = readLiteralString();
				goAfter('=', false);
			} else if (ch == '\n' || ch == '\t' || ch == ' ') {
				continue;// ignores
			} else if (ch == '#') {
				pos = data.indexOf('\n', pos) + 1;// goes after the \n character
			} else {
				pos--;// unreads ch
				key = until(' ', '=');
				if (data.charAt(pos) != '=')// if key reading didn't stop at =
					pos = data.indexOf('=', pos) + 1;// goes after the = character
			}
			
			value = readValue(true, '[');
			table.put(key, value);
		}
	}
	
	/**
	 * Reads an inline table.
	 */
	Map<String, Object> readInlineTable() throws IOException {
		Map<String, Object> table = new HashMap<>();
		while (true) {
			final int ch = nextChar();
			final String key;
			if (ch == '}') {// end of table
				return table;
			} else if (ch == '\"') {
				key = readBasicString();
				pos = data.indexOf('=', pos) + 1;// goes after the '=' character
			} else if (ch == '\'') {
				key = readLiteralString();
				pos = data.indexOf('=', pos) + 1;// goes after the '=' character
			} else if (ch == '\t' || ch == ' ') {
				continue;// ignores
			} else if (ch == '\n') {
				throw new IOException("Invalid line break in a inline table, at the end of line " + (getCurrentLine() - 1));
			} else {
				pos--;// unreads ch
				key = until(' ', '=', '\t');
				if (data.charAt(pos) != '=')// if key reading didn't stop at =
					pos = data.indexOf('=', pos) + 1;// goes after the = character
			}
			
			final Object value = readValue(false, ',', '}');
			table.put(key, value);
			
			if (data.charAt(pos) == '}')// end of table
				return table;
		}
	}
	
	/**
	 * Reads an array of values.
	 */
	List<Object> readArray() throws IOException {
		List<Object> list = new ArrayList();
		while (true) {
			int ch = nextChar();
			if (ch == ']')// end of array
				return list;
			else if (ch == '\n' || ch == '\t' || ch == ' ')
				continue;// ignores
			else
				pos--;// unreads ch
				
			Object v = readValue(false, ',', ']');
			list.add(v);
			
			if (data.charAt(pos) == ']')// end of array
				return list;
		}
	}
	
	// === Methods for reading some values ===
	
	/**
	 * Reads the next value.
	 * 
	 * @param acceptEOF true if EOF is normal, false if it should throw an exception
	 * @param end the characters that marks the end of the value
	 */
	Object readValue(boolean acceptEOF, char... end) throws IOException {
		int ch = nextChar();
		switch (ch) {
			case '[':
				return readArray();
			case '{':
				return readInlineTable();
			case '\"': {
				if (seekNext(1) == '\"' && seekNext(2) == '\"') {
					return readBasicMultiString();
				}
				return readBasicString();
			}
			case '\'': {
				if (seekNext(1) == '\'' && seekNext(2) == '\'') {
					return readLiteralMultiString();
				}
				return readLiteralString();
			}
			case 'f':
				return readFalseBoolean();
			case 't':
				return readTrueBoolean();
			default:
				return readDateOrNumber(true, acceptEOF, end);
			case -1:
				return null;
		}
	}
	
	/**
	 * Reads a date or a number.
	 * 
	 * @param firstCharWasRead true if the first char of the value has been read
	 * @param acceptEOF true if EOF is normal, false if it should throw an exception
	 * @param end the characters that marks the end of the value
	 */
	Object readDateOrNumber(boolean firstCharWasRead, boolean acceptEOF, char... end) throws IOException {
		if (firstCharWasRead)
			pos--;
		String str = until(acceptEOF, end);
		try {
			return Long.parseLong(str);
		} catch (NumberFormatException ex) {
			try {
				return Double.parseDouble(str);
			} catch (NumberFormatException ex2) {
				try {
					return TOML_DATE_FORMATTER.parse(str);
				} catch (Exception ex3) {
					throw new IOException("Invalid value at " + getCurrentPosition() + ": " + str);
				}
			}
		}
	}
	
	// === Methods for reading booleans ===
	
	boolean readFalseBoolean() throws IOException {
		String boolEnd;
		try {
			boolEnd = data.substring(pos, pos + 4);
		} catch (IndexOutOfBoundsException ex) {
			throw new EOFException("Invalid end of data");
		}
		
		if (!boolEnd.equals("alse"))
			throw new IOException("Invalid boolean value at " + getCurrentPosition());
			
		pos += 4;
		return false;
	}
	
	boolean readTrueBoolean() throws IOException {
		String boolEnd;
		try {
			boolEnd = data.substring(pos, pos + 3);
		} catch (IndexOutOfBoundsException ex) {
			throw new EOFException("Invalid end of data");
		}
		
		if (!boolEnd.equals("rue"))
			throw new IOException("Invalid boolean value at " + getCurrentPosition());
			
		pos += 3;
		return true;
	}
	
	// === Methods for reading Strings ===
	
	/**
	 * Reads a literal String which doesn't support escaping.
	 * 
	 * @throws EOFException
	 */
	String readLiteralString() throws EOFException {
		return until('\'');
	}
	
	/**
	 * Reads a literal multi-line String, which doesn't support escaping.
	 */
	String readLiteralMultiString() throws IOException {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		while (true) {
			int ch = nextChar();
			if (first) {
				first = false;
				if (ch == '\n') {// skips the first newline
					continue;
				}
			}
			if (ch == -1)
				throw new EOFException("Invalid end of data");
			if (ch == '\'') {
				if (seekNext(1) != '\'' && seekNext(2) != '\'')
					throw new IOException("Invalid end of multi-line string at " + getCurrentPosition());
				return sb.toString();
			} else {
				sb.append(ch);
			}
		}
	}
	
	/**
	 * Reads a basic String which supports escaping.
	 */
	String readBasicString() throws IOException {
		StringBuilder sb = new StringBuilder();
		boolean escape = false;
		while (true) {
			int ch = nextChar();
			if (ch == -1)
				throw new EOFException("Invalid end of data");
			if (ch == '\n')
				throw new IOException("Invalid line break after line " + getPreviousLine());
			if (escape) {
				sb.append(unescape(ch));
				escape = false;
			} else if (ch == '\\') {
				escape = true;
			} else if (ch == '\"') {
				return sb.toString();
			} else {
				sb.append(ch);
			}
		}
	}
	
	/**
	 * Reads a literal multi-line String, which supports escaping.
	 */
	String readBasicMultiString() throws IOException {
		StringBuilder sb = new StringBuilder();
		boolean escape = false, first = true;
		while (true) {
			int ch = nextChar();
			if (first) {
				first = false;
				if (ch == '\n') {// skips the first newline
					continue;
				}
			}
			if (ch == -1)
				throw new EOFException("Invalid end of data");
			if (escape) {
				sb.append(unescape(ch));
				escape = false;
			} else if (ch == '\\') {
				escape = true;
			} else if (ch == '\"') {
				if (seekNext(1) != '\"' && seekNext(2) != '\"')
					throw new IOException("Invalid end of multi-line string at " + getCurrentPosition());
				return sb.toString();
			} else {
				sb.append(ch);
			}
		}
	}
	
	// === Utilities for manipulating characters and Strings ===
	
	char unescape(int c) throws IOException {
		if (c == -1)
			throw new EOFException("Invalid end of data at " + getCurrentPosition());
		switch (c) {
			case 'b':
				return '\b';
			case 't':
				return '\t';
			case 'n':
				return '\n';
			case 'f':
				return '\f';
			case 'r':
				return '\r';
			case '\"':
				return '\"';
			case '\\':
				return '\\';
			case 'u': {// unicode U+XXXX
				if (data.length() - pos < 4)
					throw new EOFException("Invalid end of data");
				String unicode = data.substring(pos, pos + 4);
				int hexVal = Integer.parseInt(unicode, 16);
				return (char) hexVal;
			}
			case 'U': {// unicode U+XXXXXXXX
				if (data.length() - pos < 8)
					throw new EOFException("Invalid end of data");
				String unicode = data.substring(pos, pos + 8);
				int hexVal = Integer.parseInt(unicode, 16);
				return (char) hexVal;
			}
			case -1:
				throw new EOFException("Invalid end of data");
			default:
				throw new IOException("Invalid escape sequence at " + getCurrentPosition() + ": \\" + c);
		}
	}
	
	/**
	 * Exactly the same as {@code until(false, c)}.
	 */
	String until(char c) throws EOFException {
		return until(false, c);
	}
	
	String until(boolean acceptEOF, char c) throws EOFException {
		try {
			int indexOfC = data.indexOf(c, pos);
			if (indexOfC == -1)// TODO nécessaire ??
				return data.substring(pos);// TODO utile ??
			return data.substring(pos, data.indexOf(c, pos));
		} catch (IndexOutOfBoundsException ex) {
			throw new EOFException("Invalid end of data");
		}
	}
	
	/**
	 * Exactly the same as {@code until(false, chars)}.
	 */
	String until(char... chars) throws EOFException {
		return until(false, chars);
	}
	
	/**
	 * Returns the string containg every character from the current position to the position of one of the given
	 * character (stops at the first one). The last character is read but the position is NOT incremented after.
	 * Therefore, after this method is called, the current position is the position of the last character read by this
	 * method.
	 */
	String until(boolean acceptEOF, char... chars) throws EOFException {
		final int initialPos = pos;
		for (; pos < data.length(); pos++) {
			char ch = data.charAt(pos);
			
			for (int i = 0; i < chars.length; i++) {
				if (ch == chars[i]) {
					return data.substring(initialPos, pos);
				}
			}
		}
		if (!acceptEOF)
			throw new EOFException("Invalid end of data");
			
		return data.substring(initialPos);
	}
	
	/**
	 * Gets the next char, or -1 if EOF is reached, and increments the position.
	 */
	int nextChar() {
		if (pos >= data.length())
			return -1;
		return data.charAt(pos++);
	}
	
	/**
	 * Gets the next char, or -1 if EOF is reached, but does not increment the position.
	 */
	int seekNext(int n) {
		if (pos >= data.length())
			return -1;
		return data.charAt(pos + (n - 1));
	}
	
	/**
	 * Goes at the position of the next character that equals to c.
	 */
	void goAt(char c, boolean acceptNewlines) throws IOException {
		for (; pos < data.length(); pos++) {
			char ch = data.charAt(pos);
			if (ch == '\n' && !acceptNewlines) {
				throw new IOException("Invalid line break after line " + getPreviousLine());
			}
			if (ch == c)
				return;
		}
	}
	
	/**
	 * Goes after the position of the next character that equals to c.
	 */
	void goAfter(char c, boolean acceptNewlines) throws IOException {
		goAt(c, acceptNewlines);
		pos++;
	}
	
	// === Methods for getting the current position in text ===
	
	/**
	 * Constructs a string indicating the current position in the data. The format is:
	 * {@code line <line number> position <position on line>}. For example "line 10 position 25".
	 */
	String getCurrentPosition() {
		int previousLineStart = 0;
		for (int i = 0; i < newlines.size(); i++) {
			int newLinePosition = newlines.get(i);
			if (pos < newLinePosition) {
				return "line " + (i + 1) + " position " + (pos - previousLineStart);
			}
			previousLineStart = newLinePosition;
		}
		return "line 1 position " + pos;
	}
	
	/**
	 * Gets the number of the previous line.
	 */
	int getPreviousLine() {
		return getCurrentLine() - 1;
	}
	
	/**
	 * Gets the number of the current line.
	 */
	int getCurrentLine() {
		for (int i = 0; i < newlines.size(); i++) {
			int newLinePosition = newlines.get(i);
			if (pos < newLinePosition)
				return i;
		}
		return newlines.size();
	}
	
}
