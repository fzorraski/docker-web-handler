package br.com.fzdevx.util;

public class SanitizeHtml {

    public static String html2text (String html){
        return html.replaceAll("\\<[^>]*>","--");
    }
}
