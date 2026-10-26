package com.fincore.identity.domain;

import java.util.ArrayList;
import java.util.List;

/** Length 12-72 (72 is bcrypt's input limit), mixed character classes, must not contain the username. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {}

    public static List<String> violations(String password, String username) {
        List<String> v = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            v.add("at least " + MIN_LENGTH + " characters");
            return v;
        }
        if (password.length() > MAX_LENGTH) v.add("at most " + MAX_LENGTH + " characters");
        if (password.chars().noneMatch(Character::isUpperCase)) v.add("an uppercase letter");
        if (password.chars().noneMatch(Character::isLowerCase)) v.add("a lowercase letter");
        if (password.chars().noneMatch(Character::isDigit)) v.add("a digit");
        if (password.chars().allMatch(Character::isLetterOrDigit)) v.add("a symbol");
        if (username != null && username.length() >= 3 && password.toLowerCase().contains(username.toLowerCase())) {
            v.add("must not contain the username");
        }
        return v;
    }
}
