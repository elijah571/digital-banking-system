package org.elibanking.walletservice.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * A console layout that masks secrets in every formatted line (wallet-plan 3.5, threat model T4). It is a second line of
 * defence: the code never logs a PIN, an OTP or a password, and a test checks that. This layout catches a mistake that
 * slips through, in JSON ({@code "pin":"1234"}) or key=value ({@code pin=1234}) form.
 */
public class MaskingPatternLayout extends PatternLayout {

    /** The key, its separator, and the value up to the next quote, comma, brace or space. The key must be a whole word. */
    static final Pattern SECRET = Pattern.compile(
            "(?i)(\\b(?:pin|otp|password|currentPassword|newPassword)\"?\\s*[:=]\\s*\"?)([^\"',}\\s]+)");

    @Override
    public String doLayout(ILoggingEvent event) {
        return mask(super.doLayout(event));
    }

    /** The text with every secret value replaced by four asterisks. */
    public static String mask(String text) {
        return SECRET.matcher(text).replaceAll("$1****");
    }
}
