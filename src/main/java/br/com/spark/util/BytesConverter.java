package br.com.spark.util;

import java.text.DecimalFormat;

public class BytesConverter {

    public static Double bytesToMegaBytes(long size) {
        return (double) size / (1024 * 1024);

    }

    public static String bytesToMegabytesFormatted(long bytes, int precision) {
        StringBuilder pattern = new StringBuilder("0");
        if (precision > 0) {
            pattern.append(".");
            for (int i = 0; i < precision; i++) {
                pattern.append("0");
            }
        }

        DecimalFormat df = new DecimalFormat(pattern.toString());
        return df.format(bytesToMegaBytes(bytes)) + " MB";
    }
}
