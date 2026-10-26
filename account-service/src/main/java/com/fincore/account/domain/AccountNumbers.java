package com.fincore.account.domain;

import java.security.SecureRandom;

/** Generates "FC" + 10 digits (synthetic account numbers; uniqueness is enforced by a DB constraint, retried on clash). */
public final class AccountNumbers {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccountNumbers() {}

    public static String next() {
        return String.format("FC%010d", RANDOM.nextLong(10_000_000_000L));
    }

    public static boolean isValid(String number) {
        return number != null && number.matches("^FC\\d{10}$");
    }
}
