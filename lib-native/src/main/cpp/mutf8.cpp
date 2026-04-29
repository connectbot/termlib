/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "mutf8.h"

/**
 * Converts Standard UTF-8 to Modified UTF-8 (JNI format).
 *
 * @param utf8_in Pointer to standard UTF-8 data.
 * @param len Length of the input data in bytes.
 * @param out_len Pointer to size_t to store the length of the result (optional).
 * @return Pointer to a null-terminated MUTF-8 string (must be freed by caller).
 */
char* utf8_to_mutf8(const char* utf8_in, size_t len, size_t* out_len) {
    if (!utf8_in) return NULL;

    const uint8_t* ptr = (const uint8_t*)utf8_in;
    const uint8_t* end = ptr + len;

    // Pass 1: Calculate required length
    // MUTF-8 can be larger than UTF-8 (Null: 1->2 bytes, Emojis: 4->6 bytes)
    size_t needed_size = 0;
    const uint8_t* p = ptr;
    while (p < end) {
        uint8_t c = *p;
        if (c == 0x00) {
            needed_size += 2; // 0xC0 0x80
            p++;
        } else if ((c & 0xF8) == 0xF0) {
            // 4-byte sequence (supplementary char) -> 2x 3-byte surrogates
            needed_size += 6;
            p += 4;
        } else {
            // 1, 2, or 3 byte sequences remain same length
            // Just advance pointer
            int step = 1;
            if ((c & 0xE0) == 0xC0) step = 2;
            else if ((c & 0xF0) == 0xE0) step = 3;
            needed_size += step;
            p += step;
        }
    }

    // Allocate memory (+1 for null terminator)
    char* mutf8_out = (char*)malloc(needed_size + 1);
    if (!mutf8_out) return NULL;

    // Pass 2: Encode
    uint8_t* out = (uint8_t*)mutf8_out;
    p = ptr;

    while (p < end) {
        uint8_t c = *p;

        if (c == 0x00) {
            *out++ = 0xC0;
            *out++ = 0x80;
            p++;
        } else if ((c & 0xF8) == 0xF0) {
            // Decode 4-byte UTF-8
            uint32_t cp = ((p[0] & 0x07) << 18) |
                          ((p[1] & 0x3F) << 12) |
                          ((p[2] & 0x3F) << 6)  |
                          (p[3] & 0x3F);
            p += 4;

            // Convert to Surrogate Pair
            cp -= 0x10000;
            uint32_t high = 0xD800 + ((cp >> 10) & 0x3FF);
            uint32_t low  = 0xDC00 + (cp & 0x3FF);

            // Encode High Surrogate (3 bytes)
            *out++ = (uint8_t)(0xE0 | ((high >> 12) & 0x0F));
            *out++ = (uint8_t)(0x80 | ((high >> 6)  & 0x3F));
            *out++ = (uint8_t)(0x80 | (high         & 0x3F));

            // Encode Low Surrogate (3 bytes)
            *out++ = (uint8_t)(0xE0 | ((low >> 12) & 0x0F));
            *out++ = (uint8_t)(0x80 | ((low >> 6)  & 0x3F));
            *out++ = (uint8_t)(0x80 | (low         & 0x3F));
        } else {
            // Copy 1, 2, or 3 byte sequences directly
            int step = 1;
            if ((c & 0xE0) == 0xC0) step = 2;
            else if ((c & 0xF0) == 0xE0) step = 3;

            for (int i = 0; i < step && p < end; i++) {
                *out++ = *p++;
            }
        }
    }

    *out = '\0'; // Null terminate
    if (out_len) *out_len = (size_t)((char*)out - mutf8_out);

    return mutf8_out;
}

/**
 * Converts Modified UTF-8 (JNI format) to Standard UTF-8.
 *
 * @param mutf8_in Pointer to Modified UTF-8 data.
 * @param len Length of the input data in bytes.
 * @param out_len Pointer to size_t to store the length of the result (optional).
 * @return Pointer to a null-terminated UTF-8 string (must be freed by caller).
 */
char* mutf8_to_utf8(const char* mutf8_in, size_t len, size_t* out_len) {
    if (!mutf8_in) return NULL;

    const uint8_t* ptr = (const uint8_t*)mutf8_in;
    const uint8_t* end = ptr + len;

    // Pass 1 is unnecessary for allocation size because Standard UTF-8
    // is always <= Modified UTF-8 size (surrogates shrink 6->4, nulls shrink 2->1).
    // We can allocate len + 1 safely.
    char* utf8_out = (char*)malloc(len + 1);
    if (!utf8_out) return NULL;

    uint8_t* out = (uint8_t*)utf8_out;
    const uint8_t* p = ptr;

    while (p < end) {
        uint8_t c = *p;

        // CASE 1: Null Character (0xC0 0x80 -> 0x00)
        if (c == 0xC0 && (p + 1 < end) && p[1] == 0x80) {
            *out++ = 0x00;
            p += 2;
        }
            // CASE 2: Surrogate Pair (6 bytes -> 4 bytes)
            // High Surrogate: 0xED 0xA0..0xAF 0x80..0xBF
        else if (c == 0xED && (p + 5 < end) &&
                 (p[1] >= 0xA0 && p[1] <= 0xAF) &&
                 (p[3] == 0xED && p[4] >= 0xB0 && p[4] <= 0xBF)) {

            // Decode High Surrogate
            uint32_t high = ((p[0] & 0x0F) << 12) |
                            ((p[1] & 0x3F) << 6)  |
                            (p[2] & 0x3F);

            // Decode Low Surrogate
            uint32_t low =  ((p[3] & 0x0F) << 12) |
                            ((p[4] & 0x3F) << 6)  |
                            (p[5] & 0x3F);

            // Recombine
            uint32_t cp = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);

            // Encode as 4-byte UTF-8
            *out++ = (uint8_t)(0xF0 | ((cp >> 18) & 0x07));
            *out++ = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
            *out++ = (uint8_t)(0x80 | ((cp >> 6)  & 0x3F));
            *out++ = (uint8_t)(0x80 | (cp         & 0x3F));

            p += 6;
        }
        else {
            // Copy 1, 2, or 3 byte sequences directly
            int step = 1;
            if ((c & 0xE0) == 0xC0) step = 2;
            else if ((c & 0xF0) == 0xE0) step = 3;

            // Safety bound check
            for(int i=0; i<step && p < end; i++) {
                *out++ = *p++;
            }
        }
    }

    *out = '\0'; // Null terminate
    if (out_len) *out_len = (size_t)((char*)out - utf8_out);

    return utf8_out;
}
