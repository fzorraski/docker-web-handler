package br.com.fzdevx.infrastructure.util;

public class SanitizeHtml {

    public static String html2text (String html){
        return html.replaceAll("\\<[^>]*>","--");
    }
}
