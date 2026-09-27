/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

// This file is available under and governed by the GNU General Public
// License version 2 only, as published by the Free Software Foundation.
// However, the following notice accompanied the original version of this
// file:
//
// Copyright 2011 the V8 project authors. All rights reserved.
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
//       notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
//       copyright notice, this list of conditions and the following
//       disclaimer in the documentation and/or other materials provided
//       with the distribution.
//     * Neither the name of Google Inc. nor the names of its
//       contributors may be used to endorse or promote products derived
//       from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package hope.magic.js.runtime.doubleconv;

import java.nio.charset.StandardCharsets;

import hope.magic.runtime.Magic;
import hope.magic.runtime.Schubfach;

/**
 * This class provides the public API for the double conversion package.
 */
public final class DoubleConversion {

    private final static int BUFFER_LENGTH = 30;

    static {
        if (!Magic.isInstalled()) {
            Magic.install();
        }
    }

    private static final ThreadLocal<DtoaBuffer> LOCAL_BUFFER =
            ThreadLocal.withInitial(() -> new DtoaBuffer(FastDtoa.kFastDtoaMaximalLength));

    private static final ThreadLocal<byte[]> LOCAL_DIGITS =
            ThreadLocal.withInitial(() -> new byte[24]);

    /**
     * Converts a double number to its shortest string representation according to ECMA-262 § 7.1.12.1.
     * Formats directly into a Latin-1 byte buffer to avoid ThreadLocal DtoaBuffer, StringBuilder allocation,
     * and char-to-byte transcoding overhead, mirroring OpenJDK's {@code DoubleToDecimal.toString} mechanism.
     *
     * @param value number to convert
     * @return formatted number
     */
    public static String toShortestString(final double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (value == 0.0) {
            return "0";
        }

        final byte[] str = new byte[32];
        final int len = toShortestBytes(value, str, 0);
        return new String(str, 0, len, StandardCharsets.ISO_8859_1);
    }

    /**
     * Appends a double number to the target StringBuilder in its shortest string representation,
     * achieving zero intermediate object allocation.
     *
     * @param sb target StringBuilder
     * @param value number to convert
     */
    public static void appendTo(final StringBuilder sb, final double value) {
        final DtoaBuffer buffer = LOCAL_BUFFER.get();
        buffer.reset();
        final double absValue = Math.abs(value);

        if (value < 0) {
            buffer.isNegative = true;
        }

        final long packed = Schubfach.toDecimal(absValue, buffer.chars);
        if (packed == -1L) {
            sb.append(value);
            return;
        }
        buffer.decimalPoint = (int) (packed >> 32);
        buffer.length = (int) packed;

        buffer.format(sb, DtoaMode.SHORTEST, 0);
    }

    /**
     * Formats the shortest representation of a double into the specified byte buffer
     * using the ECMA-262 § 7.1.12.1 rules.
     *
     * @param value double value to format
     * @param str target byte array
     * @param pos starting offset
     * @return final written offset (total bytes written = return value - starting offset)
     */
    public static int toShortestBytes(final double value, final byte[] str, int pos) {
        if (Double.isNaN(value)) {
            str[pos++] = 'N'; str[pos++] = 'a'; str[pos++] = 'N';
            return pos;
        }
        if (value == Double.POSITIVE_INFINITY) {
            str[pos++] = 'I'; str[pos++] = 'n'; str[pos++] = 'f'; str[pos++] = 'i';
            str[pos++] = 'n'; str[pos++] = 'i'; str[pos++] = 't'; str[pos++] = 'y';
            return pos;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            str[pos++] = '-';
            str[pos++] = 'I'; str[pos++] = 'n'; str[pos++] = 'f'; str[pos++] = 'i';
            str[pos++] = 'n'; str[pos++] = 'i'; str[pos++] = 't'; str[pos++] = 'y';
            return pos;
        }
        if (value == 0.0) {
            str[pos++] = '0';
            return pos;
        }

        if (value < 0.0) {
            str[pos++] = '-';
        }
        final double absValue = Math.abs(value);
        final byte[] digits = LOCAL_DIGITS.get();
        final long packed = Schubfach.toDecimal(absValue, digits);
        if (packed == -1L) {
            String s = String.valueOf(value);
            for (int i = 0; i < s.length(); i++) {
                str[pos++] = (byte) s.charAt(i);
            }
            return pos;
        }

        final int decimalPoint = (int) (packed >> 32);
        final int len = (int) packed;

        if (decimalPoint < -5 || decimalPoint > 21) {
            str[pos++] = digits[0];
            if (len > 1) {
                str[pos++] = '.';
                System.arraycopy(digits, 1, str, pos, len - 1);
                pos += len - 1;
            }
            str[pos++] = 'e';
            int exp = decimalPoint - 1;
            if (exp > 0) {
                str[pos++] = '+';
            } else {
                str[pos++] = '-';
                exp = -exp;
            }
            if (exp < 10) {
                str[pos++] = (byte) ('0' + exp);
            } else if (exp < 100) {
                str[pos++] = Schubfach.BYTE_DIGIT_TENS[exp];
                str[pos++] = Schubfach.BYTE_DIGIT_ONES[exp];
            } else {
                int d = exp / 100;
                str[pos++] = (byte) ('0' + d);
                int rem = exp - d * 100;
                str[pos++] = Schubfach.BYTE_DIGIT_TENS[rem];
                str[pos++] = Schubfach.BYTE_DIGIT_ONES[rem];
            }
        } else if (decimalPoint <= 0) {
            str[pos++] = '0';
            str[pos++] = '.';
            int padding = -decimalPoint;
            for (int i = 0; i < padding; i++) {
                str[pos++] = '0';
            }
            System.arraycopy(digits, 0, str, pos, len);
            pos += len;
        } else if (decimalPoint >= len) {
            System.arraycopy(digits, 0, str, pos, len);
            pos += len;
            int zeros = decimalPoint - len;
            for (int i = 0; i < zeros; i++) {
                str[pos++] = '0';
            }
        } else {
            System.arraycopy(digits, 0, str, pos, decimalPoint);
            pos += decimalPoint;
            str[pos++] = '.';
            System.arraycopy(digits, decimalPoint, str, pos, len - decimalPoint);
            pos += len - decimalPoint;
        }
        return pos;
    }

    /**
     * Converts a double number to a string representation with a fixed number of digits
     * after the decimal point.
     *
     * @param value number to convert.
     * @param requestedDigits number of digits after decimal point
     * @return formatted number
     */
    public static String toFixed(final double value, final int requestedDigits) {
        final DtoaBuffer buffer = new DtoaBuffer(BUFFER_LENGTH);
        final double absValue = Math.abs(value);

        if (value < 0) {
            buffer.isNegative = true;
        }

        if (value == 0) {
            buffer.append('0');
            buffer.decimalPoint = 1;
        } else if (!fixedDtoa(absValue, requestedDigits, buffer)) {
            buffer.reset();
            bignumDtoa(absValue, DtoaMode.FIXED, requestedDigits, buffer);
        }

        return buffer.format(DtoaMode.FIXED, requestedDigits);
    }

    /**
     * Converts a double number to a string representation with a fixed number of digits.
     *
     * @param value number to convert
     * @param precision number of digits to create
     * @return formatted number
     */
    public static String toPrecision(final double value, final int precision) {
        final DtoaBuffer buffer = new DtoaBuffer(precision);
        final double absValue = Math.abs(value);

        if (value < 0) {
            buffer.isNegative = true;
        }

        if (value == 0) {
            for (int i = 0; i < precision; i++) {
                buffer.append('0');
            }
            buffer.decimalPoint = 1;

        } else if (!fastDtoaCounted(absValue, precision, buffer)) {
            buffer.reset();
            bignumDtoa(absValue, DtoaMode.PRECISION, precision, buffer);
        }

        return buffer.format(DtoaMode.PRECISION, 0);
    }

    /**
     * Converts a double number to a string representation using the
     * {@code BignumDtoa} algorithm and the specified conversion mode
     * and number of digits.
     *
     * @param v number to convert
     * @param mode conversion mode
     * @param digits number of digits
     * @param buffer buffer to use
     */
    public static void bignumDtoa(final double v, final DtoaMode mode, final int digits, final DtoaBuffer buffer) {
        assert(v > 0);
        assert(!Double.isNaN(v));
        assert(!Double.isInfinite(v));

        BignumDtoa.bignumDtoa(v, mode, digits, buffer);
    }

    /**
     * Converts a double number to its shortest string representation
     * using the {@code FastDtoa} algorithm.
     *
     * @param v number to convert
     * @param buffer buffer to use
     * @return true if conversion succeeded
     */
    public static boolean fastDtoaShortest(final double v, final DtoaBuffer buffer) {
        assert(v > 0);
        assert(!Double.isNaN(v));
        assert(!Double.isInfinite(v));

        return FastDtoa.grisu3(v, buffer);
    }

    /**
     * Converts a double number to a string representation with the
     * given number of digits using the {@code FastDtoa} algorithm.
     *
     * @param v number to convert
     * @param precision number of digits to generate
     * @param buffer buffer to use
     * @return true if conversion succeeded
     */
    public static boolean fastDtoaCounted(final double v, final int precision, final DtoaBuffer buffer) {
        assert(v > 0);
        assert(!Double.isNaN(v));
        assert(!Double.isInfinite(v));

        return FastDtoa.grisu3Counted(v, precision, buffer);
    }

    /**
     * Converts a double number to a string representation with a
     * fixed number of digits after the decimal point using the
     * {@code FixedDtoa} algorithm.
     *
     * @param v number to convert.
     * @param digits number of digits after the decimal point
     * @param buffer buffer to use
     * @return true if conversion succeeded
     */
    public static boolean fixedDtoa(final double v, final int digits, final DtoaBuffer buffer) {
        assert(v > 0);
        assert(!Double.isNaN(v));
        assert(!Double.isInfinite(v));

        return FixedDtoa.fastFixedDtoa(v, digits, buffer);
    }

}
