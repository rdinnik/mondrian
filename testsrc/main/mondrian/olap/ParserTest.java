/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2004-2010 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap;

import mondrian.test.FoodMartTestCase;
import mondrian.olap.fun.BuiltinFunTable;
import mondrian.mdx.UnresolvedFunCall;
import mondrian.mdx.QueryPrintWriter;
import mondrian.test.TestContext;
import mondrian.util.Bug;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.List;

/**
 * Tests the MDX parser.
 *
 * @author gjohnson
 * @version $Id$
 */
public class ParserTest extends FoodMartTestCase {
    public ParserTest(String name) {
        super(name);
    }

    static final BuiltinFunTable funTable = BuiltinFunTable.instance();

    public void testAxisParsing() throws Exception {
        checkAxisAllWays(0, "COLUMNS");
        checkAxisAllWays(1, "ROWS");
        checkAxisAllWays(2, "PAGES");
        checkAxisAllWays(3, "CHAPTERS");
        checkAxisAllWays(4, "SECTIONS");
    }

    private void checkAxisAllWays(int axisOrdinal, String axisName) {
        checkAxis(axisOrdinal + "", axisName);
        checkAxis("AXIS(" + axisOrdinal + ")", axisName);
        checkAxis(axisName, axisName);
    }

    private void checkAxis(
        String s,
        String expectedName)
    {
        Parser p = new TestParser();
        String q = "select [member] on " + s + " from [cube]";
        QueryPart query = p.parseInternal(null, q, false, funTable, false);
        assertNull("Test parser should return null query", query);

        QueryAxis[] axes = ((TestParser) p).getAxes();

        assertEquals("Number of axes must be 1", 1, axes.length);
        assertEquals(
            "Axis index name must be correct",
            expectedName,
            axes[0].getAxisName());
    }

    public void testNegativeCases() throws Exception {
        assertParseQueryFails(
            "select [member] on axis(1.7) from sales",
            "Invalid axis specification. "
            + "The axis number must be non-negative integer, but it was 1.7.");
        assertParseQueryFails(
            "select [member] on axis(-1) from sales",
            "Syntax error at line");
        // used to be an error, no longer
        assertParseQuery(
            "select [member] on axis(5) from sales",
            "select [member] ON AXIS(5)\n"
            + "from [sales]\n");
        assertParseQueryFails(
            "select [member] on axes(0) from sales",
            "Syntax error at line");
        assertParseQueryFails(
            "select [member] on 0.5 from sales",
            "Invalid axis specification. "
            + "The axis number must be non-negative integer, but it was 0.5.");
        assertParseQuery(
            "select [member] on 555 from sales",
            "select [member] ON AXIS(555)\n"
            + "from [sales]\n");
    }

    public void testScannerPunc() {
        // '$' is OK inside brackets but not outside
        assertParseQuery(
            "select [measures].[$foo] on columns from sales",
            "select [measures].[$foo] ON COLUMNS\n"
            + "from [sales]\n");
        assertParseQueryFails(
            "select [measures].$foo on columns from sales",
            "Unexpected character '$'");

        // ']' unexcpected
        assertParseQueryFails(
            "select { Customers].Children } on columns from [Sales]",
            "Unexpected character ']'");
    }

    public void testUnparse() {
        checkUnparse(
            "with member [Measures].[Foo] as ' 123 '\n"
            + "select {[Measures].members} on columns,\n"
            + " CrossJoin([Product].members, {[Gender].Children}) on rows\n"
            + "from [Sales]\n"
            + "where [Marital Status].[S]",
            "with member [Measures].[Foo] as '123.0'\n"
            + "select {[Measures].Members} ON COLUMNS,\n"
            + "  Crossjoin([Product].Members, {[Gender].Children}) ON ROWS\n"
            + "from [Sales]\n"
            + "where [Marital Status].[S]\n");
    }

    private void checkUnparse(String queryString, final String expected) {
        final TestContext testContext = TestContext.instance();

        final Query query = testContext.getConnection().parseQuery(queryString);
        String unparsedQueryString = Util.unparse(query);
        TestContext.assertEqualsVerbose(expected, unparsedQueryString);
    }

    private void assertParseQueryFails(String query, String expected) {
        checkFails(new TestParser(), query, expected);
    }

    private void assertParseExprFails(String expr, String expected) {
        checkFails(new TestParser(), wrapExpr(expr), expected);
    }

    private void checkFails(Parser p, String query, String expected) {
        try {
            p.parseInternal(null, query, false, funTable, false);

            fail("Must return an error");
        } catch (Exception e) {
            Exception nested = (Exception) e.getCause();
            String message = nested.getMessage();
            if (message.indexOf(expected) < 0) {
                fail(
                    "Actual result [" + message
                    + "] did not contain [" + expected + "]");
            }
        }
    }

    public void testMultipleAxes() throws Exception {
        Parser p = new TestParser();
        String query = "select {[axis0mbr]} on axis(0), "
                + "{[axis1mbr]} on axis(1) from cube";

        assertNull(
            "Test parser should return null query",
            p.parseInternal(null, query, false, funTable, false));

        QueryAxis[] axes = ((TestParser) p).getAxes();

        assertEquals("Number of axes", 2, axes.length);
        assertEquals(
            "Axis index name must be correct",
            AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(0).name(),
            axes[0].getAxisName());
        assertEquals(
            "Axis index name must be correct",
            AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(1).name(),
            axes[1].getAxisName());

        query = "select {[axis1mbr]} on aXiS(1), "
                + "{[axis0mbr]} on AxIs(0) from cube";

        assertNull(
            "Test parser should return null query",
            p.parseInternal(null, query, false, funTable, false));

        assertEquals("Number of axes", 2, axes.length);
        assertEquals(
            "Axis index name must be correct",
            AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(0).name(),
            axes[0].getAxisName());
        assertEquals(
            "Axis index name must be correct",
            AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(1).name(),
            axes[1].getAxisName());

        Exp colsSetExpr = axes[0].getSet();
        assertNotNull("Column tuples", colsSetExpr);

        UnresolvedFunCall fun = (UnresolvedFunCall)colsSetExpr;
        Id.Segment id = ((Id)(fun.getArgs()[0])).getElement(0);
        assertEquals("Correct member on axis", "axis0mbr", id.name);

        Exp rowsSetExpr = axes[1].getSet();
        assertNotNull("Row tuples", rowsSetExpr);

        fun = (UnresolvedFunCall) rowsSetExpr;
        id = ((Id) (fun.getArgs()[0])).getElement(0);
        assertEquals("Correct member on axis", "axis1mbr", id.name);
    }

    /**
     * If an axis expression is a member, implicitly convert it to a set.
     */
    public void testMemberOnAxis() {
        assertParseQuery(
            "select [Measures].[Sales Count] on 0, non empty [Store].[Store State].members on 1 from [Sales]",
            "select [Measures].[Sales Count] ON COLUMNS,\n"
            + "  NON EMPTY [Store].[Store State].members ON ROWS\n"
            + "from [Sales]\n");
    }

    public void testCaseTest() {
        assertParseQuery(
            "with member [Measures].[Foo] as "
            + " ' case when x = y then \"eq\" when x < y then \"lt\" else \"gt\" end '"
            + "select {[foo]} on axis(0) from cube",
            "with member [Measures].[Foo] as 'CASE WHEN (x = y) THEN \"eq\" WHEN (x < y) THEN \"lt\" ELSE \"gt\" END'\n"
            + "select {[foo]} ON COLUMNS\n"
            + "from [cube]\n");
    }

    public void testCaseSwitch() {
        assertParseQuery(
            "with member [Measures].[Foo] as "
            + " ' case x when 1 then 2 when 3 then 4 else 5 end '"
            + "select {[foo]} on axis(0) from cube",
            "with member [Measures].[Foo] as 'CASE x WHEN 1.0 THEN 2.0 WHEN 3.0 THEN 4.0 ELSE 5.0 END'\n"
            + "select {[foo]} ON COLUMNS\n"
            + "from [cube]\n");
    }

    /**
     * Test case for bug <a href="http://jira.pentaho.com/browse/MONDRIAN-306">
     * MONDRIAN-306, "Parser should not require braces around range op in WITH
     * SET"</a>.
     */
    public void testSetExpr() {
        assertParseQuery(
            "with set [Set1] as '[Product].[Drink]:[Product].[Food]' \n"
            + "select [Set1] on columns, {[Measures].defaultMember} on rows \n"
            + "from Sales",
            "with set [Set1] as '([Product].[Drink] : [Product].[Food])'\n"
            + "select [Set1] ON COLUMNS,\n"
            + "  {[Measures].defaultMember} ON ROWS\n"
            + "from [Sales]\n");

        // set expr in axes
        assertParseQuery(
            "select [Product].[Drink]:[Product].[Food] on columns,\n"
            + " {[Measures].defaultMember} on rows \n"
            + "from Sales",
            "select ([Product].[Drink] : [Product].[Food]) ON COLUMNS,\n"
            + "  {[Measures].defaultMember} ON ROWS\n"
            + "from [Sales]\n");
    }

    public void testDimensionProperties() {
        assertParseQuery(
                "select {[foo]} properties p1,   p2 on columns from [cube]",
                "select {[foo]} DIMENSION PROPERTIES p1, p2 ON COLUMNS\n"
                + "from [cube]\n");
    }

    public void testCellProperties() {
        assertParseQuery(
                "select {[foo]} on columns "
                + "from [cube] CELL PROPERTIES FORMATTED_VALUE",
                "select {[foo]} ON COLUMNS\n"
                + "from [cube]\n"
                + "[FORMATTED_VALUE]");
    }

    public void testIsEmpty() {
        assertParseExpr(
            "[Measures].[Unit Sales] IS EMPTY",
            "([Measures].[Unit Sales] IS EMPTY)");

        assertParseExpr(
            "[Measures].[Unit Sales] IS EMPTY AND 1 IS NULL",
            "(([Measures].[Unit Sales] IS EMPTY) AND (1.0 IS NULL))");

        // FIXME: "NULL" should associate as "IS NULL" rather than "NULL + 56.0"
        assertParseExpr(
            "- x * 5 is empty is empty is null + 56",
            "(((((- x) * 5.0) IS EMPTY) IS EMPTY) IS (NULL + 56.0))");
    }

    public void testIs() {
        assertParseExpr(
            "[Measures].[Unit Sales] IS [Measures].[Unit Sales] "
            + "AND [Measures].[Unit Sales] IS NULL",
            "(([Measures].[Unit Sales] IS [Measures].[Unit Sales]) "
            + "AND ([Measures].[Unit Sales] IS NULL))");
    }

    public void testIsNull() {
        assertParseExpr(
            "[Measures].[Unit Sales] IS NULL",
            "([Measures].[Unit Sales] IS NULL)");

        assertParseExpr(
            "[Measures].[Unit Sales] IS NULL AND 1 <> 2",
            "(([Measures].[Unit Sales] IS NULL) AND (1.0 <> 2.0))");

        assertParseExpr(
            "x is null or y is null and z = 5",
            "((x IS NULL) OR ((y IS NULL) AND (z = 5.0)))");

        assertParseExpr(
            "(x is null) + 56 > 6", "((((x IS NULL)) + 56.0) > 6.0)");

        // FIXME: Should be
        //  "(((((x IS NULL) AND (a = b)) OR ((c = (d + 5.0))) IS NULL) + 5.0)"
        assertParseExpr(
            "x is null and a = b or c = d + 5 is null + 5",
            "(((x IS NULL) AND (a = b)) OR ((c = (d + 5.0)) IS (NULL + 5.0)))");
    }

    public void testNull() {
        assertParseExpr(
            "Filter({[Measures].[Foo]}, Iif(1 = 2, NULL, 'X'))",
            "Filter({[Measures].[Foo]}, Iif((1.0 = 2.0), NULL, \"X\"))");
    }

    public void testCast() {
        assertParseExpr(
            "Cast([Measures].[Unit Sales] AS Numeric)",
            "CAST([Measures].[Unit Sales] AS Numeric)");

        assertParseExpr(
            "Cast(1 + 2 AS String)", "CAST((1.0 + 2.0) AS String)");
    }

    /**
     * Verifies that calculated measures made of several '*' operators
     * can resolve them correctly.
     */
    public void testMultiplication() {
        Parser p = new Parser();
        final String mdx =
            wrapExpr(
                "([Measures].[Unit Sales]"
                + " * [Measures].[Store Cost]"
                + " * [Measures].[Store Sales])");

        try {
            final QueryPart query =
                p.parseInternal(getConnection(), mdx, false, funTable, false);
            assertTrue(query instanceof Query);
            ((Query) query).resolve();
        } catch (Throwable e) {
            fail(e.getMessage());
        }
    }

    public void testBangFunction() {
        // Parser accepts '<id> [! <id>] *' as a function name, but ignores
        // all but last name.
        assertParseExpr("foo!bar!Exp(2.0)", "Exp(2.0)");
        assertParseExpr("1 + VBA!Exp(2.0 + 3)", "(1.0 + Exp((2.0 + 3.0)))");
    }

    public void testId() {
        assertParseExpr("foo", "foo");
        assertParseExpr("fOo", "fOo");
        assertParseExpr("[Foo].[Bar Baz]", "[Foo].[Bar Baz]");
        assertParseExpr("[Foo].&[Bar]", "[Foo].&[Bar]");
    }

    public void testCloneQuery() {
        Connection connection = TestContext.instance().getFoodMartConnection();
        Query query = connection.parseQuery(
            "select {[Measures].Members} on columns,\n"
            + " {[Store].Members} on rows\n"
            + "from [Sales]\n"
            + "where ([Gender].[M])");

        Object queryClone = query.clone();
        assertTrue(queryClone instanceof Query);
        assertEquals(query.toString(), queryClone.toString());
    }

    /**
     * Tests parsing of numbers.
     */
    public void testNumbers() {
        // Number: [+-] <digits> [ . <digits> ] [e [+-] <digits> ]
        assertParseExpr("2", "2.0");

        // leading '-' is treated as an operator -- that's ok
        assertParseExpr("-3", "(- 3.0)");

        // leading '+' is ignored -- that's ok
        assertParseExpr("+45", "45.0");

        // space bad
        assertParseExprFails(
            "4 5",
            "Syntax error at line 1, column 35, token '5.0'");

        assertParseExpr("3.14", "3.14");
        assertParseExpr(".12345", "0.12345");

        // lots of digits left and right of point
        assertParseExpr("31415926535.89793", "3.141592653589793E10");
        assertParseExpr(
            "31415926535897.9314159265358979",
            "3.141592653589793E13");
        assertParseExpr("3.141592653589793", "3.141592653589793");
        assertParseExpr(
            "-3141592653589793.14159265358979",
            "(- 3.141592653589793E15)");

        // exponents akimbo
        assertParseExpr("1e2", "100.0");
        assertParseExprFails(
            "1e2e3",
            "Syntax error at line 1, column 37, token 'e3'");
        assertParseExpr("1.2e3", "1200.0");
        assertParseExpr("-1.2345e3", "(- 1234.5)");
        assertParseExprFails(
            "1.2e3.4",
            "Syntax error at line 1, column 39, token '0.4'");
        assertParseExpr(".00234e0003", "2.34");
        assertParseExpr(".00234e-0067", "2.34E-70");
    }

    /**
     * Testcase for bug <a href="http://jira.pentaho.com/browse/MONDRIAN-272">
     * MONDRIAN-272, "High precision number in MDX causes overflow"</a>.
     * The problem was that "5000001234" exceeded the precision of the int being
     * used to gather the mantissa.
     */
    public void testLargePrecision() {
        // Now, a query with several numeric literals. This is the original
        // testcase for the bug.
        assertParseQuery(
            "with member [Measures].[Small Number] as '[Measures].[Store Sales] / 9000'\n"
            + "select\n"
            + "{[Measures].[Small Number]} on columns,\n"
            + "{Filter([Product].[Product Department].members, [Measures].[Small Number] >= 0.3\n"
            + "and [Measures].[Small Number] <= 0.5000001234)} on rows\n"
            + "from Sales\n"
            + "where ([Time].[1997].[Q2].[4])",
            "with member [Measures].[Small Number] as '([Measures].[Store Sales] / 9000.0)'\n"
            + "select {[Measures].[Small Number]} ON COLUMNS,\n"
            + "  {Filter([Product].[Product Department].members, (([Measures].[Small Number] >= 0.3) AND ([Measures].[Small Number] <= 0.5000001234)))} ON ROWS\n"
            + "from [Sales]\n"
            + "where ([Time].[1997].[Q2].[4])\n");
    }

    public void testEmptyExpr() {
        assertParseQuery(
            "SELECT NON EMPTY HIERARCHIZE({DrillDownLevelTop({[Product].[All Products]},\n"
            + "3, , [Measures].[Unit Sales])}) on columns from [Sales]",
            "select NON EMPTY HIERARCHIZE({DrillDownLevelTop({[Product].[All Products]}, 3.0, , [Measures].[Unit Sales])}) ON COLUMNS\n"
            + "from [Sales]\n");
    }

    /**
     * Test case for bug <a href="http://jira.pentaho.com/browse/MONDRIAN-648">
     * MONDRIAN-648, "AS operator has lower precedence than required by MDX
     * specification"</a>.
     *
     * <p>Currently that bug is not fixed. We give the AS operator low
     * precedence, so CAST works as it should but 'expr AS namedSet' does not.
     */
    public void testAsPrecedence() {
        // low precedence operator (AND) in CAST.
        assertParseQuery(
            "select cast(a and b as string) on 0 from cube",
            "select CAST((a AND b) AS string) ON COLUMNS\n"
            + "from [cube]\n");

        // medium precedence operator (:) in CAST
        assertParseQuery(
            "select cast(a : b as string) on 0 from cube",
            "select CAST((a : b) AS string) ON COLUMNS\n"
            + "from [cube]\n");

        // high precedence operator (IS) in CAST
        assertParseQuery(
            "select cast(a is b as string) on 0 from cube",
            "select CAST((a IS b) AS string) ON COLUMNS\n"
            + "from [cube]\n");

        // low precedence operator in axis expression. According to spec, 'AS'
        // has higher precedence than '*' but we give it lower. Bug.
        assertParseQuery(
            "select a * b as c on 0 from cube",
            Bug.BugMondrian648Fixed
                ? "select (a * (b AS c) ON COLUMNS\n"
                  + "from [cube]\n"
                : "select ((a * b) AS c) ON COLUMNS\n"
                  + "from [cube]\n");

        if (Bug.BugMondrian648Fixed) {
            // Note that 'AS' has higher precedence than '*'.
            assertParseQuery(
                "select a * b as c * d on 0 from cube",
                "select (((a * b) AS c) * d) ON COLUMNS\n"
                + "from [cube]\n");

        } else {
            // Bug. Even with MONDRIAN-648, Mondrian should parse this query.
            assertParseQueryFails(
                "select a * b as c * d on 0 from cube",
                "Syntax error at line 1, column 19, token '*'");
        }

        // Spec says that ':' has a higher precedence than '*'.
        // Mondrian currently does it wrong.
        assertParseQuery(
            "select a : b * c : d on 0 from cube",
            Bug.BugMondrian648Fixed
                ? "select ((a : b) * (c : d)) ON COLUMNS\n"
                  + "from [cube]\n"
                : "select ((a : (b * c)) : d) ON COLUMNS\n"
                  + "from [cube]\n");

        if (Bug.BugMondrian648Fixed) {
            // Note that 'AS' has higher precedence than ':', has higher
            // precedence than '*'.
            assertParseQuery(
                "select a : b as n * c : d as n2 as n3 on 0 from cube",
                "select (((a : b) as n) * ((c : d) AS n2) as n3) ON COLUMNS\n"
                + "from [cube]\n");
        } else {
            // Bug. Even with MONDRIAN-648, Mondrian should parse this query.
            assertParseQueryFails(
                "select a : b as n * c : d as n2 as n3 on 0 from cube",
                "Syntax error at line 1, column 19, token '*'");
        }
    }

    public void testDrillThrough() {
        assertParseQuery(
            "DRILLTHROUGH SELECT [Foo] on 0, [Bar] on 1 FROM [Cube]",
            "drillthrough\n"
            + "select [Foo] ON COLUMNS,\n"
            + "  [Bar] ON ROWS\n"
            + "from [Cube]\n");
    }

    public void testDrillThroughExtended() {
        assertParseQuery(
            "DRILLTHROUGH MAXROWS 5 FIRSTROWSET 7\n"
            + "SELECT [Foo] on 0, [Bar] on 1 FROM [Cube]\n"
            + "RETURN [Xxx].[AAa], [YYY]",
            "drillthrough maxrows 5 firstrowset 7\n"
            + "select [Foo] ON COLUMNS,\n"
            + "  [Bar] ON ROWS\n"
            + "from [Cube]\n"
            + " return  return [Xxx].[AAa], [YYY]");
    }

    /**
     * Parses an MDX query and asserts that the result is as expected when
     * unparsed.
     *
     * @param mdx MDX query
     * @param expected Expected result of unparsing
     */
    private void assertParseQuery(String mdx, final String expected) {
        Parser p = new TestParser();
        final QueryPart query =
            p.parseInternal(null, mdx, false, funTable, false);
        if (!(query instanceof DrillThrough)) {
            assertNull("Test parser should return null query", query);
        }
        final String actual = ((TestParser) p).toMdxString();
        TestContext.assertEqualsVerbose(expected, actual);
    }

    /**
     * Parses an MDX expression and asserts that the result is as expected when
     * unparsed.
     *
     * @param expr MDX query
     * @param expected Expected result of unparsing
     */
    private void assertParseExpr(String expr, final String expected) {
        TestParser p = new TestParser();
        final String mdx = wrapExpr(expr);
        final QueryPart query =
            p.parseInternal(null, mdx, false, funTable, false);
        assertNull("Test parser should return null query", query);
        final String actual = Util.unparse(p.formulas[0].getExpression());
        TestContext.assertEqualsVerbose(expected, actual);
    }

    private String wrapExpr(String expr) {
        return
            "with member [Measures].[Foo] as "
            + expr
            + "\n select from [Sales]";
    }

    public static class TestParser extends Parser {
        private Formula[] formulas;
        private QueryAxis[] axes;
        private String cube;
        private Exp slicer;
        private QueryPart[] cellProps;
        private boolean drillThrough;
        private int maxRowCount;
        private int firstRowOrdinal;
        private List<Exp> returnList;

        public TestParser() {
            super();
        }

        @Override
        protected Query makeQuery(
            Formula[] formulae,
            QueryAxis[] axes,
            String cube,
            Exp slicer,
            QueryPart[] cellProps)
        {
            setFormulas(formulae);
            setAxes(axes);
            setCube(cube);
            setSlicer(slicer);
            setCellProps(cellProps);
            return null;
        }

        @Override
        protected DrillThrough makeDrillThrough(
            Query query,
            int maxRowCount,
            int firstRowOrdinal,
            List<Exp> returnList)
        {
            this.drillThrough = true;
            this.maxRowCount = maxRowCount;
            this.firstRowOrdinal = firstRowOrdinal;
            this.returnList = returnList;
            return null;
        }

        public QueryAxis[] getAxes() {
            return axes;
        }

        public void setAxes(QueryAxis[] axes) {
            this.axes = axes;
        }

        public QueryPart[] getCellProps() {
            return cellProps;
        }

        public void setCellProps(QueryPart[] cellProps) {
            this.cellProps = cellProps;
        }

        public String getCube() {
            return cube;
        }

        public void setCube(String cube) {
            this.cube = cube;
        }

        public Formula[] getFormulas() {
            return formulas;
        }

        public void setFormulas(Formula[] formulas) {
            this.formulas = formulas;
        }

        public Exp getSlicer() {
            return slicer;
        }

        public void setSlicer(Exp slicer) {
            this.slicer = slicer;
        }

        /**
         * Converts this query to a string.
         *
         * @return This query converted to a string
         */
        public String toMdxString() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new QueryPrintWriter(sw);
            unparse(pw);
            return sw.toString();
        }

        private void unparse(PrintWriter pw) {
            if (drillThrough) {
                pw.print("drillthrough");
                if (maxRowCount > 0) {
                    pw.print(" maxrows " + maxRowCount);
                }
                if (firstRowOrdinal > 0) {
                    pw.print(" firstrowset " + firstRowOrdinal);
                }
                pw.println();
            }
            if (formulas != null) {
                for (int i = 0; i < formulas.length; i++) {
                    if (i == 0) {
                        pw.print("with ");
                    } else {
                        pw.print("  ");
                    }
                    formulas[i].unparse(pw);
                    pw.println();
                }
            }
            pw.print("select ");
            if (axes != null) {
                for (int i = 0; i < axes.length; i++) {
                    axes[i].unparse(pw);
                    if (i < axes.length - 1) {
                        pw.println(",");
                        pw.print("  ");
                    } else {
                        pw.println();
                    }
                }
            }
            if (cube != null) {
                pw.println("from [" + cube + "]");
            }
            if (slicer != null) {
                pw.print("where ");
                slicer.unparse(pw);
                pw.println();
            }
            if (cellProps != null) {
                for (QueryPart cellProp : cellProps) {
                    cellProp.unparse(pw);
                }
            }
            if (drillThrough && returnList != null) {
                pw.print(" return ");
                ExpBase.unparseList(
                    pw, returnList.toArray(new Exp[returnList.size()]),
                    " return ", ", ", "");
            }
        }
    }
}

// End ParserTest.java
