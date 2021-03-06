/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 1998-2002 Kana Software, Inc.
// Copyright (C) 2001-2005 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
//
// jhyde, 20 January, 1999
*/

package mondrian.olap;


/**
 * Lexical analyzer whose input is a string.
 */
public class StringScanner extends Scanner {
    private final String s;
    private int i;

    public StringScanner(String s, boolean debug) {
        super(debug);
        this.s = s;
        i = 0;
    }

    // Override Scanner.getChar().
    protected int getChar() {
        return (i >= s.length())
            ? -1
            : s.charAt(i++);
    }
}

// End StringScanner.java
