package modtools.utils.doubleconv;

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

import java.nio.charset.StandardCharsets;


/**
 * This class provides the public API for the double conversion package.
 */
public final class DoubleConversion {

	private static final ThreadLocal<byte[]> LOCAL_DIGITS =
	 ThreadLocal.withInitial(() -> new byte[24]);
	private static final ThreadLocal<char[]> LOCAL_CHARS  =
	 ThreadLocal.withInitial(() -> new char[24]);
	public static void appendTo(StringBuilder sb, final float value) {
		if (Float.isNaN(value)) {
			sb.append("NaN");
			return;
		}
		if (value == Float.POSITIVE_INFINITY) {
			sb.append("+∞");
			return;
		}
		if (value == Float.NEGATIVE_INFINITY) {
			sb.append("-∞");
			return;
		}
		internalAppendTo(sb, value);
		// sb.append('F');
	}
	public static void appendTo(StringBuilder sb, final double value) {
		if (Double.isNaN(value)) {
			sb.append("NaN");
			return;
		}
		if (value == Double.POSITIVE_INFINITY) {
			sb.append("+∞");
			return;
		}
		if (value == Double.NEGATIVE_INFINITY) {
			sb.append("-∞");
			return;
		}
		internalAppendTo(sb, value);
		// sb.append('D');
	}

	public static void internalAppendTo(StringBuilder sb, float value) {
		if (value == 0.0f) {
			sb.append('0');
			return;
		}
		if (value < 0.0f) {
			sb.append('-');
			value = -value;
		}

		final char[] digits = LOCAL_CHARS.get();
		long packed = Schubfach.toDecimal(value, digits);
		if (packed == -1L) {
			sb.append(value);
			return;
		}

		int decimalPoint = (int) (packed >> 32);
		int len          = (int) packed;
		formatDigits(sb, digits, decimalPoint, len);
	}

	public static void internalAppendTo(StringBuilder sb, double value) {
		if (value == 0.0) {
			sb.append('0');
			return;
		}
		if (value < 0.0) {
			sb.append('-');
			value = -value;
		}

		final char[] digits = LOCAL_CHARS.get();
		long packed = Schubfach.toDecimal(value, digits);
		if (packed == -1L) {
			sb.append(value);
			return;
		}

		int decimalPoint = (int) (packed >> 32);
		int len          = (int) packed;
		formatDigits(sb, digits, decimalPoint, len);
	}

	@SuppressWarnings("StringRepeatCanBeUsed")
	private static void formatDigits(StringBuilder sb, char[] digits, int decimalPoint, int len) {
		if (decimalPoint < -5 || decimalPoint > 21) {
			sb.append(digits[0]);
			if (len > 1) {
				sb.append('.');
				sb.append(digits, 1, len - 1);
			}
			sb.append('e');
			int exp = decimalPoint - 1;
			if (exp > 0) {
				sb.append('+');
			} else {
				sb.append('-');
				exp = -exp;
			}
			if (exp < 10) {
				sb.append((char) ('0' + exp));
			} else if (exp < 100) {
				sb.append((char) Schubfach.BYTE_DIGIT_TENS[exp]);
				sb.append((char) Schubfach.BYTE_DIGIT_ONES[exp]);
			} else {
				int d = exp / 100;
				sb.append((char) ('0' + d));
				int rem = exp - d * 100;
				sb.append((char) Schubfach.BYTE_DIGIT_TENS[rem]);
				sb.append((char) Schubfach.BYTE_DIGIT_ONES[rem]);
			}
		} else if (decimalPoint <= 0) {
			sb.append('0');
			sb.append('.');
			int padding = -decimalPoint;
			for (int i = 0; i < padding; i++) {
				sb.append('0');
			}
			sb.append(digits, 0, len);
		} else if (decimalPoint >= len) {
			sb.append(digits, 0, len);
			int zeros = decimalPoint - len;
			for (int i = 0; i < zeros; i++) {
				sb.append('0');
			}
		} else {
			sb.append(digits, 0, decimalPoint);
			sb.append('.');
			sb.append(digits, decimalPoint, len - decimalPoint);
		}
	}

	/**
	 * Converts a double number to its shortest string representation according to ECMA-262 § 7.1.12.1.
	 * Formats directly into a Latin-1 byte buffer to avoid ThreadLocal DtoaBuffer, StringBuilder allocation,
	 * and char-to-byte transcoding overhead, mirroring OpenJDK's {@code DoubleToDecimal.toString} mechanism.
	 * @param value number to convert
	 * @return formatted number
	 */
	public static String toShortestString(final float value) {
		if (Float.isNaN(value)) {
			return "NaN";
		}
		if (value == Float.POSITIVE_INFINITY) {
			return "Infinity";
		}
		if (value == Float.NEGATIVE_INFINITY) {
			return "-Infinity";
		}
		if (value == 0.0f) {
			return "0";
		}

		final byte[] str = new byte[32];
		final int    len = toShortestBytes(value, str, 0);
		return new String(str, 0, len, StandardCharsets.ISO_8859_1);
	}

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
		final int    len = toShortestBytes(value, str, 0);
		return new String(str, 0, len, StandardCharsets.ISO_8859_1);
	}

	public static int toShortestBytes(final float value, final byte[] str, int pos) {
		if (Float.isNaN(value)) {
			str[pos++] = 'N';
			str[pos++] = 'a';
			str[pos++] = 'N';
			return pos;
		}
		if (value == Float.POSITIVE_INFINITY) {
			str[pos++] = 'I';
			str[pos++] = 'n';
			str[pos++] = 'f';
			str[pos++] = 'i';
			str[pos++] = 'n';
			str[pos++] = 'i';
			str[pos++] = 't';
			str[pos++] = 'y';
			return pos;
		}
		if (value == Float.NEGATIVE_INFINITY) {
			str[pos++] = '-';
			str[pos++] = 'I';
			str[pos++] = 'n';
			str[pos++] = 'f';
			str[pos++] = 'i';
			str[pos++] = 'n';
			str[pos++] = 'i';
			str[pos++] = 't';
			str[pos++] = 'y';
			return pos;
		}
		if (value == 0.0f) {
			str[pos++] = '0';
			return pos;
		}

		if (value < 0.0f) {
			str[pos++] = '-';
		}
		final float  absValue = Math.abs(value);
		final byte[] digits   = LOCAL_DIGITS.get();
		final long   packed   = Schubfach.toDecimal(absValue, digits);
		if (packed == -1L) {
			String s = String.valueOf(value);
			for (int i = 0; i < s.length(); i++) {
				str[pos++] = (byte) s.charAt(i);
			}
			return pos;
		}

		final int decimalPoint = (int) (packed >> 32);
		final int len          = (int) packed;
		return formatBytes(str, pos, digits, decimalPoint, len);
	}

	public static int toShortestBytes(final double value, final byte[] str, int pos) {
		if (Double.isNaN(value)) {
			str[pos++] = 'N';
			str[pos++] = 'a';
			str[pos++] = 'N';
			return pos;
		}
		if (value == Double.POSITIVE_INFINITY) {
			str[pos++] = 'I';
			str[pos++] = 'n';
			str[pos++] = 'f';
			str[pos++] = 'i';
			str[pos++] = 'n';
			str[pos++] = 'i';
			str[pos++] = 't';
			str[pos++] = 'y';
			return pos;
		}
		if (value == Double.NEGATIVE_INFINITY) {
			str[pos++] = '-';
			str[pos++] = 'I';
			str[pos++] = 'n';
			str[pos++] = 'f';
			str[pos++] = 'i';
			str[pos++] = 'n';
			str[pos++] = 'i';
			str[pos++] = 't';
			str[pos++] = 'y';
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
		final byte[] digits   = LOCAL_DIGITS.get();
		final long   packed   = Schubfach.toDecimal(absValue, digits);
		if (packed == -1L) {
			String s = String.valueOf(value);
			for (int i = 0; i < s.length(); i++) {
				str[pos++] = (byte) s.charAt(i);
			}
			return pos;
		}

		final int decimalPoint = (int) (packed >> 32);
		final int len          = (int) packed;
		return formatBytes(str, pos, digits, decimalPoint, len);
	}

	private static int formatBytes(byte[] str, int pos, byte[] digits, int decimalPoint, int len) {
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

}
