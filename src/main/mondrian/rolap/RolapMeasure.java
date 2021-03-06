/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2001-2002 Kana Software, Inc.
// Copyright (C) 2001-2009 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
//
// jhyde, 10 August, 2001
*/

package mondrian.rolap;
import mondrian.olap.Member;
import mondrian.olap.CellFormatter;

/**
 * Interface implemented by all measures (both stored and calculated).
 *
 * @author jhyde
 * @since 10 August, 2001
 * @version $Id$
 */
public interface RolapMeasure extends Member {
    /**
     * Returns the object that formats cells of this measure, or null to use
     * default formatting.
     *
     * @return formatter
     */
    CellFormatter getFormatter();
}

// End RolapMeasure.java
