package com.university.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Pattern;

/** Pure, side-effect-free form validation. Used by every dialog in Phases 05–09. */
public final class ValidationUtil {

    private static final Pattern EMAIL    = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE    = Pattern.compile("^[+]?[0-9 ()-]{7,20}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._]{3,50}$");
    private static final Pattern COURSE   = Pattern.compile("^[A-Z]{2,4}[0-9]{3}$");   // CS201, MATH201
    private static final Pattern CODE     = Pattern.compile("^[A-Z0-9]{2,10}$");       // CS, BSCS
    private static final Pattern NUMERIC  = Pattern.compile("^[0-9]{4,20}$");          // 2021001234

    private ValidationUtil() { }

    public static boolean isBlank(String s)     { return s == null || s.trim().isEmpty(); }
    public static boolean notBlank(String s)    { return !isBlank(s); }
    public static String  trim(String s)        { return s == null ? null : s.trim(); }

    /** Returns null when s is blank, otherwise the trimmed value. For optional NVARCHAR columns. */
    public static String trimToNull(String s) {
        String t = trim(s);
        return (t == null || t.isEmpty()) ? null : t;
    }

    public static boolean isEmail(String s)         { return notBlank(s) && EMAIL.matcher(s.trim()).matches(); }
    public static boolean isPhone(String s)         { return notBlank(s) && PHONE.matcher(s.trim()).matches(); }
    public static boolean isUsername(String s)      { return notBlank(s) && USERNAME.matcher(s.trim()).matches(); }
    public static boolean isCourseCode(String s)    { return notBlank(s) && COURSE.matcher(s.trim().toUpperCase()).matches(); }
    public static boolean isShortCode(String s)     { return notBlank(s) && CODE.matcher(s.trim().toUpperCase()).matches(); }
    public static boolean isStudentNumber(String s) { return notBlank(s) && NUMERIC.matcher(s.trim()).matches(); }

    public static boolean maxLength(String s, int max) { return s == null || s.trim().length() <= max; }

    /**
     * Minimum strength for a password the user chooses themselves:
     * at least 8 characters, at least one letter and at least one digit.
     *
     * <p>The spec puts this on {@code PasswordUtil}; this project already hashes
     * through {@code service.PasswordHasher}, so the rule lives here with the
     * rest of the validation rather than being duplicated next to the BCrypt code.</p>
     */
    public static boolean isStrongPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) return false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : rawPassword.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    /** Parses an integer, returning null instead of throwing. */
    public static Integer parseInt(String s) {
        if (isBlank(s)) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    public static BigDecimal parseDecimal(String s) {
        if (isBlank(s)) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    public static boolean isIntInRange(String s, int min, int max) {
        Integer v = parseInt(s);
        return v != null && v >= min && v <= max;
    }

    public static boolean isPositiveInt(String s) {
        Integer v = parseInt(s);
        return v != null && v > 0;
    }

    /** true when both are non-null and start is strictly before end. */
    public static boolean isBefore(LocalDate start, LocalDate end) {
        return start != null && end != null && start.isBefore(end);
    }

    /** true when both are non-null and start is strictly before end. Used by the schedule editor. */
    public static boolean isBefore(LocalTime start, LocalTime end) {
        return start != null && end != null && start.isBefore(end);
    }

    /** Accepts "09:30" and "9:30". Returns null when the text is not a valid time. */
    public static LocalTime parseTime(String s) {
        if (isBlank(s)) return null;
        String t = s.trim();
        try {
            String[] parts = t.split(":");
            if (parts.length != 2) return null;
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return null;
        }
    }
}
