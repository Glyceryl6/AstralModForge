package dev.astral.modforge.core;

import java.io.IOException;
import java.util.*;

/** Minimal strict JSON object-of-strings reader/writer; no external library dependency. */
public final class JsonFlat {
    private JsonFlat() {}
    public static Map<String, String> parse(String raw) throws IOException {
        Scanner s = new Scanner(raw);
        Map<String,String> result = new LinkedHashMap<>();
        s.ws(); s.expect('{'); s.ws();
        if (s.accept('}')) { s.end(); return result; }
        while (true) {
            String key = s.str(); s.ws(); s.expect(':'); String value = s.str();
            if (result.putIfAbsent(key, value) != null) throw new IOException("Duplicate JSON key " + key);
            s.ws(); if (s.accept('}')) break; s.expect(',');
        }
        s.end(); return result;
    }
    public static String write(Map<String,String> entries) {
        StringBuilder out = new StringBuilder("{\n");
        int n = 0;
        for (var e : entries.entrySet()) {
            if (n++ > 0) out.append(",\n");
            out.append("  ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
        }
        return out.append("\n}\n").toString();
    }
    public static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    private static final class Scanner {
        final String s; int i;
        Scanner(String s) { this.s=s; }
        void ws() { while(i<s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        boolean accept(char c) { ws(); if(i<s.length() && s.charAt(i)==c){i++;return true;}return false; }
        void expect(char c) throws IOException { if(!accept(c))throw new IOException("JSON expected '"+c+"' at "+i); }
        void end() throws IOException { ws(); if(i!=s.length())throw new IOException("Trailing JSON data at "+i); }
        String str() throws IOException {
            ws(); expect('"'); StringBuilder b=new StringBuilder();
            while (i<s.length()) {
                char c=s.charAt(i++); if(c=='"')return b.toString();
                if(c!='\\'){ if(c < 32)throw new IOException("Control character in JSON");b.append(c);continue;}
                if(i>=s.length())break;
                char esc=s.charAt(i++);
                switch(esc) {
                    case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                    case 'u' -> { if(i+4>s.length())throw new IOException("Bad unicode escape");
                        try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){throw new IOException("Bad unicode escape",ex);}i+=4; }
                    default -> throw new IOException("Invalid JSON escape: "+esc);
                }
            }
            throw new IOException("Unterminated JSON string");
        }
    }
}
