/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.webresources;

import java.util.BitSet;
import java.util.Collection;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * This class represents the contents of a jar by determining whether a given resource <b>might</b> be in the cache,
 * based on a bloom filter. This is not a general-purpose bloom filter because it contains logic to strip out characters
 * from the beginning of the key. The hash methods are simple but good enough for this purpose.
 */
public final class JarContents {

    /**
     * Constant used by a typical hashing method.
     */
    private static final int HASH_PRIME_1 = 31;

    /**
     * Constant used by a typical hashing method.
     */
    private static final int HASH_PRIME_2 = 17;

    /**
     * Size of the fixed-length bit table. Larger reduces false positives, smaller saves memory.
     */
    private static final int TABLE_SIZE = 2048;

    private final BitSet bits1 = new BitSet(TABLE_SIZE);
    private final BitSet bits2 = new BitSet(TABLE_SIZE);


    /**
     * Parses the passed-in jar and populates the bit array.
     *
     * @param jar the JAR file
     */
    public JarContents(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            processEntry(entry);
        }
    }


    /**
     * Populates the bit array from the provided set of JAR entries.
     *
     * @param entries The set of entries for the JAR file being processed
     */
    public JarContents(Collection<JarEntry> entries) {
        for (JarEntry entry : entries) {
            processEntry(entry);
        }
    }


    private void processEntry(JarEntry entry) {
        String name = entry.getName();
        int startPos = 0;

        // If the path starts with a slash, that's not useful information.
        // Skipping it increases the significance of our key by
        // removing an insignificant character.
        boolean precedingSlash = name.charAt(0) == '/';
        if (precedingSlash) {
            startPos = 1;
        }

        // Versioned entries should be added to the table according to their real name
        if (name.startsWith("META-INF/versions/", startPos)) {
            int i = name.indexOf('/', 18 + startPos);
            if (i > 0) {
                int version = Integer.parseInt(name.substring(18 + startPos, i));
                if (version <= Runtime.version().feature()) {
                    startPos = i + 1;
                }
            }
            if (startPos == name.length()) {
                return;
            }
        }

        // Find the correct table slot
        int pathHash1 = hashcode(name, startPos, HASH_PRIME_1);
        int pathHash2 = hashcode(name, startPos, HASH_PRIME_2);

        bits1.set(pathHash1 % TABLE_SIZE);
        bits2.set(pathHash2 % TABLE_SIZE);

        // While directory entry names always end in "/", application code
        // may look them up without the trailing "/". Add this second form.
        if (entry.isDirectory()) {
            pathHash1 = hashcode(name, startPos, name.length() - 1, HASH_PRIME_1);
            pathHash2 = hashcode(name, startPos, name.length() - 1, HASH_PRIME_2);

            bits1.set(pathHash1 % TABLE_SIZE);
            bits2.set(pathHash2 % TABLE_SIZE);
        }
    }

    /**
     * Simple hashcode of a portion of the string. Typically we would use substring, but memory and runtime speed are
     * critical.
     *
     * @param content  Wrapping String.
     * @param startPos First character in the range.
     *
     * @return hashcode of the range.
     */
    private int hashcode(String content, int startPos, int hashPrime) {
        return hashcode(content, startPos, content.length(), hashPrime);
    }

    private int hashcode(String content, int startPos, int endPos, int hashPrime) {
        int h = hashPrime / 2;
        for (int i = startPos; i < endPos; i++) {
            h = hashPrime * h + content.charAt(i);
        }

        if (h < 0) {
            h = h * -1;
        }
        return h;
    }


    /**
     * Method that identifies whether a given path <b>MIGHT</b> be in this jar. Uses the Bloom filter mechanism.
     *
     * @param path       Requested path. Sometimes starts with "/WEB-INF/classes".
     * @param webappRoot The value of the webapp location, which can be stripped from the path. Typically it is
     *                       "/WEB-INF/classes".
     *
     * @return Whether the prefix of the path is known to be in this jar.
     */
    public boolean mightContainResource(String path, String webappRoot) {
        int startPos = 0;
        if (path.startsWith(webappRoot)) {
            startPos = webappRoot.length();
        }

        if (path.charAt(startPos) == '/') {
            // ignore leading slash
            startPos++;
        }

        // calculate the hash lazily and return a boolean value for this path
        return (bits1.get(hashcode(path, startPos, HASH_PRIME_1) % TABLE_SIZE) &&
                bits2.get(hashcode(path, startPos, HASH_PRIME_2) % TABLE_SIZE));
    }

}