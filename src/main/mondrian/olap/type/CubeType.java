/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// (C) Copyright 2005-2005 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.type;

import mondrian.olap.Cube;
import mondrian.olap.Dimension;
import mondrian.olap.Hierarchy;
import mondrian.olap.Level;

/**
 * The type of an expression which represents a Cube or Virtual Cube.
 *
 * @author jhyde
 * @since Feb 17, 2005
 * @version $Id$
 */
public class CubeType implements Type {
    private final Cube cube;

    /**
     * Creates a type representing a cube.
     */
    public CubeType(Cube cube) {
        this.cube = cube;
    }

    public boolean usesDimension(Dimension dimension) {
        return false;
    }

    public Hierarchy getHierarchy() {
        return null;
    }

    public Level getLevel() {
        return null;
    }
}

// End CubeType.java