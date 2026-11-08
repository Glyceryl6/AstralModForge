package dev.astral.modforge.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal strict JSON object-of-strings reader/writer; no external library dependency. */
public class JsonFlat {
    public static Map<String, String> parse(String raw) throws IOException {
        Scanner scanner = new Scanner(raw);
        Map<String, String> result = new LinkedHashMap<>();
        scanner.ws();
        scanner.expect('{');
        scanner.ws();
        if (scanner.accept('}')) {
            scanner.end();
            return result;
        }

        while (true) {
            String key = scanner.str();
            scanner.ws();
            scanner.expect(':');
            String value = scanner.str();
            if (result.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate JSON key " + key);
            }
            scanner.ws();
            if (scanner.accept('}')) {
                break;
            }
            scanner.expect(',');
        }
        scanner.end();
        return result;
    }

    public static String write(Map<String, String> entries) {
        StringBuilder output = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (count++ > 0) {
                output.append(",\n");
            }
            output.append("  ").append(quote(entry.getKey()))
                    .append(": ").append(quote(entry.getValue()));
        }
        return output.append("\n}\n").toString();
    }

    public static String quote(String value) {
        StringBuilder output = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 32) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    private static class Scanner {
        private final String source;
        private int position;

        Scanner(String source) {
            this.source = source;
        }

        void ws() {
            while (this.position < this.source.length()
                    && Character.isWhitespace(this.source.charAt(this.position))) {
                this.position++;
            }
        }

        boolean accept(char character) {
            ws();
            if (this.position < this.source.length()
                    && this.source.charAt(this.position) == character) {
                this.position++;
                return true;
            }
            return false;
        }

        void expect(char character) throws IOException {
            if (!accept(character)) {
                throw new IOException("JSON expected '" + character + "' at " + this.position);
            }
        }

        void end() throws IOException {
            ws();
            if (this.position != this.source.length()) {
                throw new IOException("Trailing JSON data at " + this.position);
            }
        }

        String str() throws IOException {
            ws();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (this.position < this.source.length()) {
                char character = this.source.charAt(this.position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    if (character < 32) {
                        throw new IOException("Control character in JSON");
                    }
                    value.append(character);
                    continue;
                }
                if (this.position >= this.source.length()) {
                    break;
                }

                char escaped = this.source.charAt(this.position++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (this.position + 4 > this.source.length()) {
                            throw new IOException("Bad unicode escape");
                        }
                        try {
                            value.append((char) Integer.parseInt(
                                    this.source.substring(this.position, this.position + 4), 16));
                        } catch (NumberFormatException exception) {
                            throw new IOException("Bad unicode escape", exception);
                        }
                        this.position += 4;
                    }
                    default -> throw new IOException("Invalid JSON escape: " + escaped);
                }
            }
            throw new IOException("Unterminated JSON string");
        }
    }
}
