/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2005-2005 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.test;

/**
 * Tests multiple hierarchies within the same dimension.
 *
 * @author jhyde
 * @version $Id$
 * @since Dec 15, 2005
 */
public class MultipleHierarchyTest extends FoodMartTestCase {
    public MultipleHierarchyTest(String name) {
        super(name);
    }

    public void testWeekly() {
        assertAxisReturns("{[Time].CurrentMember}", "[Time].[1997]");
        assertAxisReturns("{[Time.Weekly].CurrentMember}", "[Time.Weekly].[All Time.Weeklys].[1997]");
    }

    public void testWeekly2() {
        // When the context is one hierarchy,
        // the current member of other hierarchy must be its default member.
        assertQueryReturns(
                fold(new String[] {
                    "with",
                    "  member [Measures].[Foo] as ' [Time.Weekly].CurrentMember.UniqueName '",
                    "  member [Measures].[Foo2] as ' [Time].CurrentMember.UniqueName '",
                    "select",
                    "  {[Measures].[Unit Sales], [Measures].[Foo], [Measures].[Foo2]} on columns,",
                    "  {[Time].children} on rows",
                    "from [Sales]"}),
                fold(new String[] {
                    "Axis #0:",
                    "{}",
                    "Axis #1:",
                    "{[Measures].[Unit Sales]}" ,
                    "{[Measures].[Foo]}",
                    "{[Measures].[Foo2]}",
                    "Axis #2:",
                    "{[Time].[1997].[Q1]}",
                    "{[Time].[1997].[Q2]}",
                    "{[Time].[1997].[Q3]}",
                    "{[Time].[1997].[Q4]}",
                    "Row #0: 66,291",
                    "Row #0: [Time.Weekly].[All Time.Weeklys].[1997]",
                    "Row #0: [Time].[1997].[Q1]",
                    "Row #1: 62,610",
                    "Row #1: [Time.Weekly].[All Time.Weeklys].[1997]",
                    "Row #1: [Time].[1997].[Q2]",
                    "Row #2: 65,848",
                    "Row #2: [Time.Weekly].[All Time.Weeklys].[1997]",
                    "Row #2: [Time].[1997].[Q3]",
                    "Row #3: 72,024",
                    "Row #3: [Time.Weekly].[All Time.Weeklys].[1997]",
                    "Row #3: [Time].[1997].[Q4]",
                    ""}));
    }

    public void testMultipleSlicersFails() {
        assertThrows(fold(new String[] {
            "select {[Measures].[Unit Sales]} on columns,",
            " {[Store].children} on rows",
            "from [Sales]",
            "where ([Gender].[M], [Time.Weekly].[1997], [Time].[1997])"}),
            "Tuple contains more than one member of dimension '[Time]'.");
    }
}

// End MultipleHierarchyTest.java