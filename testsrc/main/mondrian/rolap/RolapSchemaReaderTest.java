/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// (C) Copyright 2004-2004 Julian Hyde and others.
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap;

import java.util.List;
import java.util.Arrays;

import junit.framework.TestCase;
import mondrian.olap.*;


/**
 * Unit test for {@link SchemaReader}.
 */
public class RolapSchemaReaderTest extends TestCase {
    public RolapSchemaReaderTest(String name) {
        super(name);
    }

    public void testGetCubes() throws Exception {
        System.out.println(MondrianProperties.instance().getTestConnectString());
        Util.PropertyList properties =
                Util.parseConnectString(MondrianProperties
                .instance()
                .getTestConnectString());

        properties.put(RolapConnectionProperties.Role, "No HR Cube");

        Connection connection = DriverManager.getConnection(properties, null, true);

        try {
            SchemaReader reader = connection.getSchemaReader();

            String[] expectedCubes = new String[] {
                "Sales", "Warehouse", "Warehouse and Sales", "Store", "Sales Ragged"
            };
            Cube[] cubes = reader.getCubes();

            assertEquals(expectedCubes.length, cubes.length);

            List cubesAsList = Arrays.asList(expectedCubes);

            for (int idx = 0; idx < cubes.length; idx++) {
                String cubeName = cubes[idx].getName();
                assertTrue("Cube name not found: " + cubeName, cubesAsList.contains(cubeName));
            }
        }
        finally {
            connection.close();
        }
    }
}