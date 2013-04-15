/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.server.types3;

import com.akiban.junit.NamedParameterizedRunner;
import com.akiban.junit.NamedParameterizedRunner.TestParameters;
import com.akiban.junit.Parameterization;
import com.akiban.junit.ParameterizationBuilder;
import com.akiban.server.types3.aksql.aktypes.AkBool;
import com.akiban.server.types3.mcompat.mtypes.MString;
import com.akiban.server.types3.pvalue.PValue;
import com.akiban.server.types3.pvalue.PValueSource;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(NamedParameterizedRunner.class)
public final class BooleanParserTest {

    @TestParameters
    public static Collection<Parameterization> params() {
        ParameterizationBuilder builder = new ParameterizationBuilder();

        param(builder, "1", true);
        param(builder, "1-1", true);
        param(builder, "1.1", true);
        param(builder, "1.1.1.1.1", true);
        param(builder, ".1", true); // this is weird. ".1" as tinyint is 0, and booleans in mysql are tinyint. but
                                    // (false OR ".1") results in a tinyint 1 (ie, true). Gotta love MySQL.
        param(builder, "0.1", true);
        param(builder, "-1", true);
        param(builder, "-1.1-a", true);
        param(builder, ".-1", false);
        param(builder, "-.1", true);
        param(builder, "-..1", false);
        param(builder, "1a", true);
        param(builder, "a1", false); // MySQL doesn't believe in steak sauce
        param(builder, "0", false);
        param(builder, "0.0", false);

        param(builder, "false", false);
        param(builder, "f", false);
        // Following are not MySQL compatible, but required for ActiveRecord.
        param(builder, "true", true);
        param(builder, "t", true);

        return builder.asList();
    }

    private static void param(ParameterizationBuilder builder, String string, boolean expected) {
        builder.add(string, string, expected);
    }

    public BooleanParserTest(String string, boolean expected) {
        this.string = string;
        this.expected = expected;
    }

    private String string;
    private boolean expected;

    @Test
    public void checkParse() {
        PValueSource source = new PValue(MString.varcharFor(string), string);
        PValue target = new PValue(AkBool.INSTANCE.instance(true));
        TParsers.BOOLEAN.parse(null, source, target);
        Boolean actual = target.isNull() ? null : target.getBoolean();
        assertEquals(string, Boolean.valueOf(expected), actual);
    }
}
