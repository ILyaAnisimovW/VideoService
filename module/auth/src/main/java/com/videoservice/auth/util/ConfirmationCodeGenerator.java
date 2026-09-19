package com.videoservice.auth.util;

import java.security.SecureRandom;

public class ConfirmationCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate() {
        int code = 100000 + RANDOM.nextInt(900000); // 6 цифр
        return String.valueOf(code);
    }
}