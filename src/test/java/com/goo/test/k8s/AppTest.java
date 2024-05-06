package com.goo.test.k8s;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppTest {

    @Test
    public void testParams() throws Exception {
        HttpServer.parseParams(new String[]{"-p", "43211", "-b", "22", "-dry-run"});
        assertEquals(true, HttpServer.isDryRun());
    }
    
}
