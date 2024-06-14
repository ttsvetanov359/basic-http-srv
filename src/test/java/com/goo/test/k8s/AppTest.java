package com.goo.test.k8s;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppTest {

    @Test
    public void testParams1() throws Exception {
        HttpServer.parseParams(new String[] { "-p", "43211", "-b", "22", "--dry-run" });
        assertEquals(true, HttpServer.hasDryRunParam());
    }

    @Test
    public void testParams2() throws Exception {
        HttpServer.parseParams(new String[] { "-p", "12345", "-b", "22", "--enable-monitoring" });
        assertEquals(true, HttpServer.hasDryRunParam());
    }

}
