package org.restnext.util;

import org.fusesource.jansi.Ansi;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

/**
 * Created by thiago on 25/11/16.
 */
public class AnsiUtilsTest {

    @BeforeClass
    public static void setUp() {
        AnsiUtils.install();
    }

    @AfterClass
    public static void tearDown() {
        AnsiUtils.uninstall();
    }

    @Test
    public void illegalInstatiationUtillityClass() throws IllegalAccessException, InstantiationException, NoSuchMethodException {
        try {
            Constructor c = AnsiUtils.class.getDeclaredConstructor();
            assertTrue(Modifier.isPrivate(c.getModifiers()));
            c.setAccessible(true);
            c.newInstance();
        } catch (InvocationTargetException e) {
            assertThat(e.getCause(), is(instanceOf(AssertionError.class)));
        }
    }

    @Test
    public void ansiMessagesTest() {
        assertEquals("\u001B[32minfo\u001B[m", AnsiUtils.info("info"));
        assertEquals("\u001B[33;1mwarn\u001B[m", AnsiUtils.warn("warn"));
        assertEquals("\u001B[31;1merror\u001B[m", AnsiUtils.error("error"));
    }

    @Test
    public void ansiBoldMessagesTest() {
        assertEquals("\u001B[32minfo\u001B[m", AnsiUtils.info("info"));
        assertEquals("\u001B[32;1minfoBold\u001B[m", AnsiUtils.createAnsi("infoBold", Ansi.Color.GREEN, true));

        assertEquals("\u001B[33;1mwarn\u001B[m", AnsiUtils.warn("warn"));
        assertEquals("\u001B[33;1mwarnBold\u001B[m", AnsiUtils.createAnsi("warnBold", Ansi.Color.YELLOW, true));

        assertEquals("\u001B[31;1merror\u001B[m", AnsiUtils.error("error"));
        assertEquals("\u001B[31;1merrorBold\u001B[m", AnsiUtils.createAnsi("errorBold", Ansi.Color.RED, true));
    }

}
