/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.pg;

import static junit.framework.Assert.assertEquals;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PostgresServerLargeSortKeysIT extends PostgresServerFilesITBase {
    private final static int MAX_STRING_LENGTH = 2500;

    private final static int D = 2;
    private final static int[] LENGTHS = { 60, 200, 255, 256, 257, MAX_STRING_LENGTH };
    private final static int[] POSITIONS = { 50, -50 };
    private final static Boolean[] CASE_CONVERSIONS = { false, true };
    private final static String[] REPLACEMENTS = { " Cat ", " Mouse " };

    private final static int L = LENGTHS.length;
    private final static int P = POSITIONS.length;
    private final static int R = REPLACEMENTS.length;
    private final static int C = CASE_CONVERSIONS.length;

    private final static int T = D * L * L * L * L * P * R * C;

    private final static String SQL_ALL = "select a, b, c, d from t1";
    private final static String SQL_DISTINCT_ALL = "select distinct a, b, c, d from t1";
    private final static String SQL_DISTINCT_AB = "select distinct a, b from t1";
    private final static String SQL_DISTINCT_CD = "select distinct c, d from t1";
    private final static String SQL_DISTINCT_BCD = "select distinct b, c, d from t1";

    private final static String BIG_STRING;
    static {
        StringBuilder sb = new StringBuilder(MAX_STRING_LENGTH);
        for (int i = 0; i < MAX_STRING_LENGTH; i++) {
            sb.append((char) ('A' + (i % 25)));
        }
        BIG_STRING = sb.toString();
    }

    @Before
    public void loadDatabase() throws Exception {
        createTable(SCHEMA_NAME, "t1", "id int NOT NULL", "PRIMARY KEY(id)",
                "a varchar(65535) CHARACTER SET latin1 COLLATE latin1_swedish_ci NOT NULL",
                "b varchar(65535) CHARACTER SET latin1 COLLATE latin1_swedish_ci NOT NULL",
                "c varchar(65535) CHARACTER SET latin1 COLLATE utf8_bin NOT NULL",
                "d varchar(65535) CHARACTER SET latin1 COLLATE utf8_bin NOT NULL");

        PreparedStatement stmt = getConnection().prepareStatement("insert into t1 (id, a, b, c, d) values (?,?,?,?,?)");
        /*
         * Insert 3 sets of duplicate rows
         */
        int count = 0;
        for (int dup = 0; dup < D; dup++) {
            for (int a : LENGTHS) {
                for (int b : LENGTHS) {
                    for (int c : LENGTHS) {
                        for (int d : LENGTHS) {
                            for (int p : POSITIONS) {
                                for (String s : REPLACEMENTS) {
                                    for (boolean cc : CASE_CONVERSIONS) {
                                        stmt.setInt(1, ++count);
                                        stmt.setString(2, strVal(a, s, p > 0 ? p : a + p, cc));
                                        stmt.setString(3, strVal(b, s, p > 0 ? p : b + p, cc));
                                        stmt.setString(4, strVal(c, s, p > 0 ? p : c + p, cc));
                                        stmt.setString(5, strVal(d, s, p > 0 ? p : d + p, cc));
                                        stmt.execute();
                                        assertEquals("Insert failed", 1, stmt.getUpdateCount());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private String strVal(int len, String replace, int position, boolean caseConvert) {
        StringBuilder sb = new StringBuilder(BIG_STRING);
        sb.replace(position, position + replace.length(), replace);
        String s = sb.substring(0, len);
        if (caseConvert) {
            s = s.toLowerCase();
        }
        return s;
    }

    @After
    public void dontLeaveConnection() throws Exception {
        // Tests change read only state. Easiest not to reuse.
        forgetConnection();
    }

    @Test
    public void countRows() throws Exception {
        assertEquals("Missing rows", T, countRows(SQL_ALL));
        assertEquals("Distinct rows", T / D, countRows(SQL_DISTINCT_ALL));
        assertEquals("Distinct rows", T / D / L / L, countRows(SQL_DISTINCT_CD));
        assertEquals("Distinct rows", T / D / L / L / C, countRows(SQL_DISTINCT_AB));
        assertEquals("Distinct rows", T / D / L, countRows(SQL_DISTINCT_BCD));
    }

    public int countRows(final String sql) throws Exception {

        Statement stmt = getConnection().createStatement();
        int count = 0;
        try {
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                count++;
            }
        } finally {
            stmt.close();
        }
        return count;
    }

}
