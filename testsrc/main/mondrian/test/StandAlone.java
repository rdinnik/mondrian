/*
 * Copyright 2003 by Alphablox Corp. All rights reserved.
 *
 * Created by gjohnson
 * Last change: $Modtime: $
 * Last author: $Author$
 * Revision: $Revision$
 */
package mondrian.test;

import mondrian.olap.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.MessageFormat;

public class StandAlone {
    private static final String[] indents = new String[]{
        "    ", "        ", "            ", "                "
    };

    private static Connection cxn;

    private static String cellProp;
    private static BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

//    public static final String ConnectionString = "Provider=mondrian;" +
//        "Jdbc=jdbc:JSQLConnect://gjlaptop:1433/database=RDBPerformance/user=galt/password=password;" +
//        "Catalog=file:RdbCubePerformance.xml;" +
//        "JdbcDrivers=com.jnetdirect.jsql.JSQLDriver;";
    public static final String ConnectionString = "Provider=mondrian;" +
        "Jdbc=jdbc:JSQLConnect://engdb04:1433/database=MondrianFoodmart/user=mondrian/password=password;" +
        "Catalog=file:demo\\FoodMart.xml;" +
        "JdbcDrivers=com.jnetdirect.jsql.JSQLDriver;";

    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        java.sql.DriverManager.setLogWriter(new PrintWriter(System.err));

        cxn = DriverManager.getConnection(ConnectionString, null, true);

        System.out.println("Connected in " + (System.currentTimeMillis() - now) + " usec");
        processCommands();
    }

    private static void processCommands() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        long startTime = System.currentTimeMillis();

        inputLoop:
        for (; ;) {
            try {
                String line = in.readLine();
                if (line == null) {
                    break inputLoop;
                }

                if (line.equals("\\q")) {
                    break inputLoop;
                }
                else if (line.startsWith("\\")) {
                    processSlashCommand(line);
                }
                else {
                    StringBuffer qb = new StringBuffer();
                    qb.append(line);

                    for (;;) {
                        System.out.print("> ");
                        line = in.readLine();
                        if (line.equals(".")) {
                            break;
                        }

                        qb.append(' ');
                        qb.append(line);
                    }

                    long queryStart = System.currentTimeMillis();

                    String queryString = qb.toString();
                    boolean printResults = false;
                    if (qb.substring(0, 1).equals("-")) {
                        queryString = qb.substring(1);
                        printResults = true;
                    }

                    Query query = cxn.parseQuery(queryString);
                    Result result = cxn.execute(query);
                    displayElapsedTime(queryStart, "Elapsed time");

                    printResult(result, printResults);
                }
            }
            catch (IOException ioe) {
                ioe.printStackTrace();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        displayElapsedTime(startTime, "Connect time");

    }

    private static void displayElapsedTime(long startTime, String message) {
        long elapsed = System.currentTimeMillis() - startTime;
        int seconds, msecs;

        msecs = (int) (elapsed % 1000);
        seconds = (int) (elapsed / 1000);

        System.out.println(MessageFormat.format("{2}: {0,number,0}.{1,number,000} ({3})",
            new Object[] {new Integer(seconds), new Integer(msecs), message, new Long(elapsed)}));
    }

    private static void printResult(Result result, boolean outputResults) {
        Axis slicer = result.getSlicerAxis();
        int nonNullCellCount = 0;
        int cellCount = 0;
        int numRows = 0;
        int numColumns;

        Position[] slicerpositions = slicer.positions;

        int numSlicers = slicer.positions.length;

        if (numSlicers > 0 && outputResults) {
            System.out.print("Slicers: {");
            for (int idx = 0; idx < slicerpositions.length; idx++) {
                Position pos = slicerpositions[idx];

                printMembers(pos);
            }

            System.out.println("}");
        }

        Axis[] axes = result.getAxes();
        if (axes.length == 0) {
            numColumns = 0;
            cellCount = 1;

            if (outputResults) {
                System.out.println("No axes.");
                Cell cell = result.getCell(new int[0]);
                printCell(cell);
            }
        }
        else if (axes.length == 1) {
            // Only columns
            Position[] cols = axes[0].positions;

            numColumns = cols.length;

            for (int idx = 0; idx < cols.length; idx++) {
                Position col = cols[idx];

                if (outputResults) {
                    System.out.print("Column " + idx + ": ");
                    printMembers(col);
                }


                Cell cell = result.getCell(new int[]{idx});

                if (!cell.isNull()) {
                    nonNullCellCount++;
                }

                cellCount++;

                if (outputResults) {
                    printCell(cell);
                }
            }
        }
        else {
            Position[] colPositions = axes[0].positions;
            Position[] rowPositions = axes[1].positions;

            numColumns = colPositions.length;
            numRows = rowPositions.length;

            int[] coords = new int[2];

            if (outputResults) {
                System.out.println("Column tuples: ");
            }

            for (int colIdx = 0; colIdx < colPositions.length; colIdx++) {
                Position col = colPositions[colIdx];

                if (outputResults) {
                    System.out.print("Column " + colIdx + ": ");
                    printMembers(col);
                    System.out.println();
                }

                for (int rowIdx = 0; rowIdx < rowPositions.length; rowIdx++) {
                    if (outputResults) {
                        System.out.print("(" + colIdx + ", " + rowIdx + ") ");
                        printMembers(rowPositions[rowIdx]);
                        System.out.print("} = ");
                    }

                    coords[0] = colIdx;
                    coords[1] = rowIdx;

                    Cell cell = result.getCell(coords);

                    if (!cell.isNull()) {
                        nonNullCellCount++;
                    }

                    cellCount++;

                    if (outputResults) {
                        printCell(cell);
                    }
                }
            }
        }

        System.out.println("cellCount: " + cellCount);
        System.out.println("nonNullCellCount: " + nonNullCellCount);
        System.out.println("numSlicers: " + numSlicers);
        System.out.println("numColumns: " + numColumns);
        System.out.println("numRows: " + numRows);
    }

    private static void printCell(Cell cell) {
        Object cellPropValue;

        if (cellProp != null) {
            cellPropValue = cell.getPropertyValue(cellProp);
            System.out.print("(" + cellPropValue + ")");
        }


        System.out.println(cell.getFormattedValue());
    }
    private static void printMembers(Position pos) {
        Member[] members = pos.members;
        boolean needComma = false;

        for (int midx = 0; midx < members.length; midx++) {
            Member member = members[midx];

            if (needComma) {
                System.out.print(',');
            }
            needComma = true;

            System.out.print(member.getUniqueName());
            Property[] props =  member.getProperties();

            if (props.length > 0) {
                System.out.print(" {");
                for (int idx = 0; idx < props.length; idx++) {
                    if (idx > 1) {
                        System.out.print(", ");
                    }
                    Property prop = props[idx];

                    System.out.print(prop.getName() + ": " + member.getPropertyValue(prop.getName()));

                }
                System.out.print("}");
            }
        }
    }

    private static void processSlashCommand(String line) throws IOException {
        if (line.equals("\\schema")) {
            printSchema(cxn.getSchema());
        }
        else if (line.equals("\\dbg")) {

            PrintWriter out = java.sql.DriverManager.getLogWriter();

            if (out == null) {
                java.sql.DriverManager.setLogWriter(new PrintWriter(System.err));
                System.out.println("SQL driver logging enabled");
            }
            else {
                java.sql.DriverManager.setLogWriter(null);
                System.out.println("SQL driver logging disabled");
            }

            cxn.close();
            cxn = DriverManager.getConnection(ConnectionString, null, true);
        }
        else if (line.equals("\\cp")) {
            System.out.print("Enter cell property: ");
            cellProp = stdin.readLine();
        }
        else {
            System.out.println("Commands:");
            System.out.println("\t\\q        Quit");
            System.out.println("\t\\schema   Print the schema");
            System.out.println("\t\\dbg      Toggle SQL driver debugging");
        }
    }

    private static void printSchema(Schema schema) {
        Cube[] cubes = schema.getCubes();
        Hierarchy[] hierarchies = schema.getSharedHierarchies();

        System.out.println("Schema: " + schema.getName() + " " + cubes.length + " cubes and " + hierarchies.length + " shared hierarchies");

        System.out.println("---Cubes ");
        for (int idx = 0; idx < cubes.length; idx++) {
            printCube(cubes[idx]);
            System.out.println("-------------------------------------------");
        }

        System.out.println("---Shared hierarchies");
        for (int idx = 0; idx < hierarchies.length; idx++) {
            printHierarchy(0, hierarchies[idx]);
        }
    }

    private static void printCube(Cube cube) {
        System.out.println("Cube " + cube.getName());

        Dimension[] dims = cube.getDimensions();

        for (int idx = 0; idx < dims.length; idx++) {
            printDimension(dims[idx]);
        }
    }

    private static void printDimension(Dimension dim) {
        System.out.println("\tDimension " + dim.getName()
            + " type: " + (dim.getDimensionType() == Dimension.STANDARD ? "standard" : "time"));

        System.out.println("\t    Description: " + dim.getDescription());
        Hierarchy[] hierarchies = dim.getHierarchies();

        for (int idx = 0; idx < hierarchies.length; idx++) {
            printHierarchy(1, hierarchies[idx]);
        }

    }


    private static void printHierarchy(int indent, Hierarchy hierarchy) {
        String indentString = indents[indent];

        System.out.println(indentString + " Hierarchy " + hierarchy.getName());
        System.out.println(indentString + "    Description: " + hierarchy.getDescription());
        System.out.println(indentString + "    Default member: " + hierarchy.getDefaultMember().getUniqueName());

        Level[] levels = hierarchy.getLevels();

        for (int idx = 0; idx < levels.length; idx++) {
            printLevel(indent + 1, levels[idx]);
        }
    }

    private static void printLevel(int indent, Level level) {
        String indentString = indents[indent];

        System.out.println(indentString + "Level " + level.getName());

        level.unparse(new PrintWriter(System.out));
    }
}