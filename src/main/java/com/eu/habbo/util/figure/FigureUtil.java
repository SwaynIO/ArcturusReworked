package com.eu.habbo.util.figure;

import gnu.trove.map.hash.THashMap;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Map;
import java.util.Set;

public class FigureUtil {
    public static THashMap<String, String> getFigureBits(String looks) {
        THashMap<String, String> bits = new THashMap<>();
        String[] sets = looks.split("\\.");

        for (String set : sets) {
            String[] setBits = set.split("-", 2);
            bits.put(setBits[0], setBits.length > 1 ? setBits[1] : "");
        }

        return bits;
    }


    public static String mergeFigures(String figure1, String figure2) {
        return mergeFigures(figure1, figure2, null, null);
    }

    public static String mergeFigures(String figure1, String figure2, String[] limitFigure1) {
        return mergeFigures(figure1, figure2, limitFigure1, null);
    }

    public static boolean hasBlacklistedClothing(String figure, Set<Integer> blacklist) {
        for (String set : figure.split("\\.")) {
            String[] pieces = set.split("-");

            try {
                if (pieces.length >= 2 && blacklist.contains(Integer.valueOf(pieces[1]))) {
                    return true;
                }
            } catch (NumberFormatException ignored) {

            }
        }

        return false;
    }

    public static String mergeFigures(String figure1, String figure2, String[] limitFigure1, String[] limitFigure2) {
        THashMap<String, String> figureBits1 = getFigureBits(figure1);
        THashMap<String, String> figureBits2 = getFigureBits(figure2);

        // Pre-size StringBuilder for better performance (estimate ~200 chars)
        StringBuilder finalLook = new StringBuilder(200);

        // Convert limit arrays to Sets for O(1) lookup instead of O(n) contains()
        Set<String> limitSet1 = limitFigure1 != null ? Set.of(limitFigure1) : null;
        Set<String> limitSet2 = limitFigure2 != null ? Set.of(limitFigure2) : null;

        for (Map.Entry<String, String> entry : figureBits1.entrySet()) {
            if (limitSet1 == null || limitSet1.contains(entry.getKey())) {
                finalLook.append(entry.getKey()).append('-').append(entry.getValue()).append('.');
            }
        }

        for (Map.Entry<String, String> entry : figureBits2.entrySet()) {
            if (limitSet2 == null || limitSet2.contains(entry.getKey())) {
                finalLook.append(entry.getKey()).append('-').append(entry.getValue()).append('.');
            }
        }

        // More efficient: check length instead of creating substring to check endsWith
        if (finalLook.length() > 0 && finalLook.charAt(finalLook.length() - 1) == '.') {
            finalLook.setLength(finalLook.length() - 1);
        }

        return finalLook.toString();
    }
}