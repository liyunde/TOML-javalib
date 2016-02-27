package com.electronwill.toml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for reading TOML v0.4.0.
 * 
 * @author TheElectronWill
 * 		
 */
public final class TomlReader {
	
	private final String data;
	private int pos = 0;// current position
	
	public TomlReader(String data) {
		this.data = data;
	}
	
	private boolean hasNext() {
		return pos < data.length();
	}
	
	private char next() {
		return data.charAt(pos++);
	}
	
	private char nextUseful(boolean skipComments) {
		char c = ' ';
		while (hasNext() && (c == ' ' || c == '\t' || c == '\r' || c == '\n' || (c == '#' && skipComments))) {
			c = next();
			if (c == '#')
				pos = data.indexOf('\n', pos) + 1;
		}
		return c;
	}
	
	private char nextUsefulOrLinebreak() {
		char c = ' ';
		while (c == ' ' || c == '\t')
			c = next();
		return c;
	}
	
	private Object nextValue(char firstChar) {
		char c2, c3;
		switch (firstChar) {
			case '+':
			case '-':
			case '0':
			case '1':
			case '2':
			case '3':
			case '4':
			case '5':
			case '6':
			case '7':
			case '8':
			case '9':
				return nextNumberOrDate(firstChar);
			case '"':
				if (pos + 2 < data.length()) {
					c2 = data.charAt(pos + 1);
					c3 = data.charAt(pos + 2);
					if (c2 == '"' && c3 == '"') {
						pos += 2;
						return nextBasicMultilineString();
					}
				}
				return nextBasicString();
			case '\'':
				if (pos + 2 < data.length()) {
					c2 = data.charAt(pos + 1);
					c3 = data.charAt(pos + 2);
					if (c2 == '"' && c3 == '"') {
						pos += 2;
						return nextLiteralMultilineString();
					}
				}
				return nextLiteralString();
			case '[':
				return nextArray();
			case '{':
				return nextInlineTable();
			case 't':// Must be "true"
				if (pos + 3 >= data.length() || next() != 'r' || next() != 'u' || next() != 'e') {
					throw new TOMLException("Invalid value at pos " + pos);
				}
				return true;
			case 'f':// Must be "false"
				if (pos + 4 >= data.length() || next() != 'a' || next() != 'l' || next() != 's' || next() != 'e') {
					throw new TOMLException("Invalid value at pos " + pos);
				}
				return false;
			default:
				throw new TOMLException("Invalid character at pos " + pos);
		}
	}
	
	public Map<String, Object> read() {
		Map<String, Object> map = nextTableContent();
		
		while (hasNext()) {
			char c = nextUseful(true);
			boolean twoBrackets = (c == '[');
			
			// --- Reads the key --
			List<String> keyParts = new ArrayList<>(4);
			StringBuilder keyBuilder = new StringBuilder();
			while (true) {
				if (!hasNext())
					throw new TOMLException("Invalid table declaration at pos " + pos + ": not enough data");
				char next = nextUsefulOrLinebreak();
				if (next == '"') {
					keyParts.add(keyBuilder.toString().trim());
					keyBuilder.setLength(0);
					keyParts.add(nextBasicString());
				} else if (next == '\'') {
					keyParts.add(keyBuilder.toString().trim());
					keyBuilder.setLength(0);
					keyParts.add(nextLiteralString());
				} else if (next == ']') {
					keyParts.add(keyBuilder.toString().trim());
					break;
				} else if (next == '.') {
					keyParts.add(keyBuilder.toString().trim());
					keyBuilder.setLength(0);
				} else if (next == '#') {
					throw new TOMLException("Invalid table name at pos " + pos + ": comments are not allowed here");
				} else if (next == '\n' || next == '\r') {
					throw new TOMLException("Invalid table name at pos " + pos + ": line breaks are not allowed here");
				} else {
					keyBuilder.append(next);
				}
			}
			
			// -- Check --
			if (twoBrackets) {
				if (next() != ']')// there are only one ] that ends the declaration -> error
					throw new TOMLException("Missing character ] at pos " + pos);
			}
			
			// -- Reads the value (table content) --
			Map<String, Object> value = nextTableContent();
			
			// -- Saves the value --
			Map<String, Object> valueMap = map;// the map that contains the value
			for (int i = 0; i < keyParts.size() - 1; i++) {
				String part = keyParts.get(i);
				valueMap = (Map) map.get(part);
			}
			if (twoBrackets) {// element of a table array
				String name = keyParts.get(keyParts.size() - 1);
				Collection<Map> tableArray = (Collection) valueMap.get(name);
				if (tableArray == null) {
					tableArray = new ArrayList<>();
					valueMap.put(name, tableArray);
				}
				tableArray.add(value);
			} else {// just a table
				valueMap.put(keyParts.get(keyParts.size() - 1), value);
			}
			
		}
		return map;
	}
	
	private List nextArray() {
		List<Object> list = new ArrayList<>();
		while (true) {
			char c = nextUseful(true);
			if (c == ']') {
				pos++;
				return list;
			}
			Object value = nextValue(c);
			if (!list.isEmpty() && !(list.get(0).getClass().isAssignableFrom(value.getClass())))
				throw new TOMLException("Invalid array at pos " + pos + ": all the values must have the same type");
			list.add(value);
			
			pos--;
			char afterEntry = nextUseful(true);
			if (afterEntry == ']') {
				pos++;
				return list;
			}
			if (afterEntry != ',') {
				throw new TOMLException("Invalid array at pos " + pos + ": expected a comma after each value");
			}
		}
	}
	
	private Map<String, Object> nextInlineTable() {
		Map<String, Object> map = new HashMap<>();
		while (true) {
			char nameFirstChar = nextUsefulOrLinebreak();
			if (nameFirstChar == '}') {
				return map;
			}
			String name;
			if (nameFirstChar == '"') {
				char c2 = next(), c3 = next();
				name = (c2 == '"' && c3 == '"') ? nextBasicMultilineString() : nextBasicString();
			} else if (nameFirstChar == '\'') {
				char c2 = next(), c3 = next();
				name = (c2 == '\'' && c3 == '\'') ? nextLiteralMultilineString() : nextLiteralString();
			} else {
				name = nextBareKey();
				if (data.charAt(pos - 1) == '=')
					pos--;
			}
			char separator = nextUsefulOrLinebreak();
			if (separator != '=') {
				throw new TOMLException("Invalid key at pos " + pos);
			}
			
			char valueFirstChar = nextUsefulOrLinebreak();
			Object value = nextValue(valueFirstChar);
			map.put(name, value);
			
			char after = nextUsefulOrLinebreak();
			if (after == '}' || !hasNext()) {
				return map;
			} else if (after != ',') {
				throw new TOMLException("Invalid inline table at pos " + pos + ": missing comma");
			}
		}
	}
	
	private Map<String, Object> nextTableContent() {
		Map<String, Object> map = new HashMap<>();
		while (true) {
			char nameFirstChar = nextUseful(true);
			if (!hasNext() || nameFirstChar == '[') {
				return map;
			}
			System.out.println("nameFirstChar: " + nameFirstChar);
			String name = null;
			if (nameFirstChar == '"') {
				if (pos + 2 < data.length()) {
					char c2 = data.charAt(pos + 1);
					char c3 = data.charAt(pos + 2);
					if (c2 == '"' && c3 == '"') {
						pos += 2;
						name = nextBasicMultilineString();
					}
				}
				if (name == null) {
					pos--;
					name = nextBasicString();
				}
			} else if (nameFirstChar == '\'') {
				if (pos + 2 < data.length()) {
					char c2 = data.charAt(pos + 1);
					char c3 = data.charAt(pos + 2);
					if (c2 == '"' && c3 == '"') {
						pos += 2;
						name = nextLiteralMultilineString();
					}
				}
				if (name == null) {
					pos--;
					name = nextLiteralString();
				}
			} else {
				pos--;
				name = nextBareKey();
				if (data.charAt(pos - 1) == '=')
					pos--;
			}
			System.out.println("key name: " + name);
			char separator = nextUseful(true);
			System.out.println("separator: " + separator);
			if (separator != '=') {
				throw new TOMLException("Invalid key at pos " + pos);
			}
			
			char valueFirstChar = nextUseful(true);
			Object value = nextValue(valueFirstChar);
			System.out.println("value: " + value);
			map.put(name, value);
			
			char after = nextUseful(true);
			if (after == '[' || !hasNext()) {
				return map;
			} else {
				pos--;
			}
		}
	}
	
	private Object nextNumberOrDate(char first) {
		boolean maybeDouble = true, maybeInteger = true, maybeDate = true;
		StringBuilder sb = new StringBuilder();
		sb.append(first);
		char c;
		while (hasNext()) {
			c = next();
			if (c == 'Z' || c == 'T' || c == ':')
				maybeInteger = maybeDouble = false;
			else if (c == 'e' || c == 'E')
				maybeInteger = maybeDate = false;
			else if (c == '.')
				maybeInteger = false;
			else if (c == '-' && pos != 0 && data.charAt(pos - 1) != 'e' && data.charAt(pos - 1) != 'E')
				maybeInteger = maybeDouble = false;
			else if (c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ']' || c == '}')
				break;
				
			if (c == '_')
				maybeDate = false;
			else
				sb.append(c);
		}
		String valueStr = sb.toString();
		if (maybeInteger) {
			return (valueStr.length() < 10) ? Integer.parseInt(valueStr) : Long.parseLong(valueStr);
		} else if (maybeDouble) {
			return Double.parseDouble(valueStr);
		} else if (maybeDate) {
			return Toml.DATE_FORMATTER.parseBest(valueStr, ZonedDateTime::from, LocalDateTime::from, LocalDate::from);
		} else {
			throw new TOMLException("Invalid value: " + valueStr + " at pos " + pos);
		}
	}
	
	private String nextBareKey() {
		String keyName;
		for (int i = pos; i < data.length(); i++) {
			char c = data.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
				keyName = data.substring(pos, i);
				pos = i;
				return keyName;
			}
		}
		throw new TOMLException(
				"Invalid key/value pair at pos " + pos + " end of data reached before the value attached to the key was found");
	}
	
	private String nextLiteralString() {
		int index = data.indexOf('\'');
		if (index == -1)
			throw new TOMLException("Invalid literal String at pos " + pos + ": it never ends");
		String str = data.substring(pos, index);
		pos = index + 1;
		return str;
	}
	
	private String nextLiteralMultilineString() {
		int index = data.indexOf("'''");
		if (index == -1)
			throw new TOMLException("Invalid literal String (multiline) at pos " + pos + ": it never ends");
		String str;
		if (data.charAt(pos) == '\r' && data.charAt(pos) == '\n') {
			str = data.substring(pos + 2, index);
		} else if (data.charAt(pos) == '\n' || data.charAt(pos) == '\r') {
			str = data.substring(pos + 1, index);
		} else {
			str = data.substring(pos, index);
		}
		pos = index + 1;
		return str;
	}
	
	private String nextBasicString() {
		StringBuilder sb = new StringBuilder();
		boolean escape = false;
		while (hasNext()) {
			char ch = next();
			if (ch == '\n' || ch == '\r')
				throw new TOMLException("Invalid basic String at pos " + pos + ": newlines not allowed");
			if (escape) {
				sb.append(unescape(ch));
				escape = false;
			} else if (ch == '\\') {
				escape = true;
			} else if (ch == '\"') {
				pos++;
				return sb.toString();
			} else {
				sb.append(ch);
			}
		}
		throw new TOMLException("Invalid basic String at pos " + pos + ": it nerver ends");
	}
	
	private String nextBasicMultilineString() {
		return "TODO";// TODO
	}
	
	private char unescape(char c) throws TOMLException {
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
					throw new TOMLException("Invalid unicode point: not enough data");
				String unicode = data.substring(pos, pos + 4);
				int hexVal = Integer.parseInt(unicode, 16);
				return (char) hexVal;
			}
			case 'U': {// unicode U+XXXXXXXX
				if (data.length() - pos < 8)
					throw new TOMLException("Invalid end of data");
				String unicode = data.substring(pos, pos + 8);
				int hexVal = Integer.parseInt(unicode, 16);
				return (char) hexVal;
			}
			default:
				throw new TOMLException("Invalid escape sequence: \\" + c);
		}
	}
	
}
